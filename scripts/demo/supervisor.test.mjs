import assert from 'node:assert/strict';
import { test } from 'node:test';
import { createHash } from 'node:crypto';
import { readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { setTimeout } from 'node:timers/promises';
import { withResetLock, runMaintenance, WRITERS, PROTECTED } from './reset-targets.mjs';
import { createSupervisorFixture, docker } from './fixtures/supervisor-fixture.mjs';

test('real disposable ingress, constrained host actions and private authenticated seed', { timeout: 600000 }, async t => {
  const fixture = await createSupervisorFixture();
  try {
    const commands = () => readFileSync(join(fixture.install,'commands.jsonl'),'utf8').trim().split('\n').filter(Boolean).map(JSON.parse);
    const mutations = () => commands().filter(args => ['stop','start','run','rm','kill','restart'].includes(args[0]));
    const running = id => docker('inspect', id, '--format', '{{.State.Running}}');
    const sentinel = 'protected demo fixture data';
    const unrelated = fixture.project+'-unrelated';
    const actorImage = docker('inspect',fixture.actorIds['user-service'],'--format','{{.Config.Image}}');
    docker('run','-d','--name',unrelated,'--memory','32m',actorImage,'node','-e','setInterval(()=>{},1000)');
    fixture.containers.push(unrelated);
    const unrelatedStart = docker('inspect',unrelated,'--format','{{.State.StartedAt}}');
    for (const [service, path] of Object.entries({'mysql-app':'/var/lib/mysql',redis:'/data',rabbitmq:'/var/lib/rabbitmq',meilisearch:'/meili_data'})) {
      docker('exec',fixture.actorIds[service],'sh','-c','printf %s "$1" > "$2"','fixture',sentinel,path+'/supervisor-sentinel');
    }
    let baselineSucceeded = false;
    await t.test('arbitrary command/arguments and unarmed control cause zero Docker writes', async () => {
      const initial = commands().length;
      for (const action of ['stop-writers;id','stop-writers unrelated','rm','$(id)','stop-writers\nstart-writers']) assert.notEqual((await fixture.command(action)).code,0);
      assert.notEqual((await fixture.command('status',['unrelated'])).code,0);
      assert.equal(commands().length,initial);
      const approved = readFileSync(join(fixture.install,'approved')); rmSync(join(fixture.install,'approved'));
      assert.equal((await fixture.command('status')).stdout.trim(),'unarmed');
      assert.notEqual((await fixture.command('maintenance-on')).code,0);
      assert.equal(commands().length,initial); writeFileSync(join(fixture.install,'approved'),approved);
    });
    await t.test('missing maintenance state refuses stop without stopping a container', async () => {
      assert.notEqual((await fixture.command('stop-writers')).code,0);
      assert.equal(commands().filter(args=>['stop','start','run'].includes(args[0])).length,0);
    });
    await t.test('public visitor and provider webhook remain closed across restart and private seed', async () => {
      fixture.rabbitRequest('/api/exchanges/%2F/marketplace.events','PUT',{type:'x-delayed-message',durable:true,arguments:{'x-delayed-type':'topic'}});
      fixture.rabbitRequest('/api/queues/%2F/notify.follow','PUT',{durable:true,arguments:{}});
      fixture.rabbitRequest('/api/bindings/%2F/e/marketplace.events/q/notify.follow','POST',{routing_key:'old',arguments:{}});
      fixture.rabbitRequest('/api/exchanges/%2F/marketplace.events/publish','POST',{properties:{headers:{'x-delay':300000}},routing_key:'old',payload:'old delayed sentinel',payload_encoding:'string'});
      assert.equal(docker('exec',fixture.actorIds.rabbitmq,'rabbitmqctl','eval','mnesia:table_info(rabbit_delayed_message:table_name(),size).'),'1');
      await withResetLock(join(fixture.root,'reset.lock'), async () => runMaintenance({
        control: action => fixture.control(action),
        wipe: async () => {
          for(const table of ['table_name','index_table_name'])assert.equal(docker('exec',fixture.actorIds.rabbitmq,'rabbitmqctl','eval',`mnesia:table_info(rabbit_delayed_message:${table}(),size).`),'0');
          for (const service of WRITERS) assert.equal(docker('inspect',fixture.actorIds[service],'--format','{{.State.Running}}'),'false');
          for (const service of PROTECTED) assert.equal(docker('inspect',fixture.actorIds[service],'--format','{{.State.Running}}'),'true');
          assert.equal(running(unrelated),'true');
          const counters = () => WRITERS.map(service => readFileSync(join(fixture.root,'data',service+'.json'),'utf8'));
          const before = counters(); await setTimeout(350); assert.deepEqual(counters(),before);
          assert.equal((await fixture.publicRequest('/users/me')).status,503);
          assert.equal((await fixture.publicRequest('/payments/webhook',{method:'POST'})).status,503);
          for (const host of ['rebyte.tech','www.rebyte.tech']) assert.equal((await fixture.publicRequest('/',{host})).status,503);
        },
        seed: async () => {
          assert.equal((await fixture.publicRequest('/users/me',{token:fixture.token})).status,503);
          assert.equal((await fixture.publicRequest('/payments/webhook',{method:'POST'})).status,503);
          assert.equal(fixture.privateRequest().status,200);
          assert.equal(fixture.privateRequest('/users/me','').status,401);
          assert.equal(fixture.privateRequest('/users/me',fixture.token+'x').status,401);
          assert.equal(fixture.privateRequest('/internal/seed').status,403);
          assert.equal(fixture.privateRequest('/redirect').status,307);
          assert.equal(JSON.parse(fixture.privateRequest().body).webhooks,0);
        },
        readiness: async () => true,
      }));
      const deadline=Date.now()+10000;
      while ((await fixture.publicRequest('/users/me',{token:fixture.token})).status!==200) { assert.ok(Date.now()<deadline); await setTimeout(100); }
      assert.equal((await fixture.publicRequest('/users/me')).status,401);
      assert.equal(docker('inspect',unrelated,'--format','{{.State.StartedAt}}'),unrelatedStart);
      baselineSucceeded = true;
    });
    assert.ok(baselineSucceeded,'known-good maintenance baseline is required before refusal tests');
    await t.test('wrong backend network refuses before mutation', async () => {
      const policyPath = join(fixture.install,'policy');
      const approvalPath = join(fixture.install,'approved');
      const approved = readFileSync(approvalPath,'utf8');
      const policy = fixture.policy.replace(/^BACKEND_NETWORK_ID=.*$/m,'BACKEND_NETWORK_ID='+'f'.repeat(64));
      const grant = approved.trim().split(' ');
      grant[2] = createHash('sha256').update(policy).digest('hex');
      const before = mutations().length;
      try {
        writeFileSync(policyPath,policy); writeFileSync(approvalPath,grant.join(' ')+'\n');
        assert.notEqual((await fixture.command('maintenance-on')).code,0);
        assert.equal(mutations().length,before);
      } finally { writeFileSync(policyPath,fixture.policy); writeFileSync(approvalPath,approved); }
    });
    for (const service of ['user-service','unknown-writer']) await t.test(`${service} duplicate or unknown writer refuses before mutation`, async () => {
      const name = fixture.project+'-extra-'+service;
      const image = docker('inspect',fixture.actorIds['user-service'],'--format','{{.Config.Image}}');
      docker('run','-d','--name',name,'--network',fixture.backend,'--memory','32m','--label',`com.docker.compose.project=${fixture.project}`,'--label',`com.docker.compose.service=${service}`,image,'node','-e','setInterval(()=>{},1000)');
      fixture.containers.push(name);
      const before = mutations().length;
      try {
        assert.notEqual((await fixture.command('maintenance-on')).code,0);
        assert.equal(mutations().length,before);
      } finally { docker('rm','-f',name); }
    });
    for (const fail of ['seed','readiness']) await t.test(`${fail} failure leaves public ingress in maintenance`, async () => {
      await assert.rejects(withResetLock(join(fixture.root,'reset.lock'), async () => runMaintenance({ control: action => fixture.control(action), wipe: async()=>{}, seed: async()=>{ if(fail==='seed')throw Error('fixture seed failure'); }, readiness: async()=>fail!=='readiness' })));
      assert.equal((await fixture.publicRequest('/users/me',{token:fixture.token})).status,503);
      assert.equal((await fixture.publicRequest('/payments/webhook',{method:'POST'})).status,503);
      assert.equal(fixture.privateRequest().status,200);
    });
    await t.test('replaced captured writer refuses and protected data remains intact', async () => {
      const service = 'user-service';
      const original = fixture.actorIds[service];
      const name = fixture.project+'-replacement';
      const image = docker('inspect',original,'--format','{{.Config.Image}}');
      docker('rm','-f',original);
      docker('run','-d','--name',name,'--network',fixture.backend,'--memory','32m','--label',`com.docker.compose.project=${fixture.project}`,'--label',`com.docker.compose.service=${service}`,image,'node','-e','setInterval(()=>{},1000)');
      fixture.containers.push(name);
      const before = mutations().length;
      assert.notEqual((await fixture.command('stop-writers')).code,0);
      assert.equal(mutations().length,before);
      assert.equal(running(name),'true');
      for (const [store,path] of Object.entries({'mysql-app':'/var/lib/mysql',redis:'/data',rabbitmq:'/var/lib/rabbitmq',meilisearch:'/meili_data'})) {
        assert.equal(running(fixture.actorIds[store]),'true');
        assert.equal(docker('exec',fixture.actorIds[store],'cat',path+'/supervisor-sentinel'),sentinel);
      }
    });
    await t.test('overlapping action and changed policy refuse without Docker access', async () => {
      const {mkdirSync}=await import('node:fs');const lock=join(fixture.install,'state/action.lock');mkdirSync(lock);
      const before=commands().length;assert.notEqual((await fixture.command('status')).code,0);assert.equal(commands().length,before);rmSync(lock,{recursive:true});
      const scriptPath=join(fixture.install,'demo-supervisor.sh'); const originalScript=readFileSync(scriptPath);
      try {
        writeFileSync(scriptPath,Buffer.concat([originalScript,Buffer.from('\n# changed fixture code\n')]));
        assert.notEqual((await fixture.command('status')).code,0);assert.equal(commands().length,before);
      } finally { writeFileSync(scriptPath,originalScript); }
      writeFileSync(join(fixture.install,'policy'),fixture.policy+'UNKNOWN=unsafe\n');
      assert.notEqual((await fixture.command('start-writers')).code,0);assert.equal(commands().length,before);
    });
  } finally { await fixture.cleanup(); }
});
