import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {randomUUID,createHash} from 'node:crypto';
import {readFileSync} from 'node:fs';
import {setTimeout as delay} from 'node:timers/promises';
import {ResetAdapters,MYSQL_TABLES} from '../reset-adapters.mjs';
import {createResetReview,reviewFingerprint,WRITERS,PROTECTED} from '../reset-targets.mjs';
import {fixtureTargets,observedTargets} from './reset-fixture.mjs';

export const fixtureDocker=(...args)=>execFileSync('docker',args,{encoding:'utf8',windowsHide:true,timeout:90000,maxBuffer:8*1024*1024,stdio:['ignore','pipe','pipe']}).trim();

export async function createStoreFixture() {
  const name='demostores'+randomUUID().replaceAll('-','').slice(0,16);
  const containers=[];let networkCreated=false;
  const targets=fixtureTargets();
  const review=createResetReview(targets,{...observedTargets(),bucketVersioning:'Disabled'},{codeSha256:createHash('sha256').update(readFileSync(new URL('../demo-supervisor.sh',import.meta.url))).digest('hex'),policySha256:'b'.repeat(64),sshKeyFingerprint:'SHA256:'+'A'.repeat(43),writers:WRITERS,protectedServices:PROTECTED});
  const approval={decision:'approve',fingerprint:reviewFingerprint(review),approvedAt:new Date().toISOString()};
  const config={accounts:Object.fromEntries(['buyer','seller','developer','admin'].map(role=>[role,{email:role+'@example.invalid',password:'fixture',username:'demo_'+role}])),aws:{accessKeyId:'fixture',secretAccessKey:'fixture'},ssh:{keyFile:'fixture',knownHostsFile:'fixture'},mysql:{user:'root',password:'fixture'},mongo:{catalogUri:'mongodb+srv://catalog-demo:fixture@rebyte-dev.fixture.mongodb.net/catalog_demo',messagingUri:'mongodb+srv://messaging-demo:fixture@rebyte-dev.fixture.mongodb.net/messaging_demo'},rabbit:{user:'fixture',password:'fixture'},redisPassword:'fixture',meiliKey:'fixture-search-key-at-least-sixteen'};
  const ports={};const commands=[];
  const invoke=(container,args,{input='',env={}}={})=>execFileSync('docker',['exec','-i',...Object.entries(env).flatMap(([key,value])=>['--env',key+'='+value]),container,...args],{input,encoding:'utf8',windowsHide:true,timeout:90000,maxBuffer:8*1024*1024,stdio:['pipe','pipe','pipe']}).trim();
  const run=async(command,args,options={})=>{
    commands.push({command,args:[...args],input:options.input});
    if(command==='mysql')return invoke(name+'-mysql-app',['mysql',...args],options);
    if(command==='redis-cli')return invoke(name+'-redis',['redis-cli',...args],options);
    if(command==='mongosh'){
      const uri=new URL(options.env.REBYTE_RESET_MONGO_URI);
      return invoke(name+'-mongo',['mongosh',...args],{...options,env:{REBYTE_RESET_MONGO_URI:`mongodb://${decodeURIComponent(uri.username)}:fixture@127.0.0.1:27017${uri.pathname}?authSource=${uri.pathname.slice(1)}`}});
    }
    throw Error('External tool forbidden in disposable store fixture');
  };
  const fetchImpl=async(url,options)=>{
    const target=new URL(url);const port=ports[target.hostname];assert.ok(port,'external HTTP forbidden in store fixture');target.hostname='127.0.0.1';target.port=port;
    const response=await fetch(target,options);return new Response(response.status===204?null:await response.text(),{status:response.status,headers:response.headers});
  };
  const fixture={name,targets,review,approval,config,commands,invoke,ports,run,fetchImpl,
    adapter(){return new ResetAdapters({targets,review,approval,config},{run,fetchImpl});},
    async cleanup(){for(const container of containers.reverse()){try{fixtureDocker('rm','-fv',container);}catch(error){if(!String(error.stderr).includes('No such container'))throw error;}}if(networkCreated)fixtureDocker('network','rm',name);},
  };
  try{
    fixtureDocker('network','create',name);networkCreated=true;
    for(const [service,image,env,port,extra] of [
      ['mysql-app','mysql:8.4',['MYSQL_ROOT_PASSWORD=fixture'],null,[]],
      ['mongo','mongo:7',[],null,[]],
      ['redis','redis:7-alpine',[],null,['redis-server','--requirepass','fixture']],
      ['rabbitmq','rebyte/rabbitmq:4.1-delayed',['RABBITMQ_DEFAULT_USER=fixture','RABBITMQ_DEFAULT_PASS=fixture'],15672,[]],
      ['meilisearch','getmeili/meilisearch:v1.53.2',['MEILI_MASTER_KEY='+config.meiliKey,'MEILI_ENV=production','MEILI_NO_ANALYTICS=true'],7700,[]],
    ]){
      const container=name+'-'+service;containers.push(container);
      fixtureDocker('run','-d','--name',container,'--network',name,'--network-alias',service,'--memory',service==='mysql-app'?'512m':'384m',...env.flatMap(value=>['--env',value]),...(port?['-p',`127.0.0.1::${port}`]:[]),image,...extra);
      if(port)ports[service]=fixtureDocker('port',container,port+'/tcp').split(':').at(-1);
    }
    const ready=async action=>{const deadline=Date.now()+120000;for(;;){try{await action();return;}catch(error){if(Date.now()>=deadline)throw error;await delay(500);}}};
    await ready(()=>invoke(name+'-mysql-app',['mysql','--host=mysql-app','-uroot','--batch','--skip-column-names'],{input:'SELECT 1;',env:{MYSQL_PWD:'fixture'}}));
    await ready(()=>invoke(name+'-mongo',['mongosh','--quiet','--eval','db.runCommand({ping:1})']));
    await ready(async()=>{const response=await fetch(`http://127.0.0.1:${ports.rabbitmq}/api/overview`,{headers:{Authorization:'Basic '+Buffer.from('fixture:fixture').toString('base64'),Connection:'close'}});assert.ok(response.ok);await response.text();});
    await ready(async()=>assert.ok((await fetch(`http://127.0.0.1:${ports.meilisearch}/health`)).ok));
    let sql='CREATE DATABASE user_db;CREATE TABLE user_db.sentinel(id INT);INSERT INTO user_db.sentinel VALUES(1);';
    for(const [schema,tables] of Object.entries(MYSQL_TABLES)){
      sql+=`CREATE DATABASE ${schema};CREATE TABLE ${schema}.flyway_schema_history(id INT PRIMARY KEY,description TEXT);INSERT INTO ${schema}.flyway_schema_history VALUES(1,'keep migration');`;
      for(const table of tables)sql+=`CREATE TABLE ${schema}.\`${table}\`(id INT PRIMARY KEY,payload VARCHAR(64),INDEX payload_index(payload));INSERT INTO ${schema}.\`${table}\` VALUES(1,'remove sentinel');`;
    }
    sql+='ALTER TABLE order_demo.reviews ADD CONSTRAINT fixture_parent FOREIGN KEY (id) REFERENCES order_demo.orders(id);';
    await run('mysql',['--host=mysql-app','--user=root'],{input:sql,env:{MYSQL_PWD:'fixture'}});
    const mongo=`for(const [name,user] of [['catalog_demo','catalog-demo'],['messaging_demo','messaging-demo']]){const d=db.getSiblingDB(name);d.createUser({user,pwd:'fixture',roles:[{role:'readWrite',db:name}]});for(const collection of ['domain','outbox','inbox','dedup']){d[collection].insertOne({sentinel:1});d[collection].createIndex({sentinel:1},{unique:true});}}`;
    invoke(name+'-mongo',['mongosh','--quiet','--eval',mongo]);
    await run('redis-cli',['-h','redis','-n','0','SET','sentinel','old'],{env:{REDISCLI_AUTH:'fixture'}});
    await run('redis-cli',['-h','redis','-n','1','SET','sentinel','keep'],{env:{REDISCLI_AUTH:'fixture'}});
    return fixture;
  }catch(error){await fixture.cleanup();throw error;}
}
