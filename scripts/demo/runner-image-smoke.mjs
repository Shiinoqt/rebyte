import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {cpSync,mkdirSync,readFileSync,writeFileSync} from 'node:fs';
import {join} from 'node:path';
import {setTimeout as delay} from 'node:timers/promises';
import {stageRunnerContext,validateRunnerContext} from './runner-context.mjs';
import {createSupervisorFixture,docker} from './fixtures/supervisor-fixture.mjs';
import {createRunnerHost} from './fixtures/runner-host-fixture.mjs';

const checks=[];
const pass=name=>{checks.push(name);console.log('PASS '+name);};
const result=(args,options={})=>spawnSync('docker',args,{encoding:'utf8',windowsHide:true,timeout:900000,maxBuffer:16*1024*1024,...options});
let fixture,host;
try{
  docker('version','--format','{{.Server.Version}}');
  fixture=await createSupervisorFixture({seedApi:true,realStores:true,nativeWatch:true});
  const context=join(fixture.root,'runner-context');const contextHash=stageRunnerContext(process.cwd(),context);validateRunnerContext(context);
  const image='rebyte-demo-runner:proof-'+contextHash.slice(0,20);
  const build=result(['buildx','build','--platform','linux/amd64','--provenance=false','--load','--file',join(context,'Dockerfile'),'--tag',image,context]);
  assert.equal(build.status,0,'actual final runner build failed: '+build.stderr.slice(-2000));
  const imageId=docker('image','inspect',image,'--format','{{.Id}}');pass('final amd64 image builds from the exact allowlisted CI context');
  const wait=async(container,action,timeout=120000)=>{
    const deadline=Date.now()+timeout;
    for(;;){try{await action();return;}catch(error){
      const state=JSON.parse(docker('inspect',container,'--format','{{json .State}}'));
      assert.ok(state.Running,`Readiness fixture exited: ${container}; exit=${state.ExitCode}; oom=${state.OOMKilled}`);
      if(Date.now()>deadline)throw error;
      await delay(300);
    }}
  };
  const exited=fixture.project+'-readiness-exited';
  docker('run','-d','--name',exited,image,'node','-e','process.exit(17)');fixture.containers.push(exited);
  assert.equal(docker('wait',exited),'17');
  let attempts=0;
  await assert.rejects(wait(exited,()=>{attempts++;throw new Error('not ready');},1000),/Readiness fixture exited:.*exit=17; oom=false/);
  assert.equal(attempts,1);pass('exited fixture readiness reports exit state without polling until deadline');
  host=await createRunnerHost(fixture,image);
  for(const name of ['runner-native-probe.mjs','reset-fixture.mjs'])cpSync(join('scripts/demo/fixtures',name),join(host.data,name));
  const mongo=fixture.project+'-mongo-proof',mongoVolume=fixture.project+'-mongo-data';
  docker('volume','create',mongoVolume);fixture.volumes.push(mongoVolume);
  docker('run','-d','--name',mongo,'--network',fixture.backend,'--network-alias','mongo-demo','--memory','512m','--env','MONGO_INITDB_ROOT_USERNAME=root','--env','MONGO_INITDB_ROOT_PASSWORD=fixture','--mount',`type=volume,src=${mongoVolume},dst=/data/db`,'mongo@sha256:1f995ad6fdb93244a1addab1b58f934a0bc2f5643c38e02f5e9d7f0c7d227a7b');fixture.containers.push(mongo);
  await wait(fixture.actorIds['mysql-app'],()=>docker('exec','--env','MYSQL_PWD=fixture',fixture.actorIds['mysql-app'],'mysql','--host=mysql-app','--user=root','--execute','SELECT 1;'));
  await wait(mongo,()=>docker('exec',mongo,'mongosh','--quiet','--username','root','--password','fixture','--authenticationDatabase','admin','--eval','db.runCommand({ping:1})'));
  docker('rm','-f',fixture.actorIds['demo-runner']);
  const runner=fixture.project+'-demo-runner';
  const mounts=['--mount',`type=bind,src=${host.mountpoint}/client,dst=/run/secrets/demo-reset,readonly`,'--mount',`type=bind,src=${host.data},dst=/harness,readonly`];
  docker('run','-d','--name',runner,'--network',fixture.backend,'--network-alias','demo-runner','--memory','768m','--cpus','0.50','--read-only','--cap-drop','ALL','--security-opt','no-new-privileges:true','--tmpfs','/tmp:rw,noexec,nosuid,size=64m,mode=1777','--tmpfs','/run/demo-reset:rw,noexec,nosuid,size=1m,uid=10001,gid=10001,mode=0700','--env','NODE_EXTRA_CA_CERTS=/run/secrets/demo-reset/ca.pem','--label',`com.docker.compose.project=${fixture.project}`,'--label','com.docker.compose.service=demo-runner',...mounts,image);
  fixture.actorIds['demo-runner']=docker('inspect',runner,'--format','{{.Id}}');
  await wait(runner,()=>{assert.equal(docker('inspect',runner,'--format','{{.State.Health.Status}}'),'healthy');});
  const inspect=JSON.parse(docker('inspect',runner))[0];assert.equal(inspect.Config.User,'10001:10001');assert.equal(inspect.HostConfig.ReadonlyRootfs,true);assert.deepEqual(inspect.HostConfig.PortBindings,{});assert.ok(inspect.Mounts.every(mount=>mount.Destination!=='/var/run/docker.sock'));pass('private nonroot running target validates protected config and has no host ports or daemon socket');
  const probe=(mode,ok=true)=>{
    const output=result(['exec',runner,'node','/harness/runner-native-probe.mjs',mode]);
    if(ok)assert.equal(output.status,0,mode+': '+output.stderr);else assert.notEqual(output.status,0,mode+' unexpectedly succeeded');
    if(output.stdout.trim())console.log(output.stdout.trim());return output;
  };
  const cloud=()=>JSON.parse(docker('exec',runner,'node','--input-type=module','-e',"console.log(JSON.stringify(await(await fetch('http://aws-fixture:8089/fixture/state')).json()))"));
probe('private-input');pass('native private input has owner-only permissions and is removed on every process exit');
probe('initialize');pass('installed native MySQL, mongosh, Redis clients and authenticated broker/search APIs initialize real disposable stores');
  const oldMutations=cloud().mutations;
  const missingConfig=result(['run','--rm','--network',fixture.backend,'--read-only',image,'node','/app/scripts/demo/reset.mjs']);assert.notEqual(missingConfig.status,0);
  const missingTool=result(['run','--rm','--network',fixture.backend,'--read-only','--env','PATH=/missing',...mounts,image,'/usr/local/bin/node','/app/scripts/demo/runner.mjs']);assert.notEqual(missingTool.status,0);pass('missing private config and installed binary fail without destructive work');
  const oldSigned=cloud().signed;
  host.populate(`import{readFileSync,writeFileSync,chmodSync,chownSync}from'node:fs';const root=${JSON.stringify(host.mountpoint)},path=root+'/client/config.json',bytes=readFileSync(path);writeFileSync(root+'/control/saved_config',bytes);const input=JSON.parse(bytes);input.approval.fingerprint='0'.repeat(64);writeFileSync(path,JSON.stringify(input));chmodSync(path,0o400);chownSync(path,10001,10001);`);
  probe('reset',false);assert.equal(cloud().signed,oldSigned);assert.equal(cloud().mutations,oldMutations);
  host.populate(`import{readFileSync,writeFileSync,chmodSync,chownSync}from'node:fs';const root=${JSON.stringify(host.mountpoint)},path=root+'/client/config.json';writeFileSync(path,readFileSync(root+'/control/saved_config'));chmodSync(path,0o400);chownSync(path,10001,10001);`);pass('approval mismatch refuses before any native cloud request or mutation');
  probe('wrong-cloud-auth',false);assert.equal(cloud().mutations,oldMutations);assert.match(cloud().lastError,/SigV4 signature mismatch/);pass('native signed AWS CLI authentication failure refuses mutation');
  probe('arbitrary-ssh');pass('native SSH client cannot escape the real forced-command server');
  probe('observe');probe('foreign-table');assert.match(probe('reset',false).stderr,/failed at observe/);assert.equal(cloud().mutations,oldMutations);probe('remove-foreign-table');pass('foreign schema is rejected before any wipe or maintenance change');
  const corruptHostKey=`import{readFileSync,writeFileSync,chmodSync,chownSync}from'node:fs';const root=${JSON.stringify(host.mountpoint)};const path=root+'/client/known_hosts';const correct=readFileSync(path,'utf8');writeFileSync(root+'/control/saved_known_hosts',correct);writeFileSync(path,'supervisor-fixture '+readFileSync(root+'/control/client-key.pub','utf8'));chmodSync(path,0o400);chownSync(path,10001,10001);`;
host.populate(corruptHostKey);assert.match(probe('reset',false).stderr,/failed at status/);assert.equal(cloud().mutations,oldMutations);
  host.populate(`import{readFileSync,writeFileSync,chmodSync,chownSync}from'node:fs';const root=${JSON.stringify(host.mountpoint)},path=root+'/client/known_hosts';writeFileSync(path,readFileSync(root+'/control/saved_known_hosts'));chmodSync(path,0o400);chownSync(path,10001,10001);`);pass('strict native SSH host-key verification refuses transport before mutation');
  probe('reset');probe('inspect');
  assert.equal((await fixture.publicRequest('/users/me',{token:fixture.tokens.buyer})).status,200);pass('complete native adapter reset preserves identities/indexes and reopens only after private unpaid seed readiness');
  probe('versioned');pass('native AWS CLI clears paginated versions and delete markers for enabled and suspended buckets');
  writeFileSync(join(host.data,'failure.json'),JSON.stringify({seed:true}));assert.match(probe('reset',false).stderr,/failed at seed/);
  assert.equal((await fixture.publicRequest('/users/me',{token:fixture.tokens.buyer})).status,503);assert.equal((await fixture.publicRequest('/payments/webhook',{method:'POST'})).status,503);pass('seed failure retains real maintenance for visitors and provider callbacks');
  writeFileSync(join(host.data,'failure.json'),JSON.stringify({readiness:true}));assert.match(probe('reset',false).stderr,/failed at readiness/);
  assert.equal((await fixture.publicRequest('/users/me',{token:fixture.tokens.buyer})).status,503);assert.equal((await fixture.publicRequest('/payments/webhook',{method:'POST'})).status,503);pass('readiness timeout retains real maintenance');
  const report={assertions:checks.length,checks,imageId,recipeContextSha256:contextHash,platform:'linux/amd64',cloudSignedRequests:cloud().signed};
  assert.ok(report.assertions>=12&&report.cloudSignedRequests>=150);
  mkdirSync('target/demo-runner-smoke',{recursive:true});writeFileSync('target/demo-runner-smoke/report.json',JSON.stringify(report,null,2));
  console.log('PASS '+checks.length+' final-image behavioral gates; '+imageId+'; context '+contextHash);
}catch(error){
  console.error('Final runner image smoke failed: '+error.message);process.exitCode=1;
}finally{
  if(host)await host.cleanup();else if(fixture){try{docker('rm','-f',fixture.project+'-proxy');}catch{}await fixture.cleanup();}
}
