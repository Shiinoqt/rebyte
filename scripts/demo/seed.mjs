import assert from 'node:assert/strict';
import {setTimeout as delay} from 'node:timers/promises';
import {readFileSync} from 'node:fs';
import {seedRequest,validateOrigins,PUBLIC_ORIGIN,SEED_ORIGIN} from './reset-targets.mjs';

const roles=['buyer','seller','developer','admin'];
const uuid='[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}';
const artifact=Buffer.from('Rebyte demo software sample, version 1.0. No paid licence is included.\n');

export class SeedApi {
  constructor({targets,tokens,fetchImpl=fetch,signal}) {
    validateOrigins(PUBLIC_ORIGIN,SEED_ORIGIN);
    for(const role of roles){
      assert.equal(typeof tokens[role],'string');const claims=JSON.parse(Buffer.from(tokens[role].split('.')[1],'base64url'));
      assert.equal(claims.sub,targets.preservedSubjects[role]);assert.equal(claims.token_use,'access');assert.equal(claims.client_id,targets.aws.clientId);
      assert.equal(claims.iss,`https://cognito-idp.${targets.aws.region}.amazonaws.com/${targets.aws.poolId}`);assert.ok(claims.exp>Date.now()/1000);
      assert.equal((claims['cognito:groups']??[]).includes('ADMIN'),role==='admin');
    }
    Object.assign(this,{targets,tokens,fetchImpl,signal});
  }
  async request(role,method,path,body) {
    const read=method==='GET'&&(path==='/users/me'||path==='/categories'||path==='/favourites'||path==='/notifications'||/^\/search\?[^#]*$/.test(path)||new RegExp(`^/(?:users/${uuid}|listings/${uuid}(?:/versions)?|conversations/${uuid}/messages)$`).test(path));
    const writes=(method==='PUT'&&path==='/users/me')||
      (role==='developer'&&method==='POST'&&path==='/users/me/developer-profile')||
      (role==='admin'&&method==='POST'&&(path==='/categories'||new RegExp(`^/categories/${uuid}/attributes$`).test(path)))||
      (method==='POST'&&((role==='seller'&&path==='/listings/hardware')||(role==='developer'&&path==='/listings/software')))||
      (['seller','developer'].includes(role)&&method==='POST'&&new RegExp(`^/listings/${uuid}/photos/(?:upload-policy|confirm)$`).test(path))||
      (role==='developer'&&method==='POST'&&new RegExp(`^/listings/${uuid}/versions/1\\.0/(?:artifact-upload-policy|artifact/confirm)$`).test(path))||
      (role==='buyer'&&method==='PUT'&&new RegExp(`^/favourites/${uuid}$`).test(path))||
      (['buyer','developer'].includes(role)&&method==='PUT'&&new RegExp(`^/users/${uuid}/follow$`).test(path))||
      (role==='buyer'&&method==='POST'&&path==='/conversations')||
      (role==='seller'&&method==='POST'&&new RegExp(`^/conversations/${uuid}/messages$`).test(path));
    assert.ok(roles.includes(role)&&(read||writes)&&!path.includes('%')&&!path.includes('\\'),'Seed route or role refused');
    if(path==='/listings/hardware'||path==='/listings/software'){
      assert.ok(typeof body?.price==='string'&&/^\d+\.\d{2}$/.test(body.price)&&Number(body.price)>0&&Array.isArray(body.attributes)&&body.attributes.length>0);
      assert.equal('condition' in body,path.endsWith('/hardware'));assert.equal('platforms' in body,path.endsWith('/software'));
    }
    const signal=this.signal?AbortSignal.any([this.signal,AbortSignal.timeout(30000)]):AbortSignal.timeout(30000);
    const response=await seedRequest(path,{token:this.tokens[role],method,body:body===undefined?undefined:JSON.stringify(body),fetchImpl:this.fetchImpl,signal});
    return response.status===204?undefined:response.json();
  }
  async upload(policy,bytes,type,prefix) {
    const expected=`https://${this.targets.aws.bucket}.s3.${this.targets.aws.region}.amazonaws.com/`;
    assert.equal(policy.url,expected);assert.ok(typeof policy.key==='string'&&policy.key.startsWith(prefix));
    const fields={key:policy.key,'x-amz-algorithm':policy.algorithm,'x-amz-credential':policy.credential,'x-amz-date':policy.date,policy:policy.policy,'x-amz-signature':policy.signature,'Content-Type':type};
    const form=new FormData();for(const [name,value] of Object.entries(fields)){assert.equal(typeof value,'string');form.append(name,value);}
    if(policy.securityToken)form.append('x-amz-security-token',policy.securityToken);
    form.append('file',new Blob([bytes],{type}),'demo-sample');
    const signal=this.signal?AbortSignal.any([this.signal,AbortSignal.timeout(30000)]):AbortSignal.timeout(30000);
    const response=await this.fetchImpl(expected,{method:'POST',body:form,redirect:'manual',signal});
    assert.ok(response.ok&&!response.redirected&&(!response.url||response.url===expected),'Demo upload refused');
  }
}

export async function seedDemo(context) {
  const api=new SeedApi(context);const {accounts,targets}=context;
  for(const role of roles){
    assert.match(accounts[role].username,/^[A-Za-z0-9_]{3,30}$/);
    await api.request(role,'GET','/users/me');
    await api.request(role,'PUT','/users/me',{username:accounts[role].username,location:'Rome, Italy',bio:role==='developer'?'Open-source tools for hardware enthusiasts.':'Rebyte demo '+role+' profile.'});
  }
  await api.request('developer','POST','/users/me/developer-profile');
  const roots=await api.request('admin','GET','/categories');assert.ok(Array.isArray(roots));
  const categories={};const listings=[];
  for(const type of ['Hardware','Software']){
    const root=roots.find(root=>root.name===type);assert.ok(root);
    const category=await api.request('admin','POST','/categories',{name:type==='Hardware'?'Demo hardware':'Demo software',parentId:root.id});
    const attribute=await api.request('admin','POST',`/categories/${category.id}/attributes`,{name:'edition',type:'ENUM',required:true,allowedValues:['Standard','Expanded'],unit:null});
    categories[type.toLowerCase()]={id:category.id,attributeId:attribute.id};
    for(const [index,edition] of ['Standard','Expanded'].entries()){
      const hardware=type==='Hardware';const role=hardware?'seller':'developer';
      const body={title:'[demo] '+(hardware?['ThinkPad spare parts','Raspberry Pi starter kit'][index]:['Linux recovery toolkit','Retro serial tools'][index]),description:hardware?'Unpaid demo hardware listing for browsing and sandbox purchases.':'Unpaid demo software listing with a sample version artifact.',price:hardware?(index?'35.00':'18.00'):(index?'7.00':'4.00'),categoryId:category.id,photos:[],paymentMethods:['PAYPAL'],attributes:[{attributeId:attribute.id,value:edition}],...(hardware?{condition:'GOOD',shippingLocation:'Rome, Italy',shippingCost:'3.00'}:{platforms:['LINUX'],versionNumber:'1.0',notes:'Demonstration version',licenceKeyPattern:null})};
      const listing=await api.request(role,'POST',hardware?'/listings/hardware':'/listings/software',body);
      const path=`/listings/${listing.id}/photos`;
const photo=readFileSync(new URL(`./assets/${type.toLowerCase()}-${edition.toLowerCase()}.png`,import.meta.url));
      const policy=await api.request(role,'POST',path+'/upload-policy');await api.upload(policy,photo,'image/png',`listings/${listing.id}/`);
      await api.request(role,'POST',path+'/confirm',{key:policy.key});
if(!hardware){const version=`/listings/${listing.id}/versions/1.0`;const policy=await api.request(role,'POST',version+'/artifact-upload-policy');await api.upload(policy,artifact,'application/octet-stream',`software/${listing.id}/1.0/`);await api.request(role,'POST',version+'/artifact/confirm',{key:policy.key});}
      listings.push({id:listing.id,type:type.toLowerCase(),edition});
    }
  }
  await api.request('buyer','PUT',`/favourites/${listings[0].id}`);
  for(const role of ['buyer','developer'])await api.request(role,'PUT',`/users/${targets.preservedSubjects.seller}/follow`);
  const conversation=await api.request('buyer','POST','/conversations',{listingId:listings[0].id,text:'Hello, is this demo item available?',imageKeys:[]});
  await api.request('seller','POST',`/conversations/${conversation.id}/messages`,{text:'Yes. This is an unpaid demo conversation.',imageKeys:[]});
  return {categories,listings,conversationId:conversation.id};
}

export async function verifySeed(data,context) {
const timeout=context.timeoutMs??60000;
assert.ok(Number.isInteger(timeout)&&timeout>0&&timeout<=60000);const deadline=Date.now()+timeout;
const signal=context.signal?AbortSignal.any([context.signal,AbortSignal.timeout(timeout)]):AbortSignal.timeout(timeout);
const api=new SeedApi({...context,signal});
  for(;;){
signal.throwIfAborted();
    let ready=true;
    for(const listing of data.listings){
      const detail=await api.request('buyer','GET','/listings/'+listing.id);ready&&=Array.isArray(detail.photos)&&detail.photos.length>0;
      if(listing.type==='software'){const versions=await api.request('buyer','GET',`/listings/${listing.id}/versions`);ready&&=Array.isArray(versions)&&versions.some(version=>version.number==='1.0'&&version.artifactAvailable===true);}
    }
    for(const category of Object.values(data.categories)){
      const search=await api.request('buyer','GET',`/search?q=demo&categoryId=${category.id}&size=20`);
ready&&=Array.isArray(search.hits)&&data.listings.filter(listing=>data.categories[listing.type].id===category.id).every(listing=>search.hits.some(hit=>hit.id===listing.id&&typeof hit.coverUrl==='string'&&hit.coverUrl.length>0));
      ready&&=Array.isArray(search.facets)&&search.facets.some(facet=>facet.attribute==='edition'&&facet.values?.Standard>=1&&facet.values?.Expanded>=1);
    }
    const profile=await api.request('buyer','GET','/users/'+context.targets.preservedSubjects.seller);ready&&=profile.followerCount>=2&&profile.followedByMe===true;
    const favourites=await api.request('buyer','GET','/favourites');ready&&=Array.isArray(favourites.favourites)&&favourites.favourites.some(favourite=>favourite.listingId===data.listings[0].id);
    const messages=await api.request('buyer','GET',`/conversations/${data.conversationId}/messages`);ready&&=Array.isArray(messages.items)&&messages.items.length>=2;
    const notifications=await api.request('seller','GET','/notifications');ready&&=Array.isArray(notifications.items)&&notifications.items.length>=1;
    if(ready)return true;
    if(Date.now()>=deadline)throw Error('Demo seed readiness timed out');
await delay(Math.min(500,Math.max(1,deadline-Date.now())),undefined,{signal});
  }
}
