import {withResetLock,requireApprovedReset,runMaintenance} from './reset-targets.mjs';
import {resolve} from 'node:path';
import {pathToFileURL} from 'node:url';

export async function resetDemo({targets,review,approval,lockPath,adapters,signal}) {
  let stage='lock';
  try {
    return await withResetLock(lockPath,async lockSignal=>{
      const active=signal?AbortSignal.any([lockSignal,signal]):lockSignal;
      const step=async(name,action)=>{stage=name;active.throwIfAborted();const result=await action();active.throwIfAborted();return result;};
      const verify=async allowPublicRepair=>{
        const observed=await step('observe',()=>adapters.observe(active));
        const host=await step('status',()=>adapters.control('status',active));
        stage='verify-identities';
        requireApprovedReset(targets,observed,review,approval,host,allowPublicRepair);
      };
      await verify(true);
      await runMaintenance({
        signal:active,
        control:action=>step(action,()=>adapters.control(action,active)),
        wipe:async()=>{
          for(const name of ['clearRabbit','clearMySql','clearMongo','clearBucket','clearRedis','clearSearch','repairAccounts'])await step(name,()=>adapters[name](active));
          await verify(false);
        },
        seed:async()=>{
          await step('bootstrap',()=>adapters.bootstrap(active));
          await verify(false);
          return step('seed',()=>adapters.seed(active));
        },
        readiness:()=>step('readiness',()=>adapters.readiness(active)),
      });
    });
  } catch {throw new Error(`Demo reset failed at ${stage}; inspect private diagnostics before recovery`);}
}

if(process.argv[1]&&import.meta.url===pathToFileURL(resolve(process.argv[1])).href){
  try{
    if(process.argv.length!==2)throw Error('Unexpected runner arguments');
    const {loadRunnerConfig}=await import('./runner-config.mjs');
    const {input,adapters}=loadRunnerConfig();
    await resetDemo({...input,adapters,lockPath:'/run/demo-reset/reset.lock'});
    console.log('Demo reset and unpaid seed completed');
  }catch{
    console.error('Demo reset failed; maintenance is not automatically reopened');process.exitCode=1;
  }
}
