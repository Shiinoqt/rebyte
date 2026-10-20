import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { createHash, generateKeyPairSync, randomUUID, sign } from 'node:crypto';
import { chmodSync, cpSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import https from 'node:https';
import { tmpdir } from 'node:os';
import { basename, join, resolve } from 'node:path';
import { setTimeout } from 'node:timers/promises';
import { WRITERS, PROTECTED } from '../reset-targets.mjs';
import { fixtureTargets } from './reset-fixture.mjs';

export const NGINX_IMAGE = 'nginx:1.30.6-alpine@sha256:970364228b1e4f932abc22d321932816782cca8a20b85a77552a7cfe9acfb3b5';
const NODE_IMAGE = 'node:24-alpine@sha256:44fce696f38ba8c987c4db89954051000bbd05ffd8d8659ba15ed4a8602e937f';
const PROXY_IMAGE = 'traefik@sha256:0f6150202bc1fed72a7bd2107460eaa510e5b8a905f3fdb5b84f2c8619c33368';
const hash = data => createHash('sha256').update(data).digest('hex');
const shellPath = path => process.platform === 'win32' ? path.replaceAll('\\', '/').replace(/^([A-Za-z]):/, (_, letter) => `/${letter.toLowerCase()}`) : path;
export const docker = (...args) => execFileSync('docker', args, { encoding: 'utf8', windowsHide: true, maxBuffer: 8 * 1024 * 1024, timeout: 90000, stdio: ['ignore', 'pipe', 'pipe'] }).trim();

export async function createSupervisorFixture({seedApi=false,realStores=false,nativeWatch=false}={}) {
  const root = mkdtempSync(join(tmpdir(), 'rebyte-supervisor-'));
  const project = 'demofixture' + randomUUID().replaceAll('-', '').slice(0, 18);
  const backend = project + '-backend'; const proxyNetwork = project + '-proxy';
  const containers = []; const volumes = []; const networks = [];
  const bash = process.platform === 'win32' ? join(process.env.ProgramFiles ?? 'C:/Program Files', 'Git/bin/bash.exe') : '/usr/bin/bash';
  const dynamic = join(root, 'dynamic'); const tls = join(root, 'tls'); const data = join(root, 'data'); const install = join(root, 'supervisor');
  for (const dir of [dynamic, tls, data, install]) { mkdirSync(dir); chmodSync(dir, 0o700); }
  const result = { root, install, project, backend, containers, volumes, networks, token: '', policy: '', port: '', actorIds: {},
    async cleanup() {
      const maintenance = `rebyte-demo-maintenance-${project}`;
      for (const name of [maintenance, ...containers].reverse()) {
        try { docker('rm', '-fv', name); } catch (error) { if (!String(error.stderr).includes('No such container')) throw error; }
      }
      for (const name of [...volumes].reverse()) docker('volume', 'rm', name);
      for (const name of [...networks].reverse()) docker('network', 'rm', name);
      assert.ok(root.startsWith(join(tmpdir(), 'rebyte-supervisor-')));
      rmSync(root, { recursive: true, force: true });
    },
    command(action, args = [], env = {}) {
      return new Promise((resolveRun, reject) => {
        const child = spawn(bash, [join(install, 'demo-supervisor.sh'), action, ...args], { env: { ...process.env, ...env }, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
        let stdout = '', stderr = '';
        child.stdout.on('data', chunk => { stdout += chunk; }); child.stderr.on('data', chunk => { stderr += chunk; });
        const timer = globalThis.setTimeout(() => child.kill(), 240000);
        child.on('error', reject); child.on('close', code => { clearTimeout(timer); resolveRun({ code, stdout, stderr }); });
      });
    },
    async control(action) { const output = await result.command(action); assert.equal(output.code, 0, `${action}: ${output.stderr}`); return output.stdout.trim(); },
    publicRequest(path = '/users/me', { method = 'GET', token, host = 'api.rebyte.tech' } = {}) {
      // Docker Desktop does not relay Windows bind-mount writes to Linux inotify.
      if (process.platform === 'win32'&&!nativeWatch) docker('exec', project + '-proxy', 'touch', '/dynamic/base.yml');
      return new Promise((resolveRequest, reject) => {
        const request = https.request({ hostname: '127.0.0.1', port: Number(result.port), servername: host, ca: readFileSync(join(tls, 'ca.pem')), path, method, agent: false,
          headers: { Host: host, ...(token ? { Authorization: `Bearer ${token}` } : {}) }, timeout: 5000 }, response => {
            let body = ''; response.on('data', chunk => { body += chunk; }); response.on('end', () => resolveRequest({ status: response.statusCode, body, headers: response.headers }));
          });
        request.on('error', reject); request.on('timeout', () => request.destroy(Error('fixture HTTPS timeout'))); request.end();
      });
    },
    privateRequest(path = '/users/me', token = result.token, {method='GET',body}={}) {
      const source = `const response=await fetch(${JSON.stringify('http://api-gateway:8080' + path)},{method:${JSON.stringify(method)},body:${JSON.stringify(body)},headers:${JSON.stringify({...token ? { Authorization: 'Bearer ' + token } : {},...body?{'Content-Type':'application/json'}:{}})},redirect:'manual'});console.log(JSON.stringify({status:response.status,body:await response.text()}));`;
      return JSON.parse(docker('exec', result.actorIds['demo-runner'], 'node', '--input-type=module', '-e', source));
    },
    rabbitRequest(path,method='GET',body) {
      const source=`const r=await fetch(${JSON.stringify('http://rabbitmq:15672'+path)},{method:${JSON.stringify(method)},headers:{Authorization:'Basic Zml4dHVyZTpmaXh0dXJl','Content-Type':'application/json'},body:${JSON.stringify(body===undefined?undefined:JSON.stringify(body))}});if(!r.ok)throw Error('broker fixture HTTP '+r.status);const text=await r.text();console.log(text||'null');`;
      return JSON.parse(docker('exec',result.actorIds['demo-runner'],'node','--input-type=module','-e',source));
    },
  };
  try {
    const openssl = process.platform === 'win32' ? join(process.env.ProgramFiles ?? 'C:/Program Files', 'Git/usr/bin/openssl.exe') : 'openssl';
    const ssl = (...args) => execFileSync(openssl, args, { cwd: tls, stdio: 'pipe', windowsHide: true });
    ssl('req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', 'ca.key', '-out', 'ca.pem', '-subj', '/CN=RebyteSupervisorFixtureCA', '-days', '1');
    ssl('req', '-newkey', 'rsa:2048', '-nodes', '-keyout', 'server.key', '-out', 'server.csr', '-subj', '/CN=api.rebyte.tech');
    writeFileSync(join(tls, 'extensions'), 'subjectAltName=DNS:api.rebyte.tech,DNS:rebyte.tech,DNS:www.rebyte.tech\nextendedKeyUsage=serverAuth\n');
    ssl('x509', '-req', '-in', 'server.csr', '-CA', 'ca.pem', '-CAkey', 'ca.key', '-CAcreateserial', '-out', 'server.pem', '-days', '1', '-extfile', 'extensions');
    const { publicKey, privateKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
    writeFileSync(join(data, 'public.pem'), publicKey.export({ type: 'spki', format: 'pem' }));
    const unsigned = [Buffer.from(JSON.stringify({ alg: 'RS256', typ: 'JWT' })).toString('base64url'), Buffer.from(JSON.stringify({ iss: 'https://cognito-idp.eu-south-1.amazonaws.com/eu-south-1_tG4BJNs2r', sub: '00000000-0000-4000-8000-000000000001', exp: Math.floor(Date.now()/1000)+1800 })).toString('base64url')].join('.');
    result.token = unsigned + '.' + sign('RSA-SHA256', Buffer.from(unsigned), privateKey).toString('base64url');
    const targets=fixtureTargets();result.tokens={};
    for(const [role,sub] of Object.entries(targets.preservedSubjects)){
      const unsigned=[Buffer.from(JSON.stringify({alg:'RS256',typ:'JWT'})).toString('base64url'),Buffer.from(JSON.stringify({sub,iss:`https://cognito-idp.${targets.aws.region}.amazonaws.com/${targets.aws.poolId}`,client_id:targets.aws.clientId,token_use:'access',exp:Math.floor(Date.now()/1000)+1800,'cognito:groups':role==='admin'?['ADMIN']:[]})).toString('base64url')].join('.');
      result.tokens[role]=unsigned+'.'+sign('RSA-SHA256',Buffer.from(unsigned),privateKey).toString('base64url');
    }
    writeFileSync(join(data, 'server.mjs'), `import http from 'node:http';import{readFileSync,writeFileSync}from'node:fs';import{verify}from'node:crypto';let ticks=0,webhooks=0;const service=process.env.SERVICE;setInterval(()=>writeFileSync('/fixture/'+service+'.json',JSON.stringify({ticks:++ticks,webhooks})),100);http.createServer((q,s)=>{const url=new URL(q.url,'http://api-gateway:8080');if(url.pathname==='/actuator/health'){s.end('{"status":"UP"}');return}if(url.pathname==='/payments/webhook'){webhooks++;s.writeHead(202);s.end();return}if(url.pathname.startsWith('/internal/')){s.writeHead(403);s.end();return}let valid=false;try{const parts=(q.headers.authorization??'').replace(/^Bearer /,'').split('.');const h=JSON.parse(Buffer.from(parts[0],'base64url'));const p=JSON.parse(Buffer.from(parts[1],'base64url'));valid=h.alg==='RS256'&&p.iss==='https://cognito-idp.eu-south-1.amazonaws.com/eu-south-1_tG4BJNs2r'&&p.exp>Date.now()/1000&&verify('RSA-SHA256',Buffer.from(parts.slice(0,2).join('.')),readFileSync('/fixture/public.pem'),Buffer.from(parts[2],'base64url'))}catch{}if(!valid){s.writeHead(401);s.end();return}if(url.pathname==='/redirect'){s.writeHead(307,{Location:'https://other.invalid/'});s.end();return}s.setHeader('Content-Type','application/json');s.end(JSON.stringify({subject:'00000000-0000-4000-8000-000000000001',service,ticks,webhooks}))}).listen(8080,'0.0.0.0');`);
    for (const name of [backend, proxyNetwork]) { docker('network', 'create', name); networks.push(name); }
    if(seedApi){
      cpSync(resolve('scripts/demo/fixtures/seed-api-fixture.mjs'),join(data,'seed-api-fixture.mjs'));
      const serverPath=join(data,'server.mjs');
      const handler=`if(service==='api-gateway'){let body='';q.on('data',chunk=>body+=chunk);q.on('end',()=>{try{const data=seed.handle({method:q.method,path:q.url,claims:JSON.parse(Buffer.from(q.headers.authorization.split('.')[1],'base64url')),body:body?JSON.parse(body):undefined});s.setHeader('Content-Type','application/json');s.end(JSON.stringify(data));}catch{s.writeHead(400);s.end('{}')}});return}`;
      const source=readFileSync(serverPath,'utf8').replace("s.setHeader('Content-Type','application/json');s.end(JSON.stringify({subject:",handler+"s.setHeader('Content-Type','application/json');s.end(JSON.stringify({subject:");
      writeFileSync(serverPath,`import{createSeedApiFixture}from'./seed-api-fixture.mjs';const seed=createSeedApiFixture(${JSON.stringify(targets)},{failureFile:'/fixture/failure.json'});`+source);
    }
    if(realStores){
      const serverPath=join(data,'server.mjs');
      const bootstrap=`if(process.env.SERVICE==='catalog-service'){const deadline=Date.now()+60000;for(;;){try{let r=await fetch('http://meilisearch:7700/indexes/listings',{headers:{Authorization:'Bearer fixture-search-key-at-least-sixteen'}});if(r.ok)break;await fetch('http://meilisearch:7700/indexes',{method:'POST',headers:{Authorization:'Bearer fixture-search-key-at-least-sixteen','Content-Type':'application/json'},body:JSON.stringify({uid:'listings',primaryKey:'id'})});}catch{}if(Date.now()>deadline)throw Error('fixture bootstrap unavailable');await new Promise(r=>setTimeout(r,100));}}`;
      writeFileSync(serverPath,bootstrap+readFileSync(serverPath,'utf8'));
    }
    const storeMounts = { 'mysql-app': ['mysql-data', '/var/lib/mysql'], redis: ['redis-data', '/data'], rabbitmq: ['rabbitmq-data','/var/lib/rabbitmq'], meilisearch: ['meili-data','/meili_data'] };
    for (const service of [...WRITERS, ...PROTECTED]) {
      const name = project + '-' + service; const mounts = [];
      if (storeMounts[service]) { const [suffix, target] = storeMounts[service]; const volume = project + '-' + suffix; docker('volume','create',volume); volumes.push(volume); mounts.push('--mount', `type=volume,src=${volume},dst=${target}`); }
      const broker=service==='rabbitmq';
      const stores=realStores?{
        'mysql-app':{actor:['--env','MYSQL_ROOT_PASSWORD=fixture','--env','MYSQL_ROOT_HOST=%','mysql@sha256:6ea90827b1100f8f2ae306a539f86d2c264a26ed435a2a9f75551dd5c3aeb242'],health:'MYSQL_PWD=fixture mysql --host=mysql-app --user=root --execute="SELECT 1"'},
        redis:{actor:['redis@sha256:4fa24486b8bcca8eec45ee0eb166edc674795e53a2b53d1a9ef263eecebaac85','redis-server','--requirepass','fixture'],health:'REDISCLI_AUTH=fixture redis-cli ping'},
        meilisearch:{actor:['--env','MEILI_MASTER_KEY=fixture-search-key-at-least-sixteen','--env','MEILI_ENV=production','--env','MEILI_NO_ANALYTICS=true','getmeili/meilisearch:v1.53.2'],health:'curl -fsS http://127.0.0.1:7700/health'},
      }:{};
      const actor=stores[service]?.actor??(broker?['--env','RABBITMQ_DEFAULT_USER=fixture','--env','RABBITMQ_DEFAULT_PASS=fixture','rebyte/rabbitmq:4.1-delayed']:[NODE_IMAGE,'node','/fixture/server.mjs']);
      const health=stores[service]?.health??(broker?'gosu rabbitmq rabbitmq-diagnostics -q ping':'node -e "fetch(\'http://127.0.0.1:8080/actuator/health\').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"');
      docker('run','-d','--name',name,'--network',backend,'--network-alias',service,'--memory',stores[service]?'512m':broker?'384m':'64m','--label',`com.docker.compose.project=${project}`,'--label',`com.docker.compose.service=${service}`,'--mount',`type=bind,src=${data},dst=/fixture`,...mounts,'--env','SERVICE='+service,'--health-cmd',health,'--health-interval','2s','--health-timeout','5s','--health-retries','30',...actor);
      containers.push(name); result.actorIds[service] = docker('inspect',name,'--format','{{.Id}}');
      if (service === 'api-gateway') docker('network','connect','--alias','api-gateway',proxyNetwork,name);
    }
    const proxy = project + '-proxy';
    writeFileSync(join(dynamic, 'base.yml'), JSON.stringify({ http: { routers: { api: { rule: 'Host(`api.rebyte.tech`)', entryPoints: ['https'], priority: 10, tls: {}, service: 'api' } }, services: { api: { loadBalancer: { servers: [{ url: 'http://api-gateway:8080' }] } } } }, tls: { certificates: [{ certFile: '/tls/server.pem', keyFile: '/tls/server.key' }] } }));
    docker('run','-d','--name',proxy,'--network',proxyNetwork,'--memory','96m','--mount',`type=bind,src=${dynamic},dst=/dynamic`,'--mount',`type=bind,src=${tls},dst=/tls,readonly`,'-p','127.0.0.1::8443',PROXY_IMAGE,'--entrypoints.https.address=:8443','--providers.file.directory=/dynamic','--providers.file.watch=true','--providers.providersThrottleDuration=100ms','--log.level=ERROR');
    containers.push(proxy); result.port = docker('port',proxy,'8443/tcp').split(':').at(-1);
    const deadline = Date.now()+30000;
    for (;;) {
      try { if ((await result.publicRequest('/users/me',{token:result.token})).status===200) break; } catch {}
      if (Date.now()>deadline) throw Error('Disposable HTTPS ingress did not become ready');
      await setTimeout(200);
    }
    const maintenanceDirectory = join(dynamic,'rebyte-demo');
    mkdirSync(maintenanceDirectory,{mode:0o700});
    cpSync(resolve('scripts/demo/demo-supervisor.sh'),join(install,'demo-supervisor.sh')); chmodSync(join(install,'demo-supervisor.sh'),0o700);
    const dockerExecutable = execFileSync(bash,['-c','command -v docker'],{encoding:'utf8'}).trim();
    let curlExecutable = execFileSync(bash,['-c','command -v curl'],{encoding:'utf8'}).trim();
    if (process.platform === 'win32') {
      const curlWrapper=join(install,'curl-fixture');
      // The ephemeral fixture CA has no revocation service; certificate and hostname checks remain enabled.
      writeFileSync(curlWrapper,`#!/usr/bin/env bash\n'${shellPath(join(install,'docker-fixture'))}' exec '${proxy}' touch /dynamic/base.yml >/dev/null || exit $?\nexec '${curlExecutable}' --ssl-no-revoke "$@"\n`);chmodSync(curlWrapper,0o700);curlExecutable=shellPath(curlWrapper);
    }
    const wrapper = join(install,'docker-fixture');
    const nodeExecutable = shellPath(process.execPath);
    const canonicalRoot=execFileSync(bash,['-c','cd -- "$1"; pwd -P','fixture',shellPath(root)],{encoding:'utf8'}).trim();
    writeFileSync(join(install,'docker-fixture.cjs'), `const{spawnSync}=require('node:child_process');const{appendFileSync}=require('node:fs');let args=process.argv.slice(2);appendFileSync(${JSON.stringify(join(install,'commands.jsonl'))},JSON.stringify(args)+'\\n');for(const prefix of ${JSON.stringify([canonicalRoot,'/tmp/'+basename(root)])})args=args.map(a=>a.replace('src='+prefix,'src='+${JSON.stringify(root.replaceAll('\\','/'))}));const r=spawnSync(${JSON.stringify(process.platform==='win32'?dockerExecutable.replace(/^\/([a-z])\//,(_,d)=>d.toUpperCase()+':/'):dockerExecutable)},args,{stdio:'inherit',windowsHide:true});process.exit(r.status??1);`);
    writeFileSync(wrapper,`#!/usr/bin/env bash\nexport MSYS_NO_PATHCONV=1\nexec '${nodeExecutable}' '${join(install,'docker-fixture.cjs').replaceAll('\\','/')}' "$@"\n`); chmodSync(wrapper,0o700);
    writeFileSync(join(install,'commands.jsonl'),'');
    result.policy = [ ['PROJECT',project],['DOCKER',shellPath(wrapper)],['CURL',curlExecutable],['BACKEND_NETWORK',backend],['BACKEND_NETWORK_ID',docker('network','inspect',backend,'--format','{{.Id}}')],['PROXY_NETWORK',proxyNetwork],['PROXY_NETWORK_ID',docker('network','inspect',proxyNetwork,'--format','{{.Id}}')],['MAINTENANCE_IMAGE',NGINX_IMAGE],['DYNAMIC_DIRECTORY',shellPath(maintenanceDirectory)],['PROXY_HTTPS_PORT',result.port],['CA_FILE',shellPath(join(tls,'ca.pem'))] ].map(([key,value])=>key+'='+value).join('\n')+'\n';
    writeFileSync(join(install,'policy'),result.policy,{mode:0o600});
    writeFileSync(join(install,'approved'),`${'a'.repeat(64)} ${hash(readFileSync(join(install,'demo-supervisor.sh')))} ${hash(result.policy)}\n`,{mode:0o600});
    assert.equal(result.privateRequest('/users/me','').status,401);
    const brokerDeadline=Date.now()+120000;
    for(;;){try{result.rabbitRequest('/api/overview');break;}catch(error){if(Date.now()>=brokerDeadline)throw error;await setTimeout(500);}}
    return result;
  } catch (error) { await result.cleanup(); throw error; }
}
