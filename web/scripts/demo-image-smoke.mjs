import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { createHmac, randomUUID } from 'node:crypto';
import {
  cpSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { basename, dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { setTimeout } from 'node:timers/promises';

const web = fileURLToPath(new URL('../', import.meta.url));
const temp = mkdtempSync(join(tmpdir(), 'rebyte-spa-smoke-'));
const name = `rebyte-spa-smoke-${randomUUID()}`;
const containers = [];
let networkCreated = false;
let imageCreated = false;
let checks = 0;
const docker = (...args) =>
  execFileSync('docker', args, { encoding: 'utf8', maxBuffer: 16 * 1024 * 1024 });
const dockerLogs = (container) => {
  const result = spawnSync('docker', ['logs', container], { encoding: 'utf8' });
  assert.equal(result.status, 0, 'Docker logs readable');
  return result.stdout + result.stderr;
};
const check = (actual, expected, label) => {
  assert.deepEqual(actual, expected, label);
  checks++;
};
const read = (path) => readFileSync(path, 'utf8');
const fixtureImage =
  'node:24-alpine@sha256:44fce696f38ba8c987c4db89954051000bbd05ffd8d8659ba15ed4a8602e937f';

async function startSpa(suffix, trusted) {
  const container = `${name}-${suffix}`;
  const mounts = trusted
    ? [
        '--mount',
        `type=bind,src=${join(temp, 'trust.pem')},dst=/etc/ssl/certs/ca-certificates.crt,readonly`,
      ]
    : [];
  containers.push(container);
  docker(
    'run',
    '-d',
    '--name',
    container,
    '--network',
    name,
    '-p',
    '127.0.0.1::80',
    ...mounts,
    name,
  );
  const port = docker('port', container, '80/tcp').trim().split(':').at(-1);
  const base = `http://127.0.0.1:${port}`;
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    try {
      if ((await fetch(base)).status === 200) return { container, base };
    } catch {}
    await setTimeout(200);
  }
  throw new Error('SPA image did not become ready within 30 seconds');
}

try {
  const dist = join(web, 'dist');
  const builds = readdirSync(dist).filter((entry) =>
    existsSync(join(dist, entry, 'browser/index.html')),
  );
  check(builds.length, 1, 'one actual Angular browser build');
  const context = join(temp, 'context');
  const browser = join(context, 'browser');
  cpSync(join(dist, builds[0], 'browser'), browser, { recursive: true });
  for (const file of ['config.json', 'config.demo.json'])
    rmSync(join(browser, file), { force: true });
  for (const file of readdirSync(browser, { recursive: true })) {
    if (!/\.(js|html|json|map|txt)$/.test(file)) continue;
    check(
      /eu-south-1_WPMqs7BW8|localhost:8080|AKIA[A-Z0-9]{16}|-----BEGIN (?:RSA |EC )?PRIVATE KEY-----/.test(
        read(join(browser, file)),
      ),
      false,
      `public asset ${file}`,
    );
  }
  for (const [source, target] of [
    ['Dockerfile.demo', 'Dockerfile.demo'],
    ['nginx.demo.conf', 'nginx.demo.conf'],
    ['public/config.demo.json', 'config.demo.json'],
  ]) {
    cpSync(join(web, source), join(context, target));
  }
  const config = JSON.parse(read(join(context, 'config.demo.json')));
  check(
    Object.keys(config).sort(),
    ['apiBaseUrl', 'cognito', 'demoCheckoutEnabled', 'wsUrl'],
    'public config allowlist',
  );
  check(
    Object.keys(config.cognito).sort(),
    ['region', 'userPoolClientId', 'userPoolId'],
    'public Cognito allowlist',
  );
  check(config.apiBaseUrl, 'https://api.rebyte.tech', 'demo API');
  check(config.wsUrl, 'wss://api.rebyte.tech/ws', 'demo WebSocket');
  check(config.demoCheckoutEnabled, true, 'demo checkout enabled');
  writeFileSync(join(browser, 'plain.js'), '// Unfingerprinted cache probe.\n');
  docker('build', '-f', join(context, 'Dockerfile.demo'), '-t', name, context);
  imageCreated = true;

  const fixture = join(temp, 'fixture');
  mkdirSync(fixture);
  const gitOpenSSL = join(
    process.env.ProgramFiles ?? 'C:/Program Files',
    'Git/usr/bin/openssl.exe',
  );
  const openssl = process.platform === 'win32' && existsSync(gitOpenSSL) ? gitOpenSSL : 'openssl';
  const ssl = (...args) => execFileSync(openssl, args, { cwd: fixture, stdio: 'pipe' });
  ssl(
    'req',
    '-x509',
    '-newkey',
    'rsa:2048',
    '-nodes',
    '-keyout',
    'ca.key',
    '-out',
    'ca.pem',
    '-subj',
    '/CN=RebyteSmokeCA',
    '-days',
    '1',
  );
  ssl(
    'req',
    '-newkey',
    'rsa:2048',
    '-nodes',
    '-keyout',
    'server.key',
    '-out',
    'server.csr',
    '-subj',
    '/CN=api.rebyte.tech',
  );
  writeFileSync(
    join(fixture, 'extensions'),
    'subjectAltName=DNS:api.rebyte.tech\nextendedKeyUsage=serverAuth\n',
  );
  ssl(
    'x509',
    '-req',
    '-in',
    'server.csr',
    '-CA',
    'ca.pem',
    '-CAkey',
    'ca.key',
    '-CAcreateserial',
    '-out',
    'server.pem',
    '-days',
    '1',
    '-extfile',
    'extensions',
  );
  writeFileSync(
    join(temp, 'trust.pem'),
    docker('run', '--rm', name, 'cat', '/etc/ssl/certs/ca-certificates.crt') +
      '\n' +
      read(join(fixture, 'ca.pem')),
  );
  writeFileSync(
    join(fixture, 'server.mjs'),
    `
import { createServer } from 'node:https';
import { createHmac, timingSafeEqual } from 'node:crypto';
import { readFileSync, appendFileSync } from 'node:fs';
const payload = Buffer.from('fixture-user').toString('base64url');
const signature = createHmac('sha256', 'disposable-smoke-fixture').update(payload).digest('base64url');
const expected = Buffer.from(payload + '.' + signature);
let mutations = 0;
createServer({ key: readFileSync('/fixture/server.key'), cert: readFileSync('/fixture/server.pem') }, (req, res) => {
  let body = '';
  req.on('data', chunk => body += chunk);
  req.on('end', () => {
    const url = new URL(req.url, 'https://api.rebyte.tech');
    const supplied = Buffer.from(url.searchParams.get('token') ?? '');
    const valid = supplied.length === expected.length && timingSafeEqual(supplied, expected);
    res.setHeader('x-smoke-host', req.headers.host);
    res.setHeader('x-smoke-sni', req.socket.servername);
    res.setHeader('x-smoke-method', req.method);
    if (url.pathname !== '/notifications/email-preferences/unsubscribe') res.statusCode = 404;
    else if (!valid) res.statusCode = 400;
    else if (req.method === 'POST' && body === 'List-Unsubscribe=One-Click') { mutations++; res.statusCode = 204; }
    else if (req.method === 'GET') { res.setHeader('Content-Type', 'text/html'); }
    else res.statusCode = 400;
    appendFileSync('/fixture/requests.jsonl', JSON.stringify({ url: req.url, method: req.method, body, mutations }) + '\\n');
    res.end(req.method === 'GET' && valid ? '<h1>Confirm unsubscribe</h1>' : '');
  });
}).listen(443, '0.0.0.0', () => console.log('ready'));
`,
  );
  docker('network', 'create', name);
  networkCreated = true;
  const upstream = `${name}-upstream`;
  containers.push(upstream);
  docker(
    'run',
    '-d',
    '--name',
    upstream,
    '--network',
    name,
    '--network-alias',
    'api.rebyte.tech',
    '--mount',
    `type=bind,src=${fixture},dst=/fixture`,
    fixtureImage,
    'node',
    '/fixture/server.mjs',
  );
  const upstreamDeadline = Date.now() + 30_000;
  while (!docker('logs', upstream).includes('ready')) {
    assert(Date.now() < upstreamDeadline, 'TLS fixture ready within 30 seconds');
    await setTimeout(200);
  }
  const { container, base } = await startSpa('trusted', true);
  for (const route of ['/', '/checkout/example', '/index.html']) {
    const response = await fetch(base + route, { redirect: 'manual' });
    check(response.status, 200, `SPA route ${route}`);
    check(response.headers.get('content-type').includes('text/html'), true, 'HTML MIME');
    check(response.headers.get('cache-control'), 'no-store', 'HTML not cached');
  }
  const loadedConfig = await fetch(base + '/config.json');
  check(loadedConfig.headers.get('content-type').includes('application/json'), true, 'JSON MIME');
  check(loadedConfig.headers.get('cache-control'), 'no-store', 'config not cached');
  check(await loadedConfig.json(), config, 'image contains exact demo config');
  for (const path of [
    '/config.demo.json',
    '/missing.js',
    '/missing.css',
    '/missing.json',
    '/assets/missing.png',
    '/media/missing.webp',
  ]) {
    check((await fetch(base + path)).status, 404, `missing static resource ${path}`);
  }
  const index = await (await fetch(base)).text();
  const script = index.match(/<script[^>]+src="([^"]+)"/)[1];
  const js = await fetch(new URL(script, base));
  check(js.headers.get('content-type').includes('javascript'), true, 'JavaScript MIME');
  check(
    js.headers.get('cache-control'),
    'public, max-age=31536000, immutable',
    'fingerprinted cache',
  );
  for (const path of ['/plain.js', '/favicon.ico']) {
    const response = await fetch(base + path);
    check(response.status, 200, `unhashed asset ${path}`);
    check(response.headers.get('cache-control'), 'no-cache', 'unhashed assets not immutable');
  }

  const payload = Buffer.from('fixture-user').toString('base64url');
  const token =
    payload +
    '.' +
    createHmac('sha256', 'disposable-smoke-fixture').update(payload).digest('base64url');
  const path = `/notifications/email-preferences/unsubscribe?token=${token}&encoded=a%2Bb%26c%3Dd`;
  const confirmation = await fetch(base + path, { redirect: 'manual' });
  check(confirmation.status, 200, 'signed confirmation');
  check(await confirmation.text(), '<h1>Confirm unsubscribe</h1>', 'scanner-safe GET');
  check(confirmation.headers.get('x-smoke-host'), 'api.rebyte.tech', 'upstream Host');
  check(confirmation.headers.get('x-smoke-sni'), 'api.rebyte.tech', 'upstream TLS SNI');
  check(confirmation.headers.get('x-smoke-method'), 'GET', 'GET preserved');
  check(
    (
      await fetch(base + path, {
        method: 'POST',
        body: 'List-Unsubscribe=One-Click',
        redirect: 'manual',
      })
    ).status,
    204,
    'one-click POST',
  );
  const badPath = path.replace(token, token + 'tampered');
  check(
    (await fetch(base + badPath, { method: 'POST', body: 'List-Unsubscribe=One-Click' })).status,
    400,
    'signature rejection preserved',
  );
  check((await fetch(base + path, { method: 'PUT' })).status, 405, 'other methods refused');
  const requests = read(join(fixture, 'requests.jsonl')).trim().split('\n').map(JSON.parse);
  check(
    requests.map((request) => request.mutations),
    [0, 1, 1],
    'GET and invalid signature never mutate',
  );
  check(requests[0].url, path, 'raw encoded query preserved');
  check(requests[1].url, path, 'POST query preserved');
  check(requests[1].body, 'List-Unsubscribe=One-Click', 'POST body preserved');
  check(requests[1].method, 'POST', 'POST method preserved');
  const untrusted = await startSpa('untrusted', false);
  check((await fetch(untrusted.base + path)).status, 502, 'untrusted upstream certificate refused');
  check(
    read(join(fixture, 'requests.jsonl')).trim().split('\n').length,
    3,
    'TLS failure never reaches handler',
  );
  for (const instance of [container, untrusted.container]) {
    const logs = dockerLogs(instance);
    check(logs.includes(token), false, 'signed tokens absent from logs');
    check(logs.includes('encoded=a%2B'), false, 'encoded query absent from logs');
    check(logs.includes('token='), false, 'token query absent even on TLS errors');
  }
  docker('exec', container, 'rm', '/usr/share/nginx/html/config.json');
  check((await fetch(base + '/config.json')).status, 404, 'missing config never becomes SPA HTML');
  console.log(`Demo SPA image smoke passed: ${checks} assertions (local TLS fixture only).`);
} finally {
  for (const container of containers.reverse()) {
    try {
      docker('rm', '-f', container);
    } catch {
      console.error('Could not remove owned smoke container');
      process.exitCode = 1;
    }
  }
  if (networkCreated) {
    try {
      docker('network', 'rm', name);
    } catch {
      console.error('Could not remove owned smoke network');
      process.exitCode = 1;
    }
  }
  if (imageCreated) {
    try {
      docker('image', 'rm', name);
    } catch {
      console.error('Could not remove owned smoke image');
      process.exitCode = 1;
    }
  }
  assert.equal(
    dirname(resolve(temp)),
    resolve(tmpdir()),
    'cleanup stays in the temporary directory',
  );
  assert(
    basename(temp).startsWith('rebyte-spa-smoke-'),
    'cleanup targets only this smoke workspace',
  );
  rmSync(temp, { recursive: true, force: true });
}
