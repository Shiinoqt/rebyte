import assert from 'node:assert/strict';
import {randomUUID} from 'node:crypto';
import {readFileSync} from 'node:fs';

// Stateful HTTP fixture: domain services and S3 are tested separately from this API contract.
export function createSeedApiFixture(targets,{failureFile}={}) {
  const roles=Object.keys(targets.preservedSubjects);
  const state={profiles:{},categories:['Hardware','Software'].map(name=>({id:randomUUID(),name})),attributes:[],listings:[],uploads:[],favourites:[],follows:[],conversations:[],notifications:[],calls:[],facetsReady:true};
  const requiredRole=(actual,expected)=>assert.ok(expected.includes(actual),'Fixture role refused');
  function handle({method,path,claims,body}) {
    const role=roles.find(role=>targets.preservedSubjects[role]===claims.sub);
    assert.ok(role);assert.equal((claims['cognito:groups']??[]).includes('ADMIN'),role==='admin');
    const url=new URL(path,'http://api-gateway:8080');const parts=url.pathname.split('/').filter(Boolean);
    let failures={};if(failureFile){try{failures=JSON.parse(readFileSync(failureFile,'utf8'));}catch(error){if(error.code!=='ENOENT')throw error;}}
    if(failures.seed&&method==='POST'&&path==='/listings/hardware')throw Error('Injected seed failure');
    state.calls.push({role,method,path});
    if(method==='POST'&&path==='/fixture-upload'){requiredRole(role,['admin']);state.uploads.push(body);return {};}
    if(method==='GET'&&path==='/fixture-state'){requiredRole(role,['admin']);return state;}
    if(method==='GET'&&path==='/users/me')return {sub:claims.sub,...state.profiles[role]};
    if(method==='PUT'&&path==='/users/me'){state.profiles[role]={...body};return {...body};}
    if(method==='POST'&&path==='/users/me/developer-profile'){requiredRole(role,['developer']);state.profiles[role].isDeveloper=true;return {};}
    if(method==='GET'&&path==='/categories')return state.categories.filter(category=>!category.parentId);
    if(method==='POST'&&path==='/categories'){
      requiredRole(role,['admin']);assert.ok(state.categories.some(category=>category.id===body.parentId));
      const category={id:randomUUID(),...body};state.categories.push(category);return category;
    }
    if(method==='POST'&&parts[0]==='categories'&&parts[2]==='attributes'){
      requiredRole(role,['admin']);assert.deepEqual(body.allowedValues,['Standard','Expanded']);assert.equal(body.required,true);
      const attribute={id:randomUUID(),categoryId:parts[1],...body};state.attributes.push(attribute);return attribute;
    }
    if(method==='POST'&&['hardware','software'].includes(parts[1])&&parts.length===2){
      requiredRole(role,parts[1]==='hardware'?['seller']:['developer']);
      const attribute=state.attributes.find(attribute=>attribute.categoryId===body.categoryId);
      assert.equal(body.attributes[0].attributeId,attribute.id);assert.ok(attribute.allowedValues.includes(body.attributes[0].value));
      assert.ok(Number(body.price)>0);assert.deepEqual(body.paymentMethods,['PAYPAL']);
      const listing={id:randomUUID(),type:parts[1],sellerSub:claims.sub,...body,photos:[],artifactAvailable:false};state.listings.push(listing);return {id:listing.id};
    }
    if(parts[0]==='listings'){
      const listing=state.listings.find(listing=>listing.id===parts[1]);assert.ok(listing);
      if(method==='GET'&&parts.length===2)return {...listing};
      if(method==='GET'&&parts[2]==='versions')return [{number:'1.0',artifactAvailable:listing.artifactAvailable,current:true}];
      assert.equal(claims.sub,listing.sellerSub);
      if(method==='POST'&&['upload-policy','artifact-upload-policy'].includes(parts.at(-1))){
        const artifact=parts[2]==='versions';const key=(artifact?'software/':'listings/')+listing.id+'/'+(artifact?'1.0/':'')+randomUUID();
        return {url:`https://${targets.aws.bucket}.s3.${targets.aws.region}.amazonaws.com/`,key,algorithm:'AWS4-HMAC-SHA256',credential:'fixture',date:'20261020T000000Z',policy:'fixture',signature:'fixture',securityToken:'fixture'};
      }
      if(method==='POST'&&parts.at(-1)==='confirm'){
        assert.ok(state.uploads.some(upload=>upload.key===body.key));
        if(parts[2]==='photos')listing.photos.push(body.key);else listing.artifactAvailable=true;
        return {};
      }
    }
    if(method==='PUT'&&parts[0]==='favourites'){requiredRole(role,['buyer']);state.favourites.push({listingId:parts[1]});return {};}
    if(method==='GET'&&path==='/favourites')return {favourites:state.favourites};
    if(method==='PUT'&&parts[0]==='users'&&parts[2]==='follow'){
      requiredRole(role,['buyer','developer']);assert.equal(parts[1],targets.preservedSubjects.seller);
      state.follows.push(claims.sub);state.notifications.push({id:randomUUID(),type:'FOLLOW'});return {};
    }
    if(method==='GET'&&parts[0]==='users')return {sub:parts[1],followerCount:state.follows.length,followedByMe:state.follows.includes(claims.sub)};
    if(method==='POST'&&path==='/conversations'){
      requiredRole(role,['buyer']);assert.equal(body.listingId,state.listings[0].id);
      const conversation={id:randomUUID(),messages:[{id:randomUUID(),text:body.text,senderSub:claims.sub}]};state.conversations.push(conversation);return {id:conversation.id};
    }
    if(parts[0]==='conversations'){
      const conversation=state.conversations.find(conversation=>conversation.id===parts[1]);assert.ok(conversation);
      if(method==='POST'){requiredRole(role,['seller']);conversation.messages.push({id:randomUUID(),text:body.text,senderSub:claims.sub});return {};}
      if(method==='GET')return {items:conversation.messages};
    }
    if(method==='GET'&&path==='/notifications'){requiredRole(role,['seller']);return {items:state.notifications};}
    if(method==='GET'&&parts[0]==='search'){
      const listings=state.listings.filter(listing=>listing.categoryId===url.searchParams.get('categoryId'));
      return {hits:listings.map(listing=>({id:listing.id,coverUrl:listing.photos[0]?'https://media.invalid/'+listing.photos[0]:null})),facets:state.facetsReady&&!failures.readiness?[{attribute:'edition',values:{Standard:1,Expanded:1}}]:[]};
    }
    throw Error('Unexpected fixture API request');
  }
  async function upload(form) {
    const entries=[...form.entries()];assert.equal(entries.at(-1)[0],'file');
    assert.ok(entries.at(-1)[1].size>0);assert.equal(form.get('x-amz-security-token'),'fixture');
    state.uploads.push({key:form.get('key'),type:form.get('Content-Type'),bytes:Buffer.from(await form.get('file').arrayBuffer())});
  }
  return {state,handle,upload};
}
