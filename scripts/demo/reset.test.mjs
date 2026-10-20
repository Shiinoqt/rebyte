import assert from 'node:assert/strict';
import {test} from 'node:test';
import {mkdtempSync,rmSync} from 'node:fs';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {fixtureTargets,observedTargets} from './fixtures/reset-fixture.mjs';
import {createResetReview,reviewFingerprint,acquireResetLock} from './reset-targets.mjs';
import {resetDemo} from './reset.mjs';
import {ResetAdapters,runTool,MYSQL_TABLES,RABBIT_QUEUES} from './reset-adapters.mjs';
import {createStoreFixture} from './fixtures/reset-stores-fixture.mjs';

function disposableReset() {
  const root=mkdtempSync(join(tmpdir(),'rebyte-reset-'));
  const targets=fixtureTargets();
  const identities=observedTargets();
  const review=createResetReview(targets,{...identities,bucketVersioning:'Disabled'},{codeSha256:'a'.repeat(64),policySha256:'b'.repeat(64),sshKeyFingerprint:'SHA256:'+'A'.repeat(43),writers:['api-gateway','user-service','catalog-service','order-service','payment-service','messaging-service'],protectedServices:['discovery-server','mysql-app','redis','rabbitmq','meilisearch','demo-runner']});
  const approval={decision:'approve',fingerprint:reviewFingerprint(review),approvedAt:new Date().toISOString()};
  const state={maintenance:false,writers:true,identities,stores:Object.fromEntries(['Rabbit','MySql','Mongo','Bucket','Redis','Search'].map(name=>[name,['old sentinel']])),schema:['flyway_schema_history','app_table'],indexes:['unique-pair'],adminPassword:'private unchanged',users:['buyer','seller','developer','admin','visitor'],seed:[],events:[]};
  const adapters={
    async observe(){state.events.push('observe');return structuredClone(state.identities);},
    async control(action){
      state.events.push(action);
      if(action==='status')return {state:state.maintenance?'maintenance':'open',fingerprint:approval.fingerprint};
      if(action==='maintenance-on')state.maintenance=true;
      if(action==='stop-writers'){assert.ok(state.maintenance);state.writers=false;}
      if(action==='start-writers'){assert.ok(state.maintenance);state.writers=true;}
      if(action==='maintenance-off'){assert.ok(state.maintenance&&state.writers);state.maintenance=false;}
    },
    async repairAccounts(){assert.ok(state.maintenance&&!state.writers);state.events.push('repairAccounts');state.users=state.users.filter(role=>role!=='visitor');for(const role of ['buyer','seller','developer'])Object.assign(state.identities.accounts[role],{enabled:true,emailVerified:true,authenticationVerified:true});},
    async bootstrap(){assert.ok(state.maintenance&&state.writers);state.events.push('bootstrap');},
    async seed(){assert.ok(state.maintenance&&state.writers);state.events.push('seed');state.seed.push('unpaid public content');},
    async readiness(){state.events.push('readiness');return state.seed.length===1;},
  };
  for(const name of Object.keys(state.stores))adapters['clear'+name]=async()=>{assert.ok(state.maintenance&&!state.writers);state.events.push('clear'+name);state.stores[name]=[];};
  return {targets,review,approval,lockPath:join(root,'reset.lock'),adapters,state,cleanup(){rmSync(root,{recursive:true,force:true});}};
}

test('reset removes disposable sentinel state and restores readiness before reopening',async()=>{
  const f=disposableReset();try{
    await resetDemo(f);
    assert.deepEqual(Object.values(f.state.stores),[[],[],[],[],[],[]]);
    assert.deepEqual(f.state.users,['buyer','seller','developer','admin']);
    assert.deepEqual(f.state.schema,['flyway_schema_history','app_table']);
    assert.deepEqual(f.state.indexes,['unique-pair']);assert.equal(f.state.adminPassword,'private unchanged');
    assert.equal(f.state.maintenance,false);assert.deepEqual(f.state.seed,['unpaid public content']);
    assert.ok(f.state.events.indexOf('stop-writers')<f.state.events.indexOf('clearRabbit'));
    assert.ok(f.state.events.indexOf('start-writers')<f.state.events.indexOf('seed'));
    assert.ok(f.state.events.indexOf('readiness')<f.state.events.indexOf('maintenance-off'));
  }finally{f.cleanup();}
});

for(const fail of ['maintenance-on','stop-writers','clearRabbit','clearMySql','clearMongo','clearBucket','clearRedis','clearSearch','repairAccounts','start-writers','bootstrap','seed','readiness'])test(`failure at ${fail} stops later stages and never reopens maintenance`,async()=>{
  const f=disposableReset();try{
    if(fail in f.adapters)f.adapters[fail]=async()=>{f.state.events.push(fail);throw Error('secret diagnostic must remain private');};
    else {const control=f.adapters.control;f.adapters.control=async action=>{if(action===fail){f.state.events.push(action);throw Error('secret diagnostic');}return control(action);};}
    await assert.rejects(resetDemo(f),error=>!error.message.includes('secret diagnostic'));
    assert.equal(f.state.events.at(-1),fail);assert.ok(!f.state.events.includes('maintenance-off'));
    if(fail!=='maintenance-on')assert.equal(f.state.maintenance,true);
  }finally{f.cleanup();}
});

test('wrong approval, identities or installed control refuse before any mutation',async()=>{
  for(const kind of ['approval','subject','admin','control']){
    const f=disposableReset();try{
      if(kind==='approval')f.approval.fingerprint='f'.repeat(64);
      if(kind==='subject')f.state.identities.accounts.buyer.subject='00000000-0000-4000-8000-000000000099';
      if(kind==='admin')f.state.identities.accounts.admin.authenticationVerified=false;
      if(kind==='control'){const control=f.adapters.control;f.adapters.control=async action=>action==='status'?{state:'open',fingerprint:'f'.repeat(64)}:control(action);}
      await assert.rejects(resetDemo(f));assert.ok(!f.state.events.includes('maintenance-on'));
      assert.deepEqual(f.state.stores.MySql,['old sentinel']);
    }finally{f.cleanup();}
  }
});

test('approved public password/attribute repair is followed by strict fresh validation',async()=>{
  const f=disposableReset();try{
    Object.assign(f.state.identities.accounts.buyer,{enabled:false,emailVerified:false,authenticationVerified:false});
    await resetDemo(f);assert.equal(f.state.identities.accounts.buyer.authenticationVerified,true);
    assert.equal(f.state.adminPassword,'private unchanged');
  }finally{f.cleanup();}
});

test('failed public repair or changed admin identity cannot reach seed or reopening',async()=>{
  for(const kind of ['public','admin']){
    const f=disposableReset();try{
      f.adapters.repairAccounts=async()=>{f.state.events.push('repairAccounts');f.state.identities.accounts[kind==='public'?'buyer':'admin'].authenticationVerified=false;};
      await assert.rejects(resetDemo(f));assert.ok(!f.state.events.includes('seed'));assert.ok(!f.state.events.includes('maintenance-off'));assert.equal(f.state.maintenance,true);
    }finally{f.cleanup();}
  }
});

test('external cancellation waits for active wipe to unwind before releasing the lock',async()=>{
  const f=disposableReset();const controller=new AbortController();let release,started;
  const gate=new Promise(resolve=>{release=resolve;});const active=new Promise(resolve=>{started=resolve;});
  f.adapters.clearMySql=async()=>{f.state.events.push('clearMySql');started();await gate;};
  try{
    const reset=resetDemo({...f,signal:controller.signal});const rejected=assert.rejects(reset);await active;
    controller.abort();await assert.rejects(acquireResetLock(f.lockPath,{timeoutMs:10}));release();await rejected;
    const lock=await acquireResetLock(f.lockPath);await lock.release();
    assert.ok(!f.state.events.includes('clearMongo'));assert.equal(f.state.maintenance,true);
  }finally{release?.();f.cleanup();}
});

test('native disposable datastore adapters remove sentinels and preserve schemas, indexes and unrelated state',{timeout:240000},async t=>{
  const f=await createStoreFixture();try{
    const adapter=f.adapter();
    await t.test('preflight independently reads scoped stores and identities without mutation',async()=>{
      const run=async(command,args,options)=>{
        if(command!=='aws')return f.run(command,args,options);
        const payload=JSON.parse(options.input);
        if(args[1]==='get-caller-identity')return JSON.stringify({Account:f.targets.aws.accountId});
        if(args[1]==='describe-user-pool')return JSON.stringify({UserPool:{Id:f.targets.aws.poolId,Arn:`arn:aws:cognito-idp:${f.targets.aws.region}:${f.targets.aws.accountId}:userpool/${f.targets.aws.poolId}`}});
        if(args[1]==='describe-user-pool-client')return JSON.stringify({UserPoolClient:{ClientId:f.targets.aws.clientId}});
        if(args[1]==='admin-get-user'){const role=Object.keys(f.targets.preservedSubjects).find(role=>f.targets.preservedSubjects[role]===payload.Username);return JSON.stringify({Username:role,Enabled:true,UserAttributes:[{Name:'sub',Value:payload.Username},{Name:'email_verified',Value:'true'}]});}
        if(args[1]==='admin-list-groups-for-user')return JSON.stringify({Groups:payload.Username==='admin'?[{GroupName:'ADMIN'}]:[]});
        assert.ok(['head-bucket','get-bucket-versioning'].includes(args[1]));return '{}';
      };
      const fetchImpl=async(url,options)=>{
        if(!url.startsWith('https://cognito-idp.'))return f.fetchImpl(url,options);
        const body=JSON.parse(options.body);const role=Object.keys(f.config.accounts).find(role=>f.config.accounts[role].email===body.AuthParameters.USERNAME);
        const claims={sub:f.targets.preservedSubjects[role],iss:`https://cognito-idp.${f.targets.aws.region}.amazonaws.com/${f.targets.aws.poolId}`,client_id:f.targets.aws.clientId,token_use:'access',exp:Date.now()/1000+60,...(role==='admin'?{'cognito:groups':['ADMIN']}:{})};
        return Response.json({AuthenticationResult:{AccessToken:'header.'+Buffer.from(JSON.stringify(claims)).toString('base64url')+'.signature'}});
      };
      const native=new ResetAdapters({targets:f.targets,review:f.review,approval:f.approval,config:f.config},{run,fetchImpl});const before=f.commands.length;
      const observed=await native.observe();assert.deepEqual(observed.targets,f.targets);assert.ok(Object.values(observed.accounts).every(account=>account.authenticationVerified));
      assert.ok(f.commands.slice(before).every(command=>!/(TRUNCATE|deleteMany|FLUSHDB)/.test(command.input??'')));
    });
    await t.test('MySQL app/FK rows clear in one session while Flyway and dev schema survive',async()=>{
      await adapter.clearMySql();
      const metadata=await adapter.mysql("SELECT description FROM user_demo.flyway_schema_history;SELECT COUNT(*) FROM user_db.sentinel;");
      assert.equal(metadata,'keep migration\n1');
      assert.ok(f.commands.some(command=>command.command==='mysql'&&command.input.startsWith('SET FOREIGN_KEY_CHECKS=0;')&&command.input.endsWith('SET FOREIGN_KEY_CHECKS=1;')));
      assert.equal(await adapter.mysql("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema='order_demo' AND table_name='reviews' AND index_name='payload_index';"),'1');
    });
    await t.test('Mongo documents/outbox/inbox/dedup clear and unique indexes survive',async()=>{
      await adapter.clearMongo();
      const result=f.invoke(f.name+'-mongo',['mongosh','--quiet','--eval',"print(JSON.stringify(['catalog_demo','messaging_demo'].map(name=>({count:db.getSiblingDB(name).domain.countDocuments({}),indexes:db.getSiblingDB(name).domain.getIndexes().length}))))"]);
      assert.deepEqual(JSON.parse(result),[{count:0,indexes:2},{count:0,indexes:2}]);
    });
    await t.test('Redis flush is restricted to DB 0',async()=>{
      await adapter.clearRedis();
      assert.equal(await f.run('redis-cli',['-h','redis','-n','1','--raw','GET','sentinel'],{env:{REDISCLI_AUTH:'fixture'}}),'keep');
    });
    await t.test('broker primary and dead-letter data clear before topology is restored',async()=>{
      await adapter.http('rabbit','/api/exchanges/%2F/marketplace.events','PUT',{type:'topic',durable:true,auto_delete:false,arguments:{}});
      for(const name of [RABBIT_QUEUES[0],'notify.follow.dlq']){
        await adapter.http('rabbit','/api/queues/%2F/'+name,'PUT',{durable:true,arguments:{}});
        const published=await adapter.http('rabbit','/api/exchanges/%2F/amq.default/publish','POST',{properties:{},routing_key:name,payload:'old sentinel',payload_encoding:'string'});assert.equal(published.routed,true);
      }
      await adapter.clearRabbit();assert.deepEqual(await adapter.rabbitList('queues'),[]);
      assert.ok(!(await adapter.rabbitList('exchanges')).some(exchange=>exchange.name==='marketplace.events'));
    });
    await t.test('search primary and temporary indexes and pending state are cleared',async()=>{
      for(const uid of ['listings','listings_rebuild_'+'a'.repeat(32)]){
        await adapter.waitTask(await adapter.http('search','/indexes','POST',{uid,primaryKey:'id'}));
        await adapter.waitTask(await adapter.http('search','/indexes/'+uid+'/documents','POST',[{id:'old',title:'old sentinel'}]));
      }
      await adapter.clearSearch();assert.deepEqual(await adapter.indexes(),[]);
    });
    await t.test('foreign tables or broker queues refuse before deletion',async()=>{
      await adapter.mysql('CREATE TABLE user_demo.foreign_table(id INT);INSERT INTO user_demo.foreign_table VALUES(1);');
      await assert.rejects(adapter.clearMySql());assert.equal(await adapter.mysql('SELECT COUNT(*) FROM user_demo.foreign_table;'),'1');
      await adapter.http('rabbit','/api/queues/%2F/unrelated','PUT',{durable:true,arguments:{}});
      await assert.rejects(adapter.clearRabbit());assert.ok((await adapter.rabbitList('queues')).some(queue=>queue.name==='unrelated'));
    });
    for(const versioning of ['Disabled','Enabled','Suspended'])await t.test(`S3 ${versioning} clears all pages including versions and delete markers`,async()=>{
      const review=structuredClone(f.review);review.scope.bucket.versioning=versioning;
      const objects=Array.from({length:2005},(_,i)=>({Key:'old object '+i,...(versioning==='Disabled'?{}:{VersionId:'version-'+i})}));
      const calls=[];const native=new ResetAdapters({targets:f.targets,review,approval:{...f.approval,fingerprint:reviewFingerprint(review)},config:f.config},{run:async(command,args,options)=>{
        assert.equal(command,'aws');const payload=JSON.parse(options.input);const operation=args[1];calls.push({operation,payload});
        assert.equal(payload.Bucket,f.targets.aws.bucket);assert.equal(payload.ExpectedBucketOwner,f.targets.aws.accountId);
        if(operation==='get-bucket-versioning')return JSON.stringify(versioning==='Disabled'?{}:{Status:versioning});
        if(operation.startsWith('list-')){const page=objects.slice(0,1000);return JSON.stringify(versioning==='Disabled'?{Contents:page,IsTruncated:objects.length>1000}:{Versions:page.filter((_,i)=>i%2===0),DeleteMarkers:page.filter((_,i)=>i%2!==0),IsTruncated:objects.length>1000});}
        assert.equal(operation,'delete-objects');assert.ok(payload.Delete.Objects.length<=1000);const deleted=payload.Delete.Objects;objects.splice(0,deleted.length);return JSON.stringify({Deleted:deleted});
      }});
      await native.clearBucket();assert.equal(objects.length,0);assert.equal(calls.filter(call=>call.operation==='delete-objects').length,3);
    });
    await t.test('S3 partial delete errors and versioning drift refuse success',async()=>{
      let deletes=0;const native=new ResetAdapters({targets:f.targets,review:f.review,approval:f.approval,config:f.config},{run:async(command,args)=>{
        if(args[1]==='get-bucket-versioning')return '{}';
        if(args[1]==='list-objects-v2')return JSON.stringify({Contents:[{Key:'keep after refusal'}],IsTruncated:false});
        deletes++;return JSON.stringify({Errors:[{Code:'AccessDenied'}]});
      }});
      await assert.rejects(native.clearBucket());assert.equal(deletes,1);
      native.run=async()=>JSON.stringify({Status:'Enabled'});await assert.rejects(native.clearBucket());
    });
    await t.test('Cognito pagination removes visitors and repairs only public accounts',async()=>{
      const users=Array.from({length:125},(_,i)=>({Username:'user-'+i,Attributes:[{Name:'sub',Value:i<4?Object.values(f.targets.preservedSubjects)[i]:'visitor-'+i}]}));
      const passwords=Object.fromEntries(Object.entries(f.targets.preservedSubjects).map(([role,sub])=>[sub,role==='admin'?'private original':'changed public']));
      const mutations=[];const native=new ResetAdapters({targets:f.targets,review:f.review,approval:f.approval,config:f.config},{run:async(command,args,options)=>{
        assert.equal(command,'aws');assert.ok(!args.some(arg=>arg.includes('changed public')||arg==='fixture'));const payload=JSON.parse(options.input);assert.equal(payload.UserPoolId,f.targets.aws.poolId);const operation=args[1];
        if(operation==='list-users'){const start=Number(payload.PaginationToken??0);return JSON.stringify({Users:users.slice(start,start+60),...(start+60<users.length?{PaginationToken:String(start+60)}:{})});}
        if(operation==='admin-list-groups-for-user')return JSON.stringify({Groups:[{GroupName:'TEST'}]});
        mutations.push({operation,payload});
        if(operation==='admin-delete-user')users.splice(users.findIndex(user=>user.Username===payload.Username),1);
        else if(operation==='admin-set-user-password')passwords[payload.Username]=payload.Password;
        return '{}';
      }});
      await native.repairAccounts();assert.equal(users.length,4);assert.equal(passwords[f.targets.preservedSubjects.admin],'private original');
      assert.ok(mutations.every(call=>call.payload.Username!==f.targets.preservedSubjects.admin));assert.equal(mutations.filter(call=>call.operation==='admin-set-user-password').length,3);
      assert.equal(mutations.filter(call=>call.operation==='admin-delete-user').length,121);
    });
    await t.test('changed packaged supervisor refuses the old control approval',()=>{
      const review=structuredClone(f.review);review.control.codeSha256='f'.repeat(64);
      assert.throws(()=>new ResetAdapters({targets:f.targets,review,approval:{...f.approval,fingerprint:reviewFingerprint(review)},config:f.config}));
    });
  }finally{await f.cleanup();}
});

test('native process failures redact diagnostics and respect cancellation',async()=>{
  await assert.rejects(runTool(process.execPath,['-e',"process.stderr.write('secret fixture credential');process.exit(1)"]),error=>!error.message.includes('secret fixture'));
  const controller=new AbortController();const child=runTool(process.execPath,['-e','setInterval(()=>{},1000)'],{signal:controller.signal});controller.abort();await assert.rejects(child);
});
test('bootstrap deadline also aborts a stalled category HTTP request',{timeout:1000},async t=>{
  const realTimeout=AbortSignal.timeout.bind(AbortSignal);const deadlines=[];
  t.mock.method(AbortSignal,'timeout',ms=>{deadlines.push(ms);return realTimeout(15);});
  const adapter={tokens:{admin:'fixture.access.signature'},fetchImpl:(_url,{signal})=>new Promise((resolve,reject)=>{
    assert.ok(signal instanceof AbortSignal);signal.addEventListener('abort',()=>reject(signal.reason),{once:true});
  })};
  await assert.rejects(ResetAdapters.prototype.bootstrap.call(adapter));assert.deepEqual(deadlines,[60000]);
});
