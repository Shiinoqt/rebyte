import assert from 'node:assert/strict';
import { createHash, randomUUID } from 'node:crypto';
import { open, readFile, unlink } from 'node:fs/promises';
import { hostname } from 'node:os';
import { setTimeout } from 'node:timers/promises';

export const PUBLIC_ORIGIN = 'https://api.rebyte.tech';
export const SEED_ORIGIN = 'http://api-gateway:8080';
export const WRITERS = ['api-gateway', 'user-service', 'catalog-service', 'order-service', 'payment-service', 'messaging-service'];
export const PROTECTED = ['discovery-server', 'mysql-app', 'redis', 'rabbitmq', 'meilisearch', 'demo-runner'];
const resource = 'abrj06cdsga8lngmfbhyz3ek';
const roles = ['buyer', 'seller', 'developer', 'admin'];
const uuid = /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;
const sha256 = value => createHash('sha256').update(value).digest('hex');

export function validateTargets(config, observedIdentities, allowPublicRepair = false) {
  try {
    assert.equal(typeof allowPublicRepair, 'boolean');
    assert.deepEqual(config, {
      environment: 'demo',
      aws: { accountId: '577101744401', region: 'eu-south-1', poolId: 'eu-south-1_tG4BJNs2r', clientId: '1fvpct0cb1ui35rhmp54qesrrf', bucket: 'rebyte-demo-media-577101744401', cdnDistributionId: 'E1WLORXTJ4LN20', iamUsers: { user: 'rebyte-demo-user-service', catalog: 'rebyte-demo-catalog-service', messaging: 'rebyte-demo-messaging-service' }, sesQueueArn: 'arn:aws:sqs:eu-south-1:577101744401:rebyte-demo-ses-events-messaging' },
      atlas: { cluster: 'rebyte-dev', databases: { catalog: 'catalog_demo', messaging: 'messaging_demo' }, users: { catalog: 'catalog-demo', messaging: 'messaging-demo' } },
      stores: { mysqlResource: `${resource}-mysql-data`, mysqlSchemas: ['user_demo', 'order_demo', 'payment_demo'], redisResource: `${resource}-redis-data`, rabbitResource: `${resource}-rabbitmq-data`, searchResource: `${resource}-meili-data` },
      coolify: { serverId: 'mrmr7pu5a7hlwg4avmtlsn4p', backendResourceId: resource, frontendResourceId: 'x8xwz0gzgng5bdlpnvutpvou', network: `${resource}-backend` },
      preservedSubjects: config.preservedSubjects,
    });
    assert.deepEqual(Object.keys(config.preservedSubjects).sort(), [...roles].sort());
    const subjects = Object.values(config.preservedSubjects);
    assert.ok(subjects.every(value => typeof value === 'string' && uuid.test(value)) && new Set(subjects).size === 4);
    assert.deepEqual(config, observedIdentities.targets);
    assert.deepEqual(Object.keys(observedIdentities.accounts).sort(), [...roles].sort());
    for (const role of roles) {
      const account = observedIdentities.accounts[role];
      assert.equal(account.subject, config.preservedSubjects[role]);
      // Public credentials can be repaired; the private admin must already authenticate.
      for (const field of ['enabled', 'emailVerified', 'authenticationVerified']) {
        assert.equal(typeof account[field], 'boolean');
        if (role === 'admin' || !allowPublicRepair) assert.equal(account[field], true);
      }
      assert.ok(Array.isArray(account.groups) && account.groups.every(group => typeof group === 'string'));
      assert.equal(account.groups.includes('ADMIN'), role === 'admin');
    }
    return structuredClone(config);
  } catch { throw new Error('Demo target or preserved-account verification refused'); }
}

export function validateOrigins(publicOrigin, seedOrigin) {
  if (publicOrigin !== PUBLIC_ORIGIN || seedOrigin !== SEED_ORIGIN) throw new Error('Demo origin verification refused');
}

function canonical(value) {
  if (value === null || typeof value === 'string' || typeof value === 'boolean') return value;
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (Array.isArray(value)) {
    if (Object.keys(value).length !== value.length) throw new Error('Invalid review value');
    return value.map(canonical);
  }
  if (typeof value === 'object' && Object.getPrototypeOf(value) === Object.prototype) return Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])]));
  throw new Error('Invalid review value');
}

export function reviewFingerprint(review) { return sha256(JSON.stringify(canonical(review))); }

export function requireApprovedReset(targets, observed, review, approval, hostStatus, allowPublicRepair = false) {
  validateTargets(targets, observed, allowPublicRepair);
  assert.deepEqual(review.targets, targets);
  verifyApproval(review, approval);
  if (hostStatus?.fingerprint !== reviewFingerprint(review) || !['open', 'maintenance'].includes(hostStatus.state)) throw new Error('Installed control grant does not match reviewed reset');
}

export function verifyApproval(review, approval) {
  if (review?.scope?.preserveAdminPassword !== true) throw new Error('Private admin password must be preserved');
  if (approval?.decision !== 'approve' || approval.fingerprint !== reviewFingerprint(review)
      || typeof approval.approvedAt !== 'string' || !Number.isFinite(Date.parse(approval.approvedAt))) throw new Error('Exact reset authorization is required');
}

export function createResetReview(targets, observed, control) {
  validateTargets(targets, observed);
  assert.ok(['Disabled', 'Enabled', 'Suspended'].includes(observed.bucketVersioning));
  for (const key of ['codeSha256', 'policySha256']) assert.match(control[key], /^[a-f0-9]{64}$/);
  assert.match(control.sshKeyFingerprint, /^SHA256:[A-Za-z0-9+/]{43}$/);
  assert.deepEqual(control.writers, WRITERS);
  assert.deepEqual(control.protectedServices, PROTECTED);
  return {
    targets: structuredClone(targets),
    scope: {
      mysqlSchemas: targets.stores.mysqlSchemas,
      mongoDatabases: Object.values(targets.atlas.databases),
      bucket: { name: targets.aws.bucket, versioning: observed.bucketVersioning, includesVersionsAndDeleteMarkers: true },
      broker: { resource: targets.stores.rabbitResource, contents: 'dedicated application queues, retries, DLQs and delayed messages' },
      redis: { resource: targets.stores.redisResource, databases: [0] },
      search: { resource: targets.stores.searchResource, contents: 'dedicated primary and temporary indexes and pending tasks' },
      preserveAdminPassword: true,
      resetPasswordsFor: ['buyer', 'seller', 'developer'],
      deleteOtherPoolUsers: true,
      retainMigrationMetadataAndVolumes: true,
      exclude: ['development resources', 'Coolify platform resources', 'SES identities and configuration', 'all SNS topics and SQS queues'],
    },
    origins: { public: PUBLIC_ORIGIN, seed: SEED_ORIGIN },
    control: structuredClone(control),
  };
}

export function redactedManifest(targets) {
  return { ...structuredClone(targets), preservedSubjects: Object.fromEntries(roles.map(role => [role, `sha256:${sha256(targets.preservedSubjects[role])}`])), privateAdminPassword: 'preserved; never reset', publicAccountAdminMembership: 'forbidden', origins: { public: PUBLIC_ORIGIN, seed: SEED_ORIGIN } };
}

export async function acquireResetLock(path, { timeoutMs = 1000 } = {}) {
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 0 || timeoutMs > 60000) throw new Error('Invalid reset lock timeout');
  const nonce = randomUUID();
  const deadline = Date.now() + timeoutMs;
  let file;
  for (;;) {
    try { file = await open(path, 'wx', 0o600); break; }
    catch (error) {
      if (error.code !== 'EEXIST') throw new Error('Reset lock unavailable');
      if (Date.now() >= deadline) throw new Error('Reset lock held; inspect stale owners manually');
      await setTimeout(Math.min(25, Math.max(1, deadline - Date.now())));
    }
  }
  try { await file.writeFile(JSON.stringify({ nonce, pid: process.pid, host: hostname(), createdAt: new Date().toISOString() })); }
  catch { await file.close(); throw new Error('Reset lock initialization refused; manual recovery required'); }
  await file.close();
  return { nonce, async release() {
    let owner;
    try { owner = JSON.parse(await readFile(path, 'utf8')); }
    catch { throw new Error('Reset lock owner unavailable; manual recovery required'); }
    if (owner.nonce !== nonce || owner.pid !== process.pid || owner.host !== hostname()) throw new Error('Reset lock owner changed; refusing removal');
    await unlink(path);
  } };
}

export async function withResetLock(path, work, options) {
  const lock = await acquireResetLock(path, options);
  const controller = new AbortController();
  const abort = () => controller.abort(new Error('Reset interrupted; maintenance must remain enabled'));
  process.once('SIGINT', abort); process.once('SIGTERM', abort);
  try { const result = await work(controller.signal); controller.signal.throwIfAborted(); return result; }
  finally { process.removeListener('SIGINT', abort); process.removeListener('SIGTERM', abort); await lock.release(); }
}

export async function seedRequest(path, { token, method = 'GET', body, origin = SEED_ORIGIN, fetchImpl = fetch, signal } = {}) {
  validateOrigins(PUBLIC_ORIGIN, origin);
  if (typeof path !== 'string' || !path.startsWith('/') || path.startsWith('//') || /\\|%2f|%5c|(?:^|\/)\.{1,2}(?:\/|$)/i.test(path)) throw new Error('Seed path refused');
  const url = new URL(path, origin);
  if (url.origin !== SEED_ORIGIN || url.pathname.startsWith('/internal/')) throw new Error('Seed path refused');
  if (typeof token !== 'string' || !/^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(token)) throw new Error('Seed authentication required');
  const response = await fetchImpl(url.href, { method, body, headers: { Authorization: `Bearer ${token}`, ...(body ? { 'Content-Type': 'application/json' } : {}) }, redirect: 'manual', signal });
  if (response.status >= 300 && response.status < 400 || response.redirected || response.url && response.url !== url.href) throw new Error('Seed redirect refused');
  if (!response.ok) throw new Error('Seed API request refused');
  return response;
}

export async function runMaintenance({ control, wipe, seed, readiness, signal }) {
  const step = async action => { signal?.throwIfAborted(); const result = await action(); if (result === false) throw new Error('Maintenance stage refused'); return result; };
  await step(() => control('maintenance-on'));
  await step(() => control('stop-writers'));
  await step(wipe);
  await step(() => control('start-writers'));
  await step(seed);
  if (await step(readiness) !== true) throw new Error('Seed readiness must be explicitly verified');
  await step(() => control('maintenance-off'));
}
