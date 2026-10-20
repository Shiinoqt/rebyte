import { mkdir, writeFile } from 'node:fs/promises';
import { resolve } from 'node:path';
import { test as setup } from '@playwright/test';
import { CognitoAdmin, loginThroughSpa, newCredentials, runDirectory, runtimeConfig, type Role } from './helpers/cognito-admin';
import { GatewaySeed } from './helpers/api-seed';
import { serializeSeed } from './helpers/test-data';

setup('create real Cognito role sessions', async ({ browser, baseURL }) => {
  const runId = process.env['E2E_RUN_ID']!;
  const directory = runDirectory(runId);
  await mkdir(directory, { recursive: true, mode: 0o700 });
  const admin = new CognitoAdmin(runtimeConfig().cognito);
  const emails: string[] = [];
  const tokens = {} as Record<Role, string>;
  for (const role of ['buyer', 'seller', 'admin'] as Role[]) {
    const credentials = newCredentials(`e2e-${runId}-${role}@example.com`);
    emails.push(credentials.email);
    await writeFile(resolve(directory, 'users.json'), JSON.stringify({ runId, emails }), { mode: 0o600 });
    admin.createUser(credentials, role);
    tokens[role] = await admin.accessToken(credentials);
    const context = await browser.newContext({ baseURL });
    try {
      await loginThroughSpa(await context.newPage(), credentials);
      await context.storageState({ path: resolve(directory, `${role}.json`) });
    } finally { await context.close(); }
  }
  const seed = await new GatewaySeed(runtimeConfig().apiBaseUrl).seed(runId, tokens.admin, tokens.seller);
  const publicFixtures = await new GatewaySeed(runtimeConfig().apiBaseUrl).publicFixtures(seed, tokens.admin, tokens.seller);
  await writeFile(resolve(directory, 'seed.json'), serializeSeed({ ...seed, publicFixtures, storageStates: {
    buyer: resolve(directory, 'buyer.json'), seller: resolve(directory, 'seller.json'), admin: resolve(directory, 'admin.json'),
  } }), { mode: 0o600 });
});
