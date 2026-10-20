import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import {lstatSync,writeFileSync} from 'node:fs';
import {loadRunnerConfig} from './runner-config.mjs';

try {
  assert.ok(process.argv.length===2||process.argv.length===3&&process.argv[2]==='--check');
  loadRunnerConfig();
  const heartbeat='/run/demo-reset/heartbeat';
  if(process.argv[2]==='--check') {
    const stat=lstatSync(heartbeat);assert.ok(stat.isFile()&&!stat.isSymbolicLink()&&Date.now()-stat.mtimeMs<75000);
  } else {
    for(const [binary,args] of [['aws',['--version']],['mysql',['--version']],['mongosh',['--version']],['redis-cli',['--version']],['ssh',['-V']]]){
      execFileSync(binary,args,{stdio:'pipe',timeout:15000});
    }
    const beat=()=>writeFileSync(heartbeat,'ready\n',{mode:0o600});beat();
    const timer=setInterval(beat,30000);
    for(const signal of ['SIGINT','SIGTERM'])process.once(signal,()=>{clearInterval(timer);process.exitCode=0;});
  }
}catch {
  console.error('Private demo runner configuration or health check failed');process.exitCode=1;
}
