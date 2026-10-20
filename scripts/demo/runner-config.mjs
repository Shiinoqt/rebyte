import assert from 'node:assert/strict';
import {lstatSync,readFileSync} from 'node:fs';
import {ResetAdapters} from './reset-adapters.mjs';

export const CONFIG_DIRECTORY='/run/secrets/demo-reset';
export function loadRunnerConfig() {
  const privateFile=path=>{
    const stat=lstatSync(path);
    assert.ok(stat.isFile()&&!stat.isSymbolicLink());
    assert.equal(stat.uid,process.getuid());
    assert.ok([0o400,0o600].includes(stat.mode&0o777));
    return readFileSync(path,'utf8');
  };
  const directory=lstatSync(CONFIG_DIRECTORY);
  assert.ok(directory.isDirectory()&&!directory.isSymbolicLink());
  assert.equal(directory.uid,process.getuid());assert.equal(directory.mode&0o777,0o700);
  const input=JSON.parse(privateFile(CONFIG_DIRECTORY+'/config.json'));
  assert.equal(input.config.ssh.keyFile,CONFIG_DIRECTORY+'/ssh-key');
  assert.equal(input.config.ssh.knownHostsFile,CONFIG_DIRECTORY+'/known_hosts');
  privateFile(input.config.ssh.keyFile);privateFile(input.config.ssh.knownHostsFile);
  return {input,adapters:new ResetAdapters(input)};
}
