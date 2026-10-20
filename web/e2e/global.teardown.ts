import { readFile, rm } from 'node:fs/promises';
import { resolve } from 'node:path';
import { test as teardown } from '@playwright/test';
import { CognitoAdmin, runDirectory, runtimeConfig, shouldDeleteUser } from './helpers/cognito-admin';

teardown('delete only current-run Cognito users and role states', async () => {
  const runId = process.env['E2E_RUN_ID']!;
  const directory = runDirectory(runId);
  let run: { runId: string; emails: string[] };
  try { run = JSON.parse(await readFile(resolve(directory, 'users.json'), 'utf8')); }
  catch (error) { if ((error as NodeJS.ErrnoException).code === 'ENOENT') return; throw new Error('Invalid e2e user cleanup manifest'); }
  if (run.runId !== runId || !Array.isArray(run.emails)) throw new Error('Mismatched e2e cleanup run');
  const admin = new CognitoAdmin(runtimeConfig().cognito);
  let failures = 0;
  for (const email of run.emails) {
    if (!shouldDeleteUser(email, runId)) continue;
    try { admin.deleteUser(email); } catch { failures++; }
  }
  if (failures) throw new Error(`Cognito cleanup failed for ${failures} current-run users; manifest retained for retry`);
  await rm(directory, { recursive: true, force: true });
});
