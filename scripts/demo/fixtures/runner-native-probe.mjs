import assert from 'node:assert/strict';
import {readdirSync} from 'node:fs';
import {loadRunnerConfig} from '/app/scripts/demo/runner-config.mjs';
import {resetDemo} from '/app/scripts/demo/reset.mjs';
import {runTool,MYSQL_TABLES} from '/app/scripts/demo/reset-adapters.mjs';
import {createResetReview} from '/app/scripts/demo/reset-targets.mjs';
import {observedTargets} from './reset-fixture.mjs';

const {input,adapters}=loadRunnerConfig();
const nativeFetch=globalThis.fetch;
let publicChecks=0;
const publicClosed=async()=>{
  for(const path of ['/users/me','/payments/webhook']){
    const response=await nativeFetch('https://api.rebyte.tech:8443'+path,{method:path.includes('webhook')?'POST':'GET',redirect:'manual',signal:AbortSignal.timeout(10000)});
    assert.equal(response.status,503);assert.equal(response.headers.get('X-Rebyte-Maintenance'),'rebyte-demo');await response.arrayBuffer();
  }publicChecks++;
};
// Endpoint mapping is confined to this mounted disposable harness, never runtime configuration.
adapters.run=async(command,args,options={})=>{
  if(command==='aws')args=[...args,'--endpoint-url','http://aws-fixture:8089'];
  if(command==='ssh')args=args.map(value=>value==='rebyte-reset@169.58.65.61'?'rebyte-reset@supervisor-fixture':value);
  if(command==='mongosh'){
    const uri=new URL(options.env.REBYTE_RESET_MONGO_URI);
    options={...options,env:{...options.env,REBYTE_RESET_MONGO_URI:`mongodb://${uri.username}:${uri.password}@mongo-demo:27017${uri.pathname}?authSource=${uri.pathname.slice(1)}`}};
  }
try{return await runTool(command,args,options);}catch{console.error('Native fixture tool failed: '+command+(command==='aws'?' '+args[1]:''));throw Error('Native fixture '+command+' failed');}
};
adapters.fetchImpl=async(url,options)=>{
  let target=url;
  if(url===`https://cognito-idp.${input.targets.aws.region}.amazonaws.com/`)target='http://aws-fixture:8089/';
  else if(url===`https://${input.targets.aws.bucket}.s3.${input.targets.aws.region}.amazonaws.com/`)target='http://aws-fixture:8089/fixture/upload';
  if(url.startsWith('http://api-gateway:8080/listings/hardware')&&options.method==='POST'||url.startsWith('http://api-gateway:8080/search?'))await publicClosed();
  const response=await nativeFetch(target,options);
  if(target===url)return response;
  return new Response(response.status===204?null:await response.arrayBuffer(),{status:response.status,headers:response.headers});
};
const mysql=sql=>adapters.mysql(sql);
const mongo=async source=>{try{return await runTool('mongosh',['--nodb','--quiet','--file','/dev/stdin'],{input:source});}catch{throw Error('Native fixture mongosh setup/inspection failed');}};
const cloud=async()=>{const response=await nativeFetch('http://aws-fixture:8089/fixture/state');assert.equal(response.status,200);return response.json();};

try{
  const mode=process.argv[2];
const observe=adapters.observe.bind(adapters);
adapters.observe=async(...args)=>{try{return await observe(...args);}catch(error){console.error('Observe failure location: '+(error.stack?.split('\n').find(line=>line.includes('reset-adapters.mjs'))??'native tool'));throw error;}};
if(mode==='private-input'){
const pending=()=>readdirSync('/tmp').filter(name=>name.startsWith('rebyte-reset-input-')).sort();
const before=pending();
const source="const fs=require('node:fs');for(const name of fs.readdirSync('/tmp').filter(name=>name.startsWith('rebyte-reset-input-'))){const dir='/tmp/'+name;if((fs.statSync(dir).mode&511)!==448||(fs.statSync(dir+'/input.js').mode&511)!==384)throw Error('private input permissions');}print('private input verified');";
assert.equal(await runTool('mongosh',['--nodb','--quiet','--file','/dev/stdin'],{input:source}),'private input verified');
for(const options of [{input:"throw Error('private diagnostic');"},{input:'sleep(5000);',timeout:50},{input:'sleep(5000);',signal:AbortSignal.timeout(50)}]){
await assert.rejects(runTool('mongosh',['--nodb','--quiet','--file','/dev/stdin'],options),error=>error.message==='Private reset tool failed');
assert.deepEqual(pending(),before);
}
assert.deepEqual(pending(),before);console.log('PASS native private input permissions and cleanup after success, failure, timeout and cancellation');
}else if(mode==='initialize'){
    let sql='CREATE DATABASE user_db;CREATE TABLE user_db.sentinel(id INT);INSERT INTO user_db.sentinel VALUES(1);';
    for(const [schema,tables] of Object.entries(MYSQL_TABLES)){
      sql+=`CREATE DATABASE ${schema};CREATE TABLE ${schema}.flyway_schema_history(id INT PRIMARY KEY);INSERT INTO ${schema}.flyway_schema_history VALUES(1);`;
      for(const table of tables)sql+=`CREATE TABLE ${schema}.\`${table}\`(id INT PRIMARY KEY,payload VARCHAR(32),INDEX payload_index(payload));INSERT INTO ${schema}.\`${table}\` VALUES(1,'old');`;
    }
    sql+='ALTER TABLE order_demo.reviews ADD CONSTRAINT reviews_order FOREIGN KEY(id) REFERENCES order_demo.orders(id);';await mysql(sql);
    console.log('STEP native MySQL initialized');
    await mongo(`const c=new Mongo('mongodb://root:fixture@mongo-demo:27017/admin');for(const [name,user]of [['catalog_demo','catalog-demo'],['messaging_demo','messaging-demo']]){const d=c.getDB(name);d.createUser({user,pwd:'fixture',roles:[{role:'readWrite',db:name}]});for(const name of ['domain','outbox','inbox','dedup']){d[name].insertOne({sentinel:1});d[name].createIndex({sentinel:1},{unique:true,name:'sentinel_unique'});}}c.getDB('unrelated_demo').sentinel.insertOne({keep:1});`);
    console.log('STEP native MongoDB initialized');
    for(const db of ['0','1'])assert.equal(await adapters.run('redis-cli',['-h','redis','-n',db,'SET','sentinel',db==='0'?'old':'keep'],{env:{REDISCLI_AUTH:'fixture'}}),'OK');
    await adapters.http('rabbit','/api/exchanges/%2F/marketplace.events','PUT',{type:'x-delayed-message',durable:true,arguments:{'x-delayed-type':'topic'}});
    await adapters.http('rabbit','/api/queues/%2F/notify.follow','PUT',{durable:true,arguments:{}});
    await adapters.http('rabbit','/api/bindings/%2F/e/marketplace.events/q/notify.follow','POST',{routing_key:'old',arguments:{}});
    await adapters.http('rabbit','/api/exchanges/%2F/marketplace.events/publish','POST',{properties:{headers:{'x-delay':600000}},routing_key:'old',payload:'old delayed sentinel',payload_encoding:'string'});
    await adapters.waitTask(await adapters.http('search','/indexes','POST',{uid:'listings_rebuild_'+'a'.repeat(32),primaryKey:'id'}));
    await adapters.waitTask(await adapters.http('search','/indexes/listings/documents','POST',[{id:'old',title:'old sentinel'}]));
    console.log('PASS native store initialization');
}else if(mode==='observe'){
await adapters.observe();console.log('PASS native target observation');
}else if(mode==='reset'){
    await resetDemo({...input,adapters,lockPath:'/run/demo-reset/reset.lock'});assert.ok(publicChecks>=2);
    console.log('PASS native reset, authenticated private seed and closed visitor/webhook probes');
  }else if(mode==='inspect'){
    for(const [schema,tables] of Object.entries(MYSQL_TABLES)){
      assert.equal(await mysql(`SELECT COUNT(*) FROM ${schema}.flyway_schema_history;`),'1');
      for(const table of tables)assert.equal(await mysql(`SELECT COUNT(*) FROM ${schema}.\`${table}\`;`),'0');
    }
    assert.equal(await mysql('SELECT COUNT(*) FROM user_db.sentinel;'),'1');
    await mongo(`const c=new Mongo('mongodb://root:fixture@mongo-demo:27017/admin');for(const name of ['catalog_demo','messaging_demo'])for(const info of c.getDB(name).getCollectionInfos()){const d=c.getDB(name).getCollection(info.name);if(d.countDocuments({})!==0||!d.getIndexes().some(index=>index.name==='sentinel_unique'&&index.unique))throw Error('Mongo contents/index mismatch');}if(c.getDB('unrelated_demo').sentinel.countDocuments({})!==1)throw Error('foreign database changed');`);
    assert.equal(await adapters.run('redis-cli',['-h','redis','-n','0','DBSIZE'],{env:{REDISCLI_AUTH:'fixture'}}),'0');
    assert.equal(await adapters.run('redis-cli',['-h','redis','-n','1','GET','sentinel'],{env:{REDISCLI_AUTH:'fixture'}}),'keep');
    assert.equal((await adapters.rabbitList('queues')).length,0);
    assert.deepEqual((await adapters.indexes()).map(index=>index.uid),['listings']);
    const state=await cloud();assert.equal(state.objects.length,0);assert.equal(state.users.length,4);assert.ok(state.signed>=150);
    assert.equal(state.users.find(user=>user.role==='admin').password,input.config.accounts.admin.password);
    for(const user of state.users){assert.equal(user.Enabled,true);assert.ok(user.Attributes.some(attribute=>attribute.Name==='email_verified'&&attribute.Value==='true'));}
    assert.equal((await adapters.control('status')).state,'open');
    const response=await nativeFetch('http://api-gateway:8080/users/me');assert.equal(response.status,401);
    console.log('PASS native contents, preserved schema/indexes/identities/admin, pagination and private auth');
  }else if(mode==='versioned'){
    for(const versioning of ['Enabled','Suspended']){
      const response=await nativeFetch('http://aws-fixture:8089/fixture/control',{method:'POST',body:JSON.stringify({versioning})});assert.equal(response.status,200);
      adapters.review=createResetReview(input.targets,{...observedTargets(),bucketVersioning:versioning},input.review.control);
      await adapters.clearBucket();assert.equal((await cloud()).objects.length,0);
    }
    await nativeFetch('http://aws-fixture:8089/fixture/control',{method:'POST',body:JSON.stringify({versioning:'Disabled'})});
    console.log('PASS native S3 versions and delete markers across all pages');
  }else if(mode==='foreign-table')await mysql('CREATE TABLE user_demo.foreign_table(id INT);');
  else if(mode==='remove-foreign-table')await mysql('DROP TABLE user_demo.foreign_table;');
  else if(mode==='wrong-cloud-auth'){adapters.config.aws.secretAccessKey='wrong-fixture-secret';await adapters.observe();throw Error('wrong credentials unexpectedly succeeded');}
  else if(mode==='arbitrary-ssh'){
    await assert.rejects(adapters.run('ssh',['-T','-i',input.config.ssh.keyFile,'-o','BatchMode=yes','-o','StrictHostKeyChecking=yes','-o','UserKnownHostsFile='+input.config.ssh.knownHostsFile,'rebyte-reset@169.58.65.61','id']));console.log('PASS native arbitrary SSH command refused');
  }else throw Error('Unknown probe mode');
}catch(error){console.error('Native runner probe failed: '+error.message);process.exitCode=1;}
