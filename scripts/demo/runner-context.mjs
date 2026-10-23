import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {cpSync,lstatSync,mkdirSync,readFileSync,readdirSync} from 'node:fs';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';

export const RUNNER_MODULES=['reset.mjs','reset-targets.mjs','reset-adapters.mjs','seed.mjs','listings.json','runner-config.mjs','runner.mjs','demo-supervisor.sh'];
const sourceRoot=fileURLToPath(new URL('../../',import.meta.url));
function filesWithin(directory,prefix=''){
  assert.ok(lstatSync(directory).isDirectory()&&!lstatSync(directory).isSymbolicLink());
  return readdirSync(directory).sort().flatMap(name=>{
    const path=join(directory,name),relative=prefix+name,stat=lstatSync(path);assert.ok(!stat.isSymbolicLink());
    if(stat.isDirectory())return filesWithin(path,relative+'/');assert.ok(stat.isFile());return [relative];
  });
}
export function validateRunnerContext(directory){
  for(const path of [directory,join(directory,'scripts'),join(directory,'scripts/demo'),join(directory,'scripts/demo/assets')])assert.ok(lstatSync(path).isDirectory()&&!lstatSync(path).isSymbolicLink());
  assert.deepEqual(readdirSync(directory).sort(),['Dockerfile','scripts']);
  assert.deepEqual(readdirSync(join(directory,'scripts')),['demo']);
  assert.deepEqual(readdirSync(join(directory,'scripts/demo')).sort(),[...RUNNER_MODULES,'assets'].sort());
  const assetFiles=filesWithin(join(directory,'scripts/demo/assets'));assert.deepEqual(assetFiles,filesWithin(join(sourceRoot,'scripts/demo/assets')));
  for(const [target,original] of [['Dockerfile','infra/Dockerfile.demo-runner'],...RUNNER_MODULES.map(name=>['scripts/demo/'+name,'scripts/demo/'+name]),...assetFiles.map(name=>['scripts/demo/assets/'+name,'scripts/demo/assets/'+name])]){
    const path=join(directory,target);assert.ok(lstatSync(path).isFile()&&!lstatSync(path).isSymbolicLink());
    assert.deepEqual(readFileSync(path),readFileSync(join(sourceRoot,original)),'runner artifacts match the checked-out tested source');
  }
  runnerContextFingerprint(directory);return true;
}
export function stageRunnerContext(root,target){
  mkdirSync(join(target,'scripts/demo'),{recursive:true});
  cpSync(join(root,'infra/Dockerfile.demo-runner'),join(target,'Dockerfile'));
  for(const name of RUNNER_MODULES)cpSync(join(root,'scripts/demo',name),join(target,'scripts/demo',name));
  cpSync(join(root,'scripts/demo/assets'),join(target,'scripts/demo/assets'),{recursive:true});
  return runnerContextFingerprint(target);
}
export function runnerContextFingerprint(root){
  const hash=createHash('sha256');
  const walk=(directory,prefix='')=>{
    for(const name of readdirSync(directory).sort()){
      const path=join(directory,name),relative=prefix+name,stat=lstatSync(path);
      assert.ok(!stat.isSymbolicLink());
      if(stat.isDirectory())walk(path,relative+'/');else{assert.ok(stat.isFile());hash.update(relative+'\0');hash.update(readFileSync(path));hash.update('\0');}
    }
  };walk(root);return hash.digest('hex');
}
