import assert from 'node:assert/strict';
import test from 'node:test';
import http from 'node:http';
import {once} from 'node:events';
import {randomUUID} from 'node:crypto';
import {setTimeout as delay} from 'node:timers/promises';
import {fixtureTargets} from './fixtures/reset-fixture.mjs';
import {createSeedApiFixture} from './fixtures/seed-api-fixture.mjs';
import {createSupervisorFixture} from './fixtures/supervisor-fixture.mjs';
import {SeedApi,seedDemo,verifySeed} from './seed.mjs';

const targets=fixtureTargets();
const roles=Object.keys(targets.preservedSubjects);
const accounts=Object.fromEntries(roles.map(role=>[role,{username:'demo_'+role}]));
const tokens=Object.fromEntries(roles.map(role=>[role,token(role)]));
function token(role,overrides={}){
  return [Buffer.from(JSON.stringify({alg:'RS256'})).toString('base64url'),Buffer.from(JSON.stringify({sub:targets.preservedSubjects[role],iss:`https://cognito-idp.${targets.aws.region}.amazonaws.com/${targets.aws.poolId}`,client_id:targets.aws.clientId,token_use:'access',exp:Date.now()/1000+3600,'cognito:groups':role==='admin'?['ADMIN']:[],...overrides})).toString('base64url'),'fixture'].join('.');
}
async function httpFixture(t){
  const fixture=createSeedApiFixture(targets);
  const server=http.createServer(async(req,res)=>{
    try{
      const chunks=[];for await(const chunk of req)chunks.push(chunk);
      const text=Buffer.concat(chunks).toString();
      const claims=JSON.parse(Buffer.from(req.headers.authorization.split('.')[1],'base64url'));
      const data=fixture.handle({method:req.method,path:req.url,claims,body:text?JSON.parse(text):undefined});
      res.setHeader('Content-Type','application/json');res.end(JSON.stringify(data));
    }catch{res.writeHead(400);res.end('{}');}
  }).listen(0,'127.0.0.1');
  await once(server,'listening');t.after(()=>new Promise(resolve=>{server.close(resolve);server.closeAllConnections();}));
  fixture.fetchImpl=async(url,options)=>{
    assert.equal(options.redirect,'manual');
    if(new URL(url).protocol==='https:'){
      assert.equal(url,`https://${targets.aws.bucket}.s3.${targets.aws.region}.amazonaws.com/`);
      await fixture.upload(options.body);return new Response(null,{status:204});
    }
    assert.equal(new URL(url).origin,'http://api-gateway:8080');
    const response=await fetch('http://127.0.0.1:'+server.address().port+new URL(url).pathname+new URL(url).search,options);
    return new Response(await response.arrayBuffer(),{status:response.status,headers:response.headers});
  };
  return fixture;
}
test('unpaid seed creates realistic listings, media and social/chat projections through HTTP',async t=>{
  const fixture=await httpFixture(t);const context={targets,tokens,accounts,fetchImpl:fixture.fetchImpl};
  const data=await seedDemo(context);assert.equal(await verifySeed(data,context),true);
  const {state}=fixture;
  assert.equal(state.listings.length,30);assert.equal(state.uploads.length,36);assert.equal(state.categories.filter(category=>category.parentId).length,23);assert.equal(state.attributes.length,0);
  assert.equal(state.listings.filter(listing=>listing.type==='hardware').length,24);assert.equal(state.listings.filter(listing=>listing.type==='software').length,6);
  assert.ok(state.listings.every(listing=>listing.photos.length===1&&listing.attributes.length===0));assert.ok(state.listings.filter(listing=>listing.type==='software').every(listing=>listing.artifactAvailable));
  assert.equal(state.uploads.filter(upload=>upload.type==='image/jpeg').length,28);assert.equal(state.uploads.filter(upload=>upload.type==='image/png').length,2);
  assert.ok(state.uploads.filter(upload=>upload.type==='image/jpeg').every(upload=>upload.bytes[0]===0xff&&upload.bytes[1]===0xd8));
  assert.equal(state.profiles.developer.isDeveloper,true);assert.equal(state.profiles.seller.isDeveloper,undefined);
  assert.deepEqual(new Set(state.follows),new Set([targets.preservedSubjects.buyer,targets.preservedSubjects.developer]));
  assert.equal(state.conversations[0].messages.length,2);assert.equal(state.favourites.length,1);
  assert.deepEqual(state.conversations[0].messages.map(message=>message.text),['Hi, is this still available?','Yes, it is. Happy to answer any questions.']);
  assert.equal(state.calls.some(call=>/payments|orders|licen[cs]es|reviews|internal/.test(call.path)),false);
  assert.ok(state.uploads.filter(upload=>upload.type==='image/png').every(upload=>upload.bytes.subarray(1,4).toString()==='PNG'));
  assert.ok(Object.entries(data.categories).every(([path,id])=>state.categories.some(category=>category.name===path.split('/').at(-1)&&category.id===id)));
});
test('seed reuses the catalog default category tree and creates only missing leaves',async t=>{
  const fixture=await httpFixture(t);const {state}=fixture;const add=(name,parentId)=>{const category={id:randomUUID(),name,parentId};state.categories.push(category);return category;};
  const hardware=state.categories.find(category=>category.name==='Hardware');
  const computers=add('Computers',hardware.id);const laptops=add('Laptops',computers.id);add('Peripherals',hardware.id);
  const context={targets,tokens,accounts,fetchImpl:fixture.fetchImpl};const data=await seedDemo(context);
  assert.equal(data.categories['Computers/Laptops'],laptops.id);
  assert.equal(state.categories.filter(category=>category.name==='Peripherals').length,1);assert.equal(state.categories.filter(category=>category.name==='Laptops').length,1);
  assert.equal(await verifySeed(data,context),true);
});
test('seed refuses swapped roles, missing private admin and invalid tokens before HTTP',async()=>{
  let calls=0;const fetchImpl=()=>{calls++;throw Error('unexpected HTTP');};
  for(const bad of [undefined,tokens.buyer,token('admin',{exp:0}),token('admin',{client_id:'another'}),token('admin',{'cognito:groups':[]})]){
    await assert.rejects(seedDemo({targets,accounts,tokens:{...tokens,admin:bad},fetchImpl}));
  }
  await assert.rejects(seedDemo({targets,accounts,tokens:{...tokens,seller:tokens.developer},fetchImpl}));assert.equal(calls,0);
});
test('payment, internal, role escalation and encoded paths are rejected without HTTP',async()=>{
  let calls=0;const api=new SeedApi({targets,tokens,fetchImpl:()=>{calls++;throw Error('unexpected HTTP');}});
  for(const [role,method,path,body] of [['buyer','POST','/payments'],['buyer','POST','/orders'],['admin','GET','/internal/users'],['buyer','POST','/categories',{}],['seller','POST','/listings/software',{}],['admin','GET','/search?q=%2fpayments'],['admin','GET','//evil.invalid/']]){
    await assert.rejects(api.request(role,method,path,body));
  }
  for(const body of [{price:'free',attributes:[{}],condition:'GOOD'},{price:'1.00',attributes:[],condition:'GOOD'},{price:'1.00',attributes:[{}],platforms:['LINUX']}])await assert.rejects(api.request('seller','POST','/listings/hardware',body));
  assert.equal(calls,0);
});
test('upload failures never confirm media and foreign bucket/redirect policies fail closed',async t=>{
  const fixture=await httpFixture(t);
  const context={targets,tokens,accounts,fetchImpl:async(url,options)=>new URL(url).protocol==='https:'?new Response(null,{status:403}):fixture.fetchImpl(url,options)};
  await assert.rejects(seedDemo(context));assert.equal(fixture.state.listings.length,1);
  assert.equal(fixture.state.calls.filter(call=>call.path.endsWith('/confirm')).length,0);
  let uploads=0;const api=new SeedApi({targets,tokens,fetchImpl:async()=>{uploads++;return new Response(null,{status:307,headers:{Location:'https://other.invalid/'}});}});
  const policy={url:`https://${targets.aws.bucket}.s3.${targets.aws.region}.amazonaws.com/`,key:'listings/id/photo',algorithm:'fixture',credential:'fixture',date:'fixture',policy:'fixture',signature:'fixture'};
  await assert.rejects(api.upload({...policy,url:'https://other.invalid/'},Buffer.from('x'),'image/png','listings/id/'));
  await assert.rejects(api.upload({...policy,key:'another/photo'},Buffer.from('x'),'image/png','listings/id/'));assert.equal(uploads,0);
  await assert.rejects(api.upload(policy,Buffer.from('x'),'image/png','listings/id/'));assert.equal(uploads,1);
});
test('missing search results and blocked readiness requests expire within a bounded deadline',async t=>{
  const fixture=await httpFixture(t);const context={targets,tokens,accounts,fetchImpl:fixture.fetchImpl};
  const data=await seedDemo(context);fixture.state.searchReady=false;
  await assert.rejects(verifySeed(data,{...context,timeoutMs:80}));
  const before=Date.now();await assert.rejects(verifySeed(data,{...context,timeoutMs:50,fetchImpl:(_url,{signal})=>new Promise((resolve,reject)=>signal.addEventListener('abort',()=>reject(signal.reason),{once:true}))}));assert.ok(Date.now()-before<1000);
});
test('complete private API seed runs with JWT authorization while visitor and webhook ingress stays closed',{timeout:240000},async t=>{
  const fixture=await createSupervisorFixture({seedApi:true});t.after(()=>fixture.cleanup());
  await fixture.control('maintenance-on');await fixture.control('stop-writers');await fixture.control('start-writers');
  assert.equal((await fixture.publicRequest('/users/me',{token:fixture.tokens.buyer})).status,503);
  assert.equal((await fixture.publicRequest('/payments/webhook',{method:'POST'})).status,503);
  assert.equal(fixture.privateRequest('/users/me','').status,401);
  const uploads=[];
  const fetchImpl=async(url,options)=>{
    if(new URL(url).protocol==='https:'){
      const entries=[...options.body.entries()];assert.equal(entries.at(-1)[0],'file');assert.ok(entries.at(-1)[1].size>0);
      const key=options.body.get('key');uploads.push(key);
      assert.equal(fixture.privateRequest('/fixture-upload',fixture.tokens.admin,{method:'POST',body:JSON.stringify({key})}).status,200);
      return new Response(null,{status:204});
    }
    assert.equal(new URL(url).origin,'http://api-gateway:8080');assert.equal(options.redirect,'manual');
    const result=fixture.privateRequest(new URL(url).pathname+new URL(url).search,options.headers.Authorization.slice(7),options);
    return new Response(result.body,{status:result.status});
  };
  const context={targets,tokens:fixture.tokens,accounts,fetchImpl};
  const data=await seedDemo(context);assert.equal(await verifySeed(data,context),true);assert.equal(uploads.length,36);
  const state=JSON.parse(fixture.privateRequest('/fixture-state',fixture.tokens.admin).body);
  assert.equal(state.listings.length,30);assert.equal(state.conversations[0].messages.length,2);
  assert.equal(state.calls.some(call=>/payments|orders|licen[cs]es|reviews|internal/.test(call.path)),false);
  assert.equal((await fixture.publicRequest('/users/me',{token:fixture.tokens.buyer})).status,503);
  assert.equal((await fixture.publicRequest('/payments/webhook',{method:'POST'})).status,503);
  await fixture.control('maintenance-off');
  const deadline=Date.now()+10000;
  while((await fixture.publicRequest('/users/me',{token:fixture.tokens.buyer})).status!==200){assert.ok(Date.now()<deadline,'Disposable proxy did not reload');await delay(200);}
});
