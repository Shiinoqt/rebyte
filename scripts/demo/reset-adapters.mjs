import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {createHash} from 'node:crypto';
import {mkdtempSync,readFileSync,rmdirSync,unlinkSync,writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {setTimeout as delay} from 'node:timers/promises';
import {reviewFingerprint,verifyApproval,seedRequest} from './reset-targets.mjs';

export const MYSQL_TABLES={
  user_demo:['user','admin','developer_profile','seller_review','outbox_event','follow','seller_sale','follow_transition_sequence'],
  order_demo:['orders','order_inbox_events','order_outbox_events','offers','licenses','order_problem','reviews'],
  payment_demo:['payments','paypal_webhook_events','payment_outbox_events'],
};
export const RABBIT_QUEUES=['search.listing-changed','cache.listing-changed','catalog.order-lifecycle','catalog.order-lifecycle.dlq','order.payment-result','order.payment-result.dlq','order.listing-lifecycle','order.listing-lifecycle.dlq','user-service.review-created','user-service.review-created.dlq','user-service.order-sales','user-service.order-sales.dlq',...['notify.favourite','notify.follow','notify.price-drop','notify.version-released','notify.review','notify.offer','chat.offer-card','notify.order','messaging.email-send'].flatMap(name=>[name,name+'.dlq'])];
const roles=['buyer','seller','developer','admin'];
const maxPages=1000;
const identifier=/^[A-Za-z0-9_]+$/;

export function runTool(command,args,{input='',env={},signal,timeout=60000}={}) {
  return new Promise((resolve,reject)=>{
    let output='',failed=false;
    let inputDirectory,inputPath;
    const failure=()=>new Error('Private reset tool failed');
    const clean=()=>{if(inputPath){unlinkSync(inputPath);inputPath=undefined;}if(inputDirectory){rmdirSync(inputDirectory);inputDirectory=undefined;}};
    let child;
    try{
      if(signal?.aborted)throw failure();
      if(['aws','mongosh'].includes(command)&&args.some(arg=>['/dev/stdin','file:///dev/stdin'].includes(arg))){
        inputDirectory=mkdtempSync(join(tmpdir(),'rebyte-reset-input-'));
        inputPath=join(inputDirectory,command==='aws'?'input.json':'input.js');
        writeFileSync(inputPath,input,{flag:'wx',mode:0o600});
        args=args.map(arg=>arg==='/dev/stdin'?inputPath:arg==='file:///dev/stdin'?'file://'+inputPath:arg);
      }
    const environment={...process.env,...env};
    for(const key of Object.keys(environment))if(environment[key]===undefined||command==='aws'&&key.startsWith('AWS_ENDPOINT_URL'))delete environment[key];
    child=spawn(command,args,{shell:false,windowsHide:true,env:environment,stdio:[inputPath?'ignore':'pipe','pipe','pipe']});
    }catch{try{clean();}catch{}reject(failure());return;}
    const stop=()=>{failed=true;child.kill();};
    const timer=setTimeout(stop,timeout);const hardTimer=setTimeout(()=>{failed=true;child.kill('SIGKILL');},timeout+5000);
    signal?.addEventListener('abort',stop,{once:true});if(signal?.aborted)stop();
    child.stdout.on('data',chunk=>{if(Buffer.byteLength(output)+chunk.length>8*1024*1024)stop();else output+=chunk;});
    child.stderr.resume();if(child.stdin){child.stdin.on('error',()=>{});child.stdin.end(input);}
    child.on('error',()=>{failed=true;});
    child.on('close',code=>{clearTimeout(timer);clearTimeout(hardTimer);signal?.removeEventListener('abort',stop);try{clean();}catch{failed=true;}if(failed||code!==0)reject(failure());else resolve(output.trim());});
  });
}

export class ResetAdapters {
  constructor({targets,review,approval,config},{run=runTool,fetchImpl=fetch}={}) {
    verifyApproval(review,approval);assert.deepEqual(review.targets,targets);
    assert.equal(review.control.codeSha256,createHash('sha256').update(readFileSync(new URL('./demo-supervisor.sh',import.meta.url))).digest('hex'));
    assert.deepEqual(targets.stores.mysqlSchemas,Object.keys(MYSQL_TABLES));
    for(const role of roles){assert.ok(config.accounts[role].email&&config.accounts[role].password);assert.match(config.accounts[role].username,/^[A-Za-z0-9_]{3,30}$/);}
    assert.equal(new Set(roles.map(role=>config.accounts[role].email)).size,4);
    assert.match(config.mysql.user,identifier);assert.ok(config.mysql.password&&config.redisPassword&&config.meiliKey&&config.rabbit.user&&config.rabbit.password);
    assert.ok(config.aws.accessKeyId&&config.aws.secretAccessKey&&config.ssh.keyFile&&config.ssh.knownHostsFile);
    for(const role of ['catalog','messaging']){
      const uri=new URL(config.mongo[role+'Uri']);
      assert.equal(uri.protocol,'mongodb+srv:');assert.ok(uri.hostname.startsWith(targets.atlas.cluster+'.')&&uri.hostname.endsWith('.mongodb.net'));
      assert.equal(decodeURIComponent(uri.username),targets.atlas.users[role]);assert.equal(uri.pathname,'/'+targets.atlas.databases[role]);assert.ok(uri.password);
    }
    Object.assign(this,{targets,review,approval,config,run,fetchImpl});this.tokens={};
  }
  async aws(service,operation,payload={},signal) {
    const env={AWS_ACCESS_KEY_ID:this.config.aws.accessKeyId,AWS_SECRET_ACCESS_KEY:this.config.aws.secretAccessKey,AWS_SESSION_TOKEN:this.config.aws.sessionToken??undefined,AWS_PROFILE:undefined,AWS_DEFAULT_PROFILE:undefined,AWS_CONFIG_FILE:'/dev/null',AWS_SHARED_CREDENTIALS_FILE:'/dev/null',AWS_PAGER:''};
    const result=await this.run('aws',[service,operation,'--region',this.targets.aws.region,'--no-paginate','--no-cli-pager','--output','json','--cli-input-json','file:///dev/stdin'],{input:JSON.stringify(payload),env,signal});
    return JSON.parse(result||'{}');
  }
  async control(action,signal) {
    assert.ok(['status','maintenance-on','stop-writers','start-writers','maintenance-off'].includes(action));
    const result=await this.run('ssh',['-T','-i',this.config.ssh.keyFile,'-o','BatchMode=yes','-o','IdentitiesOnly=yes','-o','StrictHostKeyChecking=yes','-o',`UserKnownHostsFile=${this.config.ssh.knownHostsFile}`,'-o','ConnectTimeout=10','rebyte-reset@169.58.65.61',action],{signal,timeout:240000});
    if(action==='status'){const status=JSON.parse(result);assert.equal(status.fingerprint,reviewFingerprint(this.review));assert.ok(['open','maintenance'].includes(status.state));return status;}
    assert.equal(result,{'maintenance-on':'maintenance','stop-writers':'quiescent','start-writers':'ready-for-private-seed','maintenance-off':'open'}[action]);
  }
  async http(kind,path,method='GET',body,signal) {
    const origins={rabbit:'http://rabbitmq:15672',search:'http://meilisearch:7700'};
    assert.ok(origins[kind]&&path.startsWith('/')&&!path.startsWith('//'));
    const url=new URL(path,origins[kind]);assert.equal(url.origin,origins[kind]);
    const authorization=kind==='rabbit'?'Basic '+Buffer.from(this.config.rabbit.user+':'+this.config.rabbit.password).toString('base64'):'Bearer '+this.config.meiliKey;
    const response=await this.fetchImpl(url.href,{method,headers:{Authorization:authorization,'Content-Type':'application/json',Connection:'close'},body:body===undefined?undefined:JSON.stringify(body),redirect:'manual',signal:signal?AbortSignal.any([signal,AbortSignal.timeout(15000)]):AbortSignal.timeout(15000)});
    if(!response.ok||response.redirected||response.url&&response.url!==url.href)throw Error('Private reset HTTP request refused');
    if(response.status===204)return;
    const text=await response.text();assert.ok(Buffer.byteLength(text)<=8*1024*1024);return text?JSON.parse(text):undefined;
  }
  mysql(sql,signal){return this.run('mysql',['--host=mysql-app','--port=3306','--user='+this.config.mysql.user,'--batch','--skip-column-names'],{input:sql,env:{MYSQL_PWD:this.config.mysql.password},signal});}
  async authenticate(role,signal) {
    const account=this.config.accounts[role];const url=`https://cognito-idp.${this.targets.aws.region}.amazonaws.com/`;
    const response=await this.fetchImpl(url,{method:'POST',headers:{'Content-Type':'application/x-amz-json-1.1','X-Amz-Target':'AWSCognitoIdentityProviderService.InitiateAuth'},body:JSON.stringify({AuthFlow:'USER_PASSWORD_AUTH',ClientId:this.targets.aws.clientId,AuthParameters:{USERNAME:account.email,PASSWORD:account.password}}),redirect:'manual',signal:signal?AbortSignal.any([signal,AbortSignal.timeout(30000)]):AbortSignal.timeout(30000)});
    const body=await response.json();
    if(response.status===400&&['NotAuthorizedException','UserNotConfirmedException','PasswordResetRequiredException'].includes(String(body.__type).split('#').at(-1))){delete this.tokens[role];return false;}
    assert.ok(response.ok&&!response.redirected&&(!response.url||response.url===url));
    const token=body.AuthenticationResult?.AccessToken;assert.equal(typeof token,'string');const claims=JSON.parse(Buffer.from(token.split('.')[1],'base64url'));
    assert.equal(claims.sub,this.targets.preservedSubjects[role]);assert.equal(claims.token_use,'access');assert.equal(claims.client_id,this.targets.aws.clientId);assert.equal(claims.iss,url.slice(0,-1)+'/'+this.targets.aws.poolId);assert.ok(claims.exp>Date.now()/1000);
    assert.equal((claims['cognito:groups']??[]).includes('ADMIN'),role==='admin');this.tokens[role]=token;return true;
  }
  async groups(username,signal) {
    const groups=[];const seen=new Set();let token;
    for(let page=0;page<maxPages;page++){
      const data=await this.aws('cognito-idp','admin-list-groups-for-user',{UserPoolId:this.targets.aws.poolId,Username:username,Limit:60,...(token?{NextToken:token}:{})},signal);
      assert.ok(Array.isArray(data.Groups));groups.push(...data.Groups.map(group=>group.GroupName));
      if(!data.NextToken)return groups;assert.ok(typeof data.NextToken==='string'&&!seen.has(data.NextToken));seen.add(data.NextToken);token=data.NextToken;
    }throw Error('Cognito group page bound exceeded');
  }
  async observe(signal) {
    const t=this.targets;const actual=structuredClone(t);
    actual.aws.accountId=(await this.aws('sts','get-caller-identity',{},signal)).Account;
    assert.equal(actual.aws.accountId,t.aws.accountId);
    const pool=(await this.aws('cognito-idp','describe-user-pool',{UserPoolId:t.aws.poolId},signal)).UserPool;
    assert.equal(pool.Arn,`arn:aws:cognito-idp:${t.aws.region}:${t.aws.accountId}:userpool/${t.aws.poolId}`);actual.aws.poolId=pool.Id;
    actual.aws.clientId=(await this.aws('cognito-idp','describe-user-pool-client',{UserPoolId:t.aws.poolId,ClientId:t.aws.clientId},signal)).UserPoolClient.ClientId;
    await this.aws('s3api','head-bucket',{Bucket:t.aws.bucket,ExpectedBucketOwner:t.aws.accountId},signal);
    assert.equal((await this.aws('s3api','get-bucket-versioning',{Bucket:t.aws.bucket,ExpectedBucketOwner:t.aws.accountId},signal)).Status??'Disabled',this.review.scope.bucket.versioning);
    await this.mysqlTables(signal);
    for(const role of ['catalog','messaging']){
      const database=t.atlas.databases[role],user=t.atlas.users[role];
      const source=`const d=new Mongo(process.env.REBYTE_RESET_MONGO_URI).getDB(${JSON.stringify(database)});if(d.getName()!==${JSON.stringify(database)}||!d.runCommand({connectionStatus:1}).authInfo.authenticatedUsers.some(u=>u.user===${JSON.stringify(user)}))throw Error('identity');if(d.getCollectionInfos().some(c=>c.type!=='collection'||c.name.startsWith('system.')))throw Error('collections');print('verified');`;
      assert.equal(await this.run('mongosh',['--nodb','--quiet','--file','/dev/stdin'],{input:source,env:{REBYTE_RESET_MONGO_URI:this.config.mongo[role+'Uri']},signal}),'verified');
    }
    assert.ok((await this.rabbitList('queues',signal)).every(queue=>RABBIT_QUEUES.includes(queue.name)));
    assert.ok((await this.rabbitList('exchanges',signal)).every(exchange=>exchange.name===''||exchange.name.startsWith('amq.')||exchange.name==='marketplace.events'));
    assert.ok((await this.indexes(signal)).every(index=>/^listings(?:_rebuild_[a-f0-9]{32})?$/.test(index.uid)));
    const accounts={};
    for(const role of roles){
      const user=await this.aws('cognito-idp','admin-get-user',{UserPoolId:t.aws.poolId,Username:t.preservedSubjects[role]},signal);
      assert.ok(Array.isArray(user.UserAttributes));const attributes=Object.fromEntries(user.UserAttributes.map(a=>[a.Name,a.Value]));
      accounts[role]={subject:attributes.sub,enabled:user.Enabled,emailVerified:attributes.email_verified==='true',groups:await this.groups(user.Username,signal),authenticationVerified:await this.authenticate(role,signal)};
    }
    assert.deepEqual(actual,t);return {targets:actual,accounts};
  }
  async mysqlTables(signal) {
    const schemas=this.targets.stores.mysqlSchemas;
    const rows=(await this.mysql(`SELECT table_schema,table_name FROM information_schema.tables WHERE table_schema IN ('${schemas.join("','")}') AND table_type='BASE TABLE' ORDER BY table_schema,table_name;`,signal)).split('\n').filter(Boolean).map(row=>row.split('\t'));
    for(const [schema,table,...extra] of rows)assert.ok(!extra.length&&schemas.includes(schema)&&identifier.test(table));
    for(const schema of schemas)assert.deepEqual(rows.filter(row=>row[0]===schema).map(row=>row[1]).sort(),[...MYSQL_TABLES[schema],'flyway_schema_history'].sort());
    return rows;
  }
  async clearMySql(signal) {
    const schemas=this.targets.stores.mysqlSchemas;const rows=await this.mysqlTables(signal);
    const counts=()=>schemas.map(schema=>`SELECT '${schema}',COUNT(*) FROM \`${schema}\`.\`flyway_schema_history\``).join(' UNION ALL ')+';';
    const metadata=await this.mysql(counts(),signal);
    signal?.throwIfAborted();
    await this.mysql('SET FOREIGN_KEY_CHECKS=0;\n'+rows.filter(([,table])=>table!=='flyway_schema_history').map(([schema,table])=>`TRUNCATE TABLE \`${schema}\`.\`${table}\`;`).join('\n')+'\nSET FOREIGN_KEY_CHECKS=1;',signal);
    assert.equal(await this.mysql(counts(),signal),metadata);
    const totals=rows.filter(([,table])=>table!=='flyway_schema_history').map(([schema,table])=>`SELECT COUNT(*) FROM \`${schema}\`.\`${table}\``).join(';')+';';
    assert.ok((await this.mysql(totals,signal)).split('\n').every(count=>count==='0'));
  }
  async clearMongo(signal) {
    for(const role of ['catalog','messaging']){
      const name=this.targets.atlas.databases[role];
      const source=`const db=new Mongo(process.env.REBYTE_RESET_MONGO_URI).getDB(${JSON.stringify(name)});if(db.getName()!==${JSON.stringify(name)})throw Error('database');const collections=db.getCollectionInfos();for(const c of collections){if(c.type!=='collection'||c.name.startsWith('system.'))throw Error('collection');const collection=db.getCollection(c.name);const before=JSON.stringify(collection.getIndexes());const result=collection.deleteMany({});if(!result.acknowledged||collection.countDocuments({})!==0||JSON.stringify(collection.getIndexes())!==before)throw Error('wipe');}print(JSON.stringify({database:db.getName(),collections:collections.length,empty:true}));`;
      signal?.throwIfAborted();const result=JSON.parse(await this.run('mongosh',['--nodb','--quiet','--file','/dev/stdin'],{input:source,env:{REBYTE_RESET_MONGO_URI:this.config.mongo[role+'Uri']},signal}));
      assert.equal(result.database,name);assert.equal(result.empty,true);
    }
  }
  async clearBucket(signal) {
    const base={Bucket:this.targets.aws.bucket,ExpectedBucketOwner:this.targets.aws.accountId};
    const versioning=(await this.aws('s3api','get-bucket-versioning',base,signal)).Status??'Disabled';assert.equal(versioning,this.review.scope.bucket.versioning);
    let previous;
    for(let page=0;page<maxPages;page++){
      const data=await this.aws('s3api',versioning==='Disabled'?'list-objects-v2':'list-object-versions',{...base,MaxKeys:1000},signal);
      assert.equal(typeof data.IsTruncated,'boolean');
      const objects=versioning==='Disabled'?(data.Contents??[]).map(({Key})=>({Key})):[...(data.Versions??[]),...(data.DeleteMarkers??[])].map(({Key,VersionId})=>({Key,VersionId}));
      assert.ok(objects.length<=1000&&objects.every(o=>typeof o.Key==='string'&&(versioning==='Disabled'||typeof o.VersionId==='string')));
      if(!objects.length){assert.notEqual(data.IsTruncated,true);return;}
      const signature=JSON.stringify(objects);assert.notEqual(signature,previous);previous=signature;
      signal?.throwIfAborted();const deleted=await this.aws('s3api','delete-objects',{...base,Delete:{Objects:objects,Quiet:false}},signal);
      assert.ok(!deleted.Errors?.length);assert.equal(deleted.Deleted?.length,objects.length);
    }throw Error('S3 deletion page bound exceeded');
  }
  async clearRedis(signal) {
    assert.equal(this.review.scope.redis.databases.length,1);assert.equal(this.review.scope.redis.databases[0],0);
    const args=['-h','redis','-p','6379','-n','0','--raw'];const options={env:{REDISCLI_AUTH:this.config.redisPassword},signal};
    signal?.throwIfAborted();assert.equal(await this.run('redis-cli',[...args,'FLUSHDB','SYNC'],options),'OK');assert.equal(await this.run('redis-cli',[...args,'DBSIZE'],options),'0');
  }
  async rabbitList(kind,signal) {
    const values=[];
    for(let page=1;page<=maxPages;page++){
      const data=await this.http('rabbit',`/api/${kind}/%2F?pagination=true&page_size=100&page=${page}`,'GET',undefined,signal);
      assert.ok(Array.isArray(data.items)&&Number.isInteger(data.page_count));values.push(...data.items);
      if(page>=data.page_count)return values;
    }throw Error('Broker page bound exceeded');
  }
  async clearRabbit(signal) {
    let queues;const deadline=Date.now()+30000;
    for(;;){
      queues=await this.rabbitList('queues',signal);assert.ok(queues.every(q=>RABBIT_QUEUES.includes(q.name)));
      if(queues.every(q=>q.consumers===0&&q.messages_unacknowledged===0))break;
      assert.ok(Date.now()<deadline);await delay(250,undefined,{signal});
    }
    const exchanges=await this.rabbitList('exchanges',signal);
    assert.ok(exchanges.every(e=>e.name===''||e.name.startsWith('amq.')||e.name==='marketplace.events'));
    for(const queue of queues){signal?.throwIfAborted();await this.http('rabbit','/api/queues/%2F/'+encodeURIComponent(queue.name)+'?if-unused=true','DELETE',undefined,signal);}
    // The reviewed host supervisor has already cleared plugin-held delayed deliveries.
    for(const exchange of exchanges.filter(e=>e.name==='marketplace.events'))await this.http('rabbit','/api/exchanges/%2F/'+encodeURIComponent(exchange.name),'DELETE',undefined,signal);
    assert.equal((await this.rabbitList('queues',signal)).length,0);
  }
  async indexes(signal) {
    const values=[];
    for(let page=0;page<maxPages;page++){
      const data=await this.http('search',`/indexes?limit=100&offset=${page*100}`,'GET',undefined,signal);assert.ok(Array.isArray(data.results)&&Number.isInteger(data.total));values.push(...data.results);
      if(values.length>=data.total)return values;assert.ok(data.results.length>0);
    }throw Error('Search page bound exceeded');
  }
  async waitTask(task,signal) {
    assert.ok(Number.isSafeInteger(task.taskUid)&&task.taskUid>=0);const deadline=Date.now()+60000;
    for(;;){const result=await this.http('search','/tasks/'+task.taskUid,'GET',undefined,signal);if(result.status==='succeeded')return;assert.ok(['enqueued','processing'].includes(result.status)&&Date.now()<deadline);await delay(250,undefined,{signal});}
  }
  async clearSearch(signal) {
    const indexes=await this.indexes(signal);assert.ok(indexes.every(index=>/^listings(?:_rebuild_[a-f0-9]{32})?$/.test(index.uid)));
    const pending=await this.http('search','/tasks?statuses=enqueued,processing&limit=1','GET',undefined,signal);assert.ok(Array.isArray(pending.results));
    if(pending.results.length)await this.waitTask(await this.http('search','/tasks/cancel?statuses=enqueued,processing','POST',undefined,signal),signal);
    for(const index of indexes){signal?.throwIfAborted();await this.waitTask(await this.http('search','/indexes/'+index.uid,'DELETE',undefined,signal),signal);}
    assert.equal((await this.indexes(signal)).length,0);
  }
  async users(signal) {
    const users=[];const seen=new Set();let token;
    for(let page=0;page<maxPages;page++){
      const data=await this.aws('cognito-idp','list-users',{UserPoolId:this.targets.aws.poolId,Limit:60,...(token?{PaginationToken:token}:{})},signal);assert.ok(Array.isArray(data.Users));users.push(...data.Users);
      if(!data.PaginationToken)return users;assert.ok(typeof data.PaginationToken==='string'&&!seen.has(data.PaginationToken));seen.add(data.PaginationToken);token=data.PaginationToken;
    }throw Error('Cognito user page bound exceeded');
  }
  async repairAccounts(signal) {
    const pool={UserPoolId:this.targets.aws.poolId};const preserved=new Set(Object.values(this.targets.preservedSubjects));const users=await this.users(signal);
    const subject=user=>{assert.ok(typeof user.Username==='string'&&Array.isArray(user.Attributes));const sub=user.Attributes.find(a=>a.Name==='sub')?.Value;assert.equal(typeof sub,'string');return sub;};
    assert.equal(new Set(users.map(subject).filter(sub=>preserved.has(sub))).size,4);
    for(const user of users)if(!preserved.has(subject(user))){signal?.throwIfAborted();await this.aws('cognito-idp','admin-delete-user',{...pool,Username:user.Username},signal);}
    for(const role of ['buyer','seller','developer']){
      const Username=this.targets.preservedSubjects[role];const account=this.config.accounts[role];signal?.throwIfAborted();
      await this.aws('cognito-idp','admin-enable-user',{...pool,Username},signal);
      await this.aws('cognito-idp','admin-update-user-attributes',{...pool,Username,UserAttributes:[{Name:'email',Value:account.email},{Name:'email_verified',Value:'true'},{Name:'preferred_username',Value:account.username}]},signal);
      for(const GroupName of await this.groups(Username,signal))await this.aws('cognito-idp','admin-remove-user-from-group',{...pool,Username,GroupName},signal);
      await this.aws('cognito-idp','admin-set-user-password',{...pool,Username,Password:account.password,Permanent:true},signal);
      await this.aws('cognito-idp','admin-user-global-sign-out',{...pool,Username},signal);
    }
    const remaining=await this.users(signal);assert.equal(remaining.length,4);assert.deepEqual(new Set(remaining.map(subject)),preserved);
  }
  async bootstrap(signal) {
    const deadline=Date.now()+60000;
signal=signal?AbortSignal.any([signal,AbortSignal.timeout(60000)]):AbortSignal.timeout(60000);
    for(;;){
      const response=await seedRequest('/categories',{token:this.tokens.admin,signal,fetchImpl:this.fetchImpl});const roots=await response.json();
      if(Array.isArray(roots)&&['Hardware','Software'].every(name=>roots.some(root=>root.name===name))){assert.ok((await this.indexes(signal)).some(index=>index.uid==='listings'));return;}
      assert.ok(Date.now()<deadline);await delay(500,undefined,{signal});
    }
  }
  async seed(signal){const {seedDemo}=await import('./seed.mjs');this.seeded=await seedDemo({targets:this.targets,accounts:this.config.accounts,tokens:this.tokens,fetchImpl:this.fetchImpl,signal});}
  async readiness(signal){const {verifySeed}=await import('./seed.mjs');return verifySeed(this.seeded,{targets:this.targets,tokens:this.tokens,fetchImpl:this.fetchImpl,signal});}
}
