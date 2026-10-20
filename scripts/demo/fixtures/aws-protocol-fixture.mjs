import assert from 'node:assert/strict';
import {createHash,createHmac,randomUUID,timingSafeEqual} from 'node:crypto';

const sha=bytes=>createHash('sha256').update(bytes).digest('hex');
const hmac=(key,text)=>createHmac('sha256',key).update(text).digest();
const escape=value=>String(value).replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;');
const unescape=value=>value.replaceAll('&lt;','<').replaceAll('&gt;','>').replaceAll('&amp;','&');
const uri=value=>encodeURIComponent(value).replace(/[!'()*]/g,char=>'%'+char.charCodeAt(0).toString(16).toUpperCase());

// This server is isolated test infrastructure; production adapters still use the native CLI.
export function createCloudFixture({targets,accounts,tokens}) {
  const preserved=new Set(Object.values(targets.preservedSubjects));
  const state={users:[],objects:[],versioning:'Disabled',signed:0,mutations:0,operations:{},accountId:targets.aws.accountId};
  for(const [role,sub] of Object.entries(targets.preservedSubjects))state.users.push({Username:sub,role,Enabled:role==='admin',password:role==='admin'?accounts[role].password:'damaged',Attributes:[{Name:'sub',Value:sub},{Name:'email',Value:accounts[role].email},{Name:'email_verified',Value:String(role==='admin')}],groups:role==='admin'?['ADMIN']:[]});
  for(let i=0;i<121;i++){const sub=randomUUID();state.users.push({Username:sub,Enabled:true,password:'visitor',Attributes:[{Name:'sub',Value:sub}],groups:[]});}
  const refill=(versioning='Disabled')=>{
    assert.ok(['Disabled','Enabled','Suspended'].includes(versioning));state.versioning=versioning;
    state.objects=Array.from({length:2005},(_,i)=>({Key:'old/object-'+String(i).padStart(4,'0'),...(versioning==='Disabled'?{}:{VersionId:'version-'+i,marker:i%2===1})}));
  };refill();
  function signature(req,url,body){
    const auth=/^AWS4-HMAC-SHA256 Credential=([^/]+)\/([^,]+), SignedHeaders=([^,]+), Signature=([a-f0-9]{64})$/.exec(req.headers.authorization??'');
    assert.ok(auth,'signed native request required');assert.equal(auth[1],'fixture');
    const scope=auth[2].split('/');assert.equal(scope.length,4);assert.equal(scope[1],targets.aws.region);assert.ok(['s3','sts','cognito-idp'].includes(scope[2]));assert.equal(scope[3],'aws4_request');
    const date=req.headers['x-amz-date'];assert.match(date,/^\d{8}T\d{6}Z$/);assert.equal(date.slice(0,8),scope[0]);
    const names=auth[3].split(';');assert.deepEqual(names,[...names].sort());assert.ok(names.includes('host')&&names.includes('x-amz-date'));
    const headers=names.map(name=>{assert.equal(typeof req.headers[name],'string');return name+':'+req.headers[name].trim().replace(/\s+/g,' ')+'\n';}).join('');
    const payload=sha(body);if(req.headers['x-amz-content-sha256'])assert.equal(req.headers['x-amz-content-sha256'],payload);
    const query=[...url.searchParams].map(([key,value])=>[uri(key),uri(value)]).sort(([a,b],[c,d])=>a.localeCompare(c)||b.localeCompare(d)).map(pair=>pair.join('=')).join('&');
    const canonical=[req.method,url.pathname,query,headers,auth[3],payload].join('\n');
    let key=Buffer.from('AWS4fixture-secret');for(const value of scope)key=hmac(key,value);
    const actual=hmac(key,['AWS4-HMAC-SHA256',date,auth[2],sha(canonical)].join('\n'));
    assert.ok(timingSafeEqual(actual,Buffer.from(auth[4],'hex')),'native SigV4 signature mismatch');state.signed++;return scope[2];
  }
  const response=(res,data,status=200)=>{res.writeHead(status,{'Content-Type':'application/x-amz-json-1.1'});res.end(JSON.stringify(data));};
  const xml=(res,name,body)=>{res.writeHead(200,{'Content-Type':'application/xml'});res.end(`<?xml version="1.0" encoding="UTF-8"?><${name} xmlns="http://s3.amazonaws.com/doc/2006-03-01/">${body}</${name}>`);};
  const attributes=user=>({Username:user.Username,Enabled:user.Enabled,UserStatus:'CONFIRMED',UserAttributes:user.Attributes});
  const count=name=>state.operations[name]=(state.operations[name]??0)+1;
  async function handle(req,res){
    try{
      const chunks=[];for await(const chunk of req){chunks.push(chunk);assert.ok(chunks.reduce((n,c)=>n+c.length,0)<=2*1024*1024);}
      const body=Buffer.concat(chunks),url=new URL(req.url,'http://aws-fixture:8089');
      if(url.pathname==='/fixture/state')return response(res,state);
      if(url.pathname==='/fixture/control'){
        const update=JSON.parse(body);if(update.versioning)refill(update.versioning);if(update.accountId)state.accountId=update.accountId;
        return response(res,{ok:true});
      }
      if(url.pathname==='/fixture/upload'){
        const request=new Request(url,{method:'POST',headers:req.headers,body});const form=await request.formData();
        assert.equal([...form.keys()].at(-1),'file');assert.ok(form.get('file').size>0);assert.equal(form.get('x-amz-security-token'),'fixture');
        const result=await fetch('http://api-gateway:8080/fixture-upload',{method:'POST',headers:{Authorization:'Bearer '+tokens.admin,'Content-Type':'application/json'},body:JSON.stringify({key:form.get('key')})});assert.equal(result.status,200);
        res.writeHead(204);res.end();return;
      }
      const operation=String(req.headers['x-amz-target']??'').split('.').at(-1);
      if(operation==='InitiateAuth'){
        const input=JSON.parse(body);assert.equal(input.AuthFlow,'USER_PASSWORD_AUTH');assert.equal(input.ClientId,targets.aws.clientId);
        const user=state.users.find(user=>user.Attributes.some(attribute=>attribute.Name==='email'&&attribute.Value===input.AuthParameters.USERNAME));
        if(!user?.Enabled||user.password!==input.AuthParameters.PASSWORD)return response(res,{__type:'NotAuthorizedException'},400);
        return response(res,{AuthenticationResult:{AccessToken:tokens[user.role],ExpiresIn:1800,TokenType:'Bearer'}});
      }
      const service=signature(req,url,body);count(operation||service);
      if(service==='sts'){
        const data={UserId:'fixture',Account:state.accountId,Arn:`arn:aws:iam::${state.accountId}:user/fixture`};
        if(operation)return response(res,data);
        assert.equal(new URLSearchParams(body.toString()).get('Action'),'GetCallerIdentity');
        res.writeHead(200,{'Content-Type':'text/xml'});res.end(`<GetCallerIdentityResponse xmlns="https://sts.amazonaws.com/doc/2011-06-15/"><GetCallerIdentityResult><UserId>fixture</UserId><Account>${data.Account}</Account><Arn>${data.Arn}</Arn></GetCallerIdentityResult><ResponseMetadata><RequestId>fixture</RequestId></ResponseMetadata></GetCallerIdentityResponse>`);return;
      }
      if(service==='s3'){
        assert.equal(url.pathname,'/'+targets.aws.bucket);assert.equal(req.headers['x-amz-expected-bucket-owner'],targets.aws.accountId);
    if(req.method==='HEAD'){res.writeHead(200,{'x-amz-bucket-region':targets.aws.region});res.end();return;}
        if(url.searchParams.has('versioning'))return xml(res,'VersioningConfiguration',state.versioning==='Disabled'?'':`<Status>${state.versioning}</Status>`);
        if(req.method==='GET'){
          assert.equal(url.searchParams.get('max-keys'),'1000');const selected=state.objects.slice(0,1000),versioned=url.searchParams.has('versions');
          assert.equal(versioned,state.versioning!=='Disabled');
          const items=selected.map(object=>{const tag=versioned?(object.marker?'DeleteMarker':'Version'):'Contents';return `<${tag}><Key>${escape(object.Key)}</Key>${versioned?'<VersionId>'+escape(object.VersionId)+'</VersionId><IsLatest>true</IsLatest>':''}<LastModified>2026-10-20T00:00:00.000Z</LastModified>${object.marker?'':'<ETag>fixture</ETag><Size>1</Size>'}</${tag}>`;}).join('');
          return xml(res,versioned?'ListVersionsResult':'ListBucketResult',`<Name>${targets.aws.bucket}</Name><IsTruncated>${state.objects.length>1000}</IsTruncated><MaxKeys>1000</MaxKeys><KeyCount>${selected.length}</KeyCount>${items}`);
        }
        assert.equal(req.method,'POST');assert.ok(url.searchParams.has('delete'));
        const requested=[...body.toString().matchAll(/<Object>([\s\S]*?)<\/Object>/g)].map(([,text])=>({Key:unescape(text.match(/<Key>([\s\S]*?)<\/Key>/)[1]),...(text.includes('<VersionId>')?{VersionId:unescape(text.match(/<VersionId>([\s\S]*?)<\/VersionId>/)[1])}:{})}));
        assert.ok(requested.length>0&&requested.length<=1000);for(const item of requested)assert.ok(state.objects.some(object=>object.Key===item.Key&&object.VersionId===item.VersionId));
        state.objects=state.objects.filter(object=>!requested.some(item=>item.Key===object.Key&&item.VersionId===object.VersionId));state.mutations++;
        return xml(res,'DeleteResult',requested.map(item=>`<Deleted><Key>${escape(item.Key)}</Key>${item.VersionId?'<VersionId>'+escape(item.VersionId)+'</VersionId>':''}</Deleted>`).join(''));
      }
      assert.equal(service,'cognito-idp');const input=JSON.parse(body);assert.equal(input.UserPoolId,targets.aws.poolId);
      if(operation==='DescribeUserPool')return response(res,{UserPool:{Id:targets.aws.poolId,Arn:`arn:aws:cognito-idp:${targets.aws.region}:${targets.aws.accountId}:userpool/${targets.aws.poolId}`}});
      if(operation==='DescribeUserPoolClient'){assert.equal(input.ClientId,targets.aws.clientId);return response(res,{UserPoolClient:{ClientId:input.ClientId,UserPoolId:input.UserPoolId}});}
      if(operation==='ListUsers'){
        assert.equal(input.Limit,60);const offset=Number(input.PaginationToken??0);assert.ok(Number.isInteger(offset));
        return response(res,{Users:state.users.slice(offset,offset+60).map(user=>({Username:user.Username,Attributes:user.Attributes,Enabled:user.Enabled})),...(offset+60<state.users.length?{PaginationToken:String(offset+60)}:{})});
      }
      const user=state.users.find(user=>user.Username===input.Username);assert.ok(user,'unknown Cognito user');
      if(operation==='AdminGetUser')return response(res,attributes(user));
      if(operation==='AdminListGroupsForUser')return response(res,{Groups:user.groups.map(GroupName=>({GroupName}))});
      assert.notEqual(user.role,'admin','private admin mutation refused');
      switch(operation){
        case 'AdminDeleteUser':assert.ok(!preserved.has(user.Username));state.users=state.users.filter(candidate=>candidate!==user);break;
        case 'AdminEnableUser':user.Enabled=true;break;
        case 'AdminUpdateUserAttributes':assert.ok(Array.isArray(input.UserAttributes));for(const attribute of input.UserAttributes){const original=user.Attributes.find(a=>a.Name===attribute.Name);if(original)original.Value=attribute.Value;else user.Attributes.push(attribute);}break;
        case 'AdminRemoveUserFromGroup':assert.equal(typeof input.GroupName,'string');user.groups=user.groups.filter(group=>group!==input.GroupName);break;
        case 'AdminSetUserPassword':assert.equal(input.Permanent,true);assert.equal(typeof input.Password,'string');user.password=input.Password;break;
        case 'AdminUserGlobalSignOut':break;
        default:throw Error('unexpected native operation');
      }
      state.mutations++;return response(res,{});
    }catch(error){state.lastError=error.message;response(res,{__type:'AccessDeniedException',message:'Disposable protocol/auth/schema refused'},403);}
  }
  return {state,handle};
}
