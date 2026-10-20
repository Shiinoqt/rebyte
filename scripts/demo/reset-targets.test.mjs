import assert from 'node:assert/strict';
import { test } from 'node:test';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { fixtureTargets, observedTargets } from './fixtures/reset-fixture.mjs';
import { validateTargets, validateOrigins, reviewFingerprint, verifyApproval, acquireResetLock, withResetLock, seedRequest, createResetReview, requireApprovedReset, WRITERS, PROTECTED } from './reset-targets.mjs';

test('exact targets and independently observed four accounts pass', () => {
  assert.equal(validateTargets(fixtureTargets(), observedTargets()).environment, 'demo');
});

for (const [name, mutate] of Object.entries({
  'changed AWS account': c => { c.aws.accountId = '111122223333'; },
  'dev pool': c => { c.aws.poolId = 'eu-south-1_WPMqs7BW8'; },
  'dev bucket': c => { c.aws.bucket = 'rebyte-media-577101744401'; },
  'dev MySQL schema': c => { c.stores.mysqlSchemas[0] = 'user_db'; },
  'dev Atlas database': c => { c.atlas.databases.catalog = 'catalog_db'; },
  'shared Atlas user': c => { c.atlas.users.catalog = c.atlas.users.messaging; },
  'missing preserved subject': c => { delete c.preservedSubjects.admin; },
  'subject alias': c => { c.preservedSubjects.admin = 'my-admin'; },
  'duplicate subject': c => { c.preservedSubjects.buyer = c.preservedSubjects.admin; },
  'missing resource': c => { c.stores.mysqlResource = ''; },
  'foreign Redis': c => { c.stores.redisResource = 'coolify-redis'; },
  'wrong backend': c => { c.coolify.backendResourceId = 'unrelated'; },
  'command injection': c => { c.coolify.network += ';id'; },
  'placeholder': c => { c.aws.clientId = 'REPLACE'; },
  'unknown extra key': c => { c.development = true; },
})) test(`refuses ${name} before destructive callback`, () => {
  const config = fixtureTargets(); mutate(config);
  let writes = 0;
  assert.throws(() => { validateTargets(config, observedTargets()); writes++; });
  assert.equal(writes, 0);
});

for (const role of ['buyer', 'seller', 'developer']) test(`refuses public ${role} ADMIN`, () => {
  const observed = observedTargets(); observed.accounts[role].groups = ['ADMIN'];
  assert.throws(() => validateTargets(fixtureTargets(), observed));
});
test('private admin must remain enabled, verified and authenticated', () => {
  for (const key of ['enabled', 'emailVerified', 'authenticationVerified']) {
    const observed = observedTargets(); observed.accounts.admin[key] = false;
    assert.throws(() => validateTargets(fixtureTargets(), observed));
  }
});
test('all observations must match, including each opaque preserved subject', () => {
  const observed = observedTargets(); observed.accounts.admin.subject = '00000000-0000-4000-8000-000000000099';
  assert.throws(() => validateTargets(fixtureTargets(), observed));
});
test('fingerprint is canonical, complete, and has no implicit approval', () => {
  const review = { targets: fixtureTargets(), control: { codeSha256: 'a'.repeat(64), policySha256: 'b'.repeat(64) }, scope: { preserveAdminPassword: true } };
  const fp = reviewFingerprint(review);
  assert.match(fp, /^[a-f0-9]{64}$/);
  assert.equal(fp, reviewFingerprint({ scope: review.scope, control: review.control, targets: review.targets }));
  assert.throws(() => verifyApproval(review, undefined));
  assert.doesNotThrow(() => verifyApproval(review, { decision: 'approve', fingerprint: fp, approvedAt: new Date().toISOString() }));
  assert.throws(() => verifyApproval({ ...review, scope: { preserveAdminPassword: false } }, { decision: 'approve', fingerprint: fp, approvedAt: new Date().toISOString() }));
  assert.throws(() => reviewFingerprint({ secret: undefined }));
});
test('origins are independently immutable', () => {
  assert.doesNotThrow(() => validateOrigins('https://api.rebyte.tech', 'http://api-gateway:8080'));
  for (const origin of ['https://api.rebyte.tech.evil.test', 'https://api.rebyte.tech:444', 'https://user@api.rebyte.tech', 'http://api.rebyte.tech', 'https://api.rebyte.tech/']) assert.throws(() => validateOrigins(origin, 'http://api-gateway:8080'));
  for (const origin of ['http://localhost:8080', 'http://api-gateway:8081', 'http://api-gateway', 'https://api-gateway:8080', 'http://api-gateway:8080/']) assert.throws(() => validateOrigins('https://api.rebyte.tech', origin));
});
test('seed requests use bearer auth and refuse redirects without another request', async () => {
  let calls = 0;
  await assert.rejects(seedRequest('/users/me', { token: 'header.payload.signature', fetchImpl: async (url, options) => {
    calls++; assert.equal(url, 'http://api-gateway:8080/users/me');
    assert.equal(options.redirect, 'manual'); assert.equal(options.headers.Authorization, 'Bearer header.payload.signature');
    return new Response('', { status: 307, headers: { Location: 'https://evil.test/' } });
  } }));
  assert.equal(calls, 1);
  for (const path of ['//evil.test/', '/internal/seed', '/\\evil.test/', '/users/../internal/seed']) await assert.rejects(seedRequest(path, { token: 'header.payload.signature', fetchImpl: () => { throw Error('must not call'); } }));
  await assert.rejects(seedRequest('/users/me', { token: '' }));
});
test('exclusive lock refuses overlap, stale files and wrong-owner release', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'rebyte-lock-')); const path = join(dir, 'reset.lock');
  try {
    const lock = await acquireResetLock(path, { timeoutMs: 20 });
    await assert.rejects(acquireResetLock(path, { timeoutMs: 20 }));
    assert.ok(readFileSync(path, 'utf8').includes(lock.nonce));
    writeFileSync(path, JSON.stringify({ nonce: 'another-owner' }));
    await assert.rejects(lock.release());
    assert.ok(readFileSync(path, 'utf8').includes('another-owner'));
    await assert.rejects(acquireResetLock(path, { timeoutMs: 20 }));
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
test('lock covers all stages and is released after an injected failure unwinds', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'rebyte-lock-')); const path = join(dir, 'reset.lock');
  try {
    await assert.rejects(withResetLock(path, async () => {
      await assert.rejects(acquireResetLock(path, { timeoutMs: 10 }));
      throw Error('seed failed');
    }));
    const next = await acquireResetLock(path); await next.release();
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test('review scope preserves admin password, volumes and all external event resources', () => {
  const observed = observedTargets(); observed.bucketVersioning = 'Disabled';
  const control = { codeSha256:'a'.repeat(64),policySha256:'b'.repeat(64),sshKeyFingerprint:'SHA256:'+'a'.repeat(43),writers:WRITERS,protectedServices:PROTECTED };
  const review = createResetReview(fixtureTargets(), observed, control);
  assert.deepEqual(review.scope.resetPasswordsFor,['buyer','seller','developer']);
  assert.equal(review.scope.preserveAdminPassword,true);
  assert.equal(review.scope.retainMigrationMetadataAndVolumes,true);
  assert.ok(review.scope.exclude.includes('all SNS topics and SQS queues'));
  const fingerprint=reviewFingerprint(review);
  const approval={decision:'approve',fingerprint,approvedAt:new Date().toISOString()};
  assert.doesNotThrow(()=>requireApprovedReset(fixtureTargets(),observed,review,approval,{state:'open',fingerprint}));
  assert.throws(()=>requireApprovedReset(fixtureTargets(),observed,review,approval,{state:'open',fingerprint:'f'.repeat(64)}));
  assert.throws(()=>verifyApproval({...review,scope:{...review.scope,preserveAdminPassword:false}},approval));
  const changed=structuredClone(review);changed.control.codeSha256='c'.repeat(64);
  assert.throws(()=>verifyApproval(changed,approval));
});

for (const signal of ['SIGINT','SIGTERM']) test(`${signal} aborts work before the owner releases a cross-process lock`, async () => {
  const dir=mkdtempSync(join(tmpdir(),'rebyte-signal-'));const path=join(dir,'reset.lock');
  const moduleUrl=new URL('./reset-targets.mjs',import.meta.url).href;
  const source=`import{withResetLock}from${JSON.stringify(moduleUrl)};import{setTimeout}from'node:timers/promises';try{await withResetLock(${JSON.stringify(path)},async signal=>{process.once('message',()=>process.emit(${JSON.stringify(signal)}));process.send('locked');await setTimeout(60000,null,{signal});});}catch{process.exitCode=1;}finally{process.disconnect();}`;
  const child=spawn(process.execPath,['--input-type=module','-e',source],{stdio:['ignore','ignore','pipe','ipc'],windowsHide:true});
  try {
    await once(child,'message');
    await assert.rejects(acquireResetLock(path,{timeoutMs:10}));
    const exited=once(child,'exit');child.send('interrupt');
    assert.equal((await exited)[0],1);
    const lock=await acquireResetLock(path);await lock.release();
  } finally {child.kill();rmSync(dir,{recursive:true,force:true});}
});
