import {test,expect,type Page,type BrowserContext} from '@playwright/test';
import assert from 'node:assert/strict';
import {readFileSync,lstatSync} from 'node:fs';
import {resolve} from 'node:path';
import {randomUUID} from 'node:crypto';
import {assertNoOverflow,monitorPage} from './helpers/assert-clean-page';
import {traceSocket} from './helpers/two-session';

const WEB='https://rebyte.tech',API='https://api.rebyte.tech';
const expectedConfig=JSON.parse(readFileSync(resolve(__dirname,'../public/config.demo.json'),'utf8'));
const roles=['buyer','seller','developer','admin'] as const;
const uuid=/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/;
type Role=typeof roles[number];
interface AcceptanceConfig {
  accounts: Record<Role,{email:string;password:string}>;
  subjects: Record<Role,string>;
  hardwareListingId:string;
  softwareListingId:string;
}
function privateConfig():AcceptanceConfig {
  try {
    const path=process.env['DEMO_ACCEPTANCE_FILE'];assert.ok(path);
    const stat=lstatSync(path);assert.ok(stat.isFile()&&!stat.isSymbolicLink());
    if(process.getuid){assert.equal(stat.uid,process.getuid());assert.equal(stat.mode&0o077,0);}
    const config=JSON.parse(readFileSync(path,'utf8')) as AcceptanceConfig;
    for(const role of roles){assert.ok(config.accounts[role].email&&config.accounts[role].password);assert.match(config.subjects[role],/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/);}
    assert.equal(new Set(roles.map(role=>config.subjects[role])).size,4);
    assert.equal(new Set(roles.map(role=>config.accounts[role].email)).size,4);
    assert.match(config.hardwareListingId,uuid);assert.match(config.softwareListingId,uuid);
    return config;
  }catch{throw Error('Private demo acceptance configuration is missing or invalid');}
}
async function runtimeConfig(page:Page):Promise<void>{
  const response=await page.request.get(WEB+'/config.json',{maxRedirects:0});
  expect(response.status()).toBe(200);expect(response.url()).toBe(WEB+'/config.json');
  expect(response.headers()['cache-control']).toContain('no-store');
  expect(await response.json()).toEqual(expectedConfig);
}
const monitor=(page:Page)=>monitorPage(page,[WEB,API],[
  failure=>failure.kind==='unexpected-public-authorization',
  failure=>failure.kind==='requestfailed'&&failure.reason==='net::ERR_ABORTED',
  failure=>failure.kind==='console-error'&&['websocket-rate-limited','conflict-response'].includes(failure.reason??''),
  failure=>failure.kind==='http'&&failure.status===409&&failure.url.endsWith('/conversations'),
]);
async function login(page:Page,role:Role,config:AcceptanceConfig):Promise<string>{
  try{
    await runtimeConfig(page);await page.goto('/login');
    expect(new URL(page.url()).origin).toBe(WEB);
    for(const [selector,value] of [['#auth-email',config.accounts[role].email],['#auth-password',config.accounts[role].password]]){
      await page.locator(selector).evaluate((element,secret)=>{
        const input=element as HTMLInputElement;
        Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value')!.set!.call(input,secret);
        input.dispatchEvent(new Event('input',{bubbles:true}));
      },value);
    }
    const [response]=await Promise.all([
      page.waitForResponse(response=>response.url()===API+'/users/me'&&response.request().method()==='GET'&&response.status()===200),
      page.getByRole('button',{name:'Log in',exact:true}).click(),
    ]);
    const authorization=response.request().headers()['authorization']??'';
    assert.ok(authorization.startsWith('Bearer '));const token=authorization.slice(7);
    const claims=JSON.parse(Buffer.from(token.split('.')[1],'base64url').toString('utf8'));
    const bound=claims.sub===config.subjects[role]&&claims.token_use==='access'&&claims.client_id===expectedConfig.cognito.userPoolClientId&&claims.iss==='https://cognito-idp.eu-south-1.amazonaws.com/'+expectedConfig.cognito.userPoolId&&claims.exp>Date.now()/1000&&Boolean(claims['cognito:groups']?.includes('ADMIN'))===(role==='admin');
    expect(bound,'Preserved subject, issuer and role binding').toBe(true);
    await expect(page).not.toHaveURL(/\/login(?:\?|$)/);return token;
  }catch{throw Error('Preserved demo login failed for '+role);}
}

for(const viewport of [{name:'desktop',width:1280,height:800},{name:'phone',width:412,height:839}]){
  test('public configured demo survives deep links on '+viewport.name,async({page})=>{
    await page.setViewportSize(viewport);const verify=monitor(page);await runtimeConfig(page);
    await page.goto('/');await expect(page.getByRole('link',{name:'Log in',exact:true})).toBeVisible();
    const cards=page.getByRole('main').getByRole('article');await expect.poll(()=>cards.count()).toBeGreaterThanOrEqual(4);
    await assertNoOverflow(page);await cards.first().locator('a[href^="/listings/"]').first().click();
    await expect(page).toHaveURL(url=>url.origin===WEB&&/^\/listings\/[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(url.pathname));
    const destination=page.url();await page.reload();await expect(page).toHaveURL(destination);
    await assertNoOverflow(page);verify();
  });
}
for(const role of roles)test('preserved '+role+' authenticates through the deployed SPA',async({page})=>{
  const verify=monitor(page);const token=await login(page,role,privateConfig());
  const result=await page.request.get(API+'/users/me',{headers:{Authorization:'Bearer '+token},maxRedirects:0});
  expect(result.status()).toBe(200);verify();
});

test('real gateway WebSocket delivers the exact new buyer message to seller',async({browser})=>{
  assert.equal(process.env['DEMO_ALLOW_MUTATIONS'],'true','Explicit demo mutation approval required');
  const config=privateConfig();const contexts:BrowserContext[]=[];
  try{
    const sessions:Array<{context:BrowserContext;page:Page;verify:()=>void;trace:Awaited<ReturnType<typeof traceSocket>>;token:string}>=[];
    for(const role of ['buyer','seller'] as const){
      const context=await browser.newContext({baseURL:WEB,ignoreHTTPSErrors:false,storageState:{cookies:[],origins:[]}});contexts.push(context);
      const page=await context.newPage(),verify=monitor(page),trace=await traceSocket(page);
      const token=await login(page,role,config);sessions.push({context,page,verify,trace,token});
    }
    const [buyer,seller]=sessions;
    await expect.poll(()=>sessions.every(session=>session.trace.connected>0&&session.trace.bearerConnects>0)).toBe(true);
    const start=await buyer.context.request.post(API+'/conversations',{headers:{Authorization:'Bearer '+buyer.token},data:{listingId:config.hardwareListingId,text:'Demo live WebSocket acceptance'},maxRedirects:0});
    expect([201,409]).toContain(start.status());const conversation=await start.json();
    const id=start.status()===201?conversation.id:conversation.conversationId;assert.equal(typeof id,'string');
    const send=await buyer.context.request.post(API+'/conversations/'+id+'/messages',{headers:{Authorization:'Bearer '+buyer.token},data:{text:'Demo live delivery '+randomUUID()},maxRedirects:0});
    expect(send.status()).toBe(201);const message=await send.json();assert.equal(typeof message.id,'string');
    await expect.poll(()=>seller.trace.received.some(frame=>frame.queue==='messages'&&JSON.stringify(frame.body).includes(message.id)),{timeout:30_000}).toBe(true);
    buyer.verify();seller.verify();
  }finally{await Promise.all(contexts.map(context=>context.close()));}
});

test('guest-card sandbox checkout returns and reaches signed-webhook PAID state',async({page})=>{
  test.setTimeout(780_000);
  assert.equal(process.env['DEMO_ALLOW_MUTATIONS'],'true','Explicit demo mutation approval required');
  assert.equal(process.env['DEMO_ALLOW_PAYMENTS'],'true','Explicit sandbox payment approval required');
  assert.equal(process.env['DEMO_CARD_HANDOFF'],'true','Visible human sandbox card handoff required');
  const config=privateConfig(),token=await login(page,'buyer',config);let orderId='',captured=false;
  page.on('request',request=>{const url=new URL(request.url());if(request.method()==='POST'&&url.origin===API&&url.pathname==='/payments/'+orderId+'/capture')captured=true;});
  await page.goto('/checkout/'+config.softwareListingId);
  const [order]=await Promise.all([
    page.waitForResponse(response=>response.url()===API+'/orders'&&response.request().method()==='POST').then(async response=>{assert.equal(response.status(),201);return response.json();}),
    page.getByRole('button',{name:/^Pay with PayPal/}).click(),
  ]);
  assert.equal(order.status,'AWAITING_PAYMENT');assert.match(order.orderId,/^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/);orderId=order.orderId;
  const approval=new URL(order.approvalUrl);assert.equal(approval.origin,'https://www.sandbox.paypal.com');assert.equal(approval.pathname,'/checkoutnow');assert.ok(approval.searchParams.get('token'));
  await page.waitForURL(url=>url.origin==='https://www.sandbox.paypal.com');
  const guest=page.getByRole('button',{name:/pay with (?:(?:a |debit|credit).*)?card/i}).or(page.getByRole('link',{name:/pay with (?:(?:a |debit|credit).*)?card/i})).first();
  await expect(guest,'Sandbox guest-card option must be available').toBeVisible();await guest.click();
  console.log('Guest-card handoff ready in the visible sandbox browser; complete the authorized test card.');
  await page.waitForURL(url=>url.origin===WEB&&url.pathname==='/payment/return',{timeout:600_000});
  await expect.poll(()=>captured,{timeout:30_000}).toBe(true);
  await expect.poll(async()=>{
    const result=await page.request.get(API+'/orders/'+orderId,{headers:{Authorization:'Bearer '+token},maxRedirects:0});
    assert.equal(result.status(),200);return (await result.json()).status==='PAID';
  },{timeout:90_000}).toBe(true);
});
