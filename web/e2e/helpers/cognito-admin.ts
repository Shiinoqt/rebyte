import { spawnSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import type { Page } from '@playwright/test';

export type Role = 'buyer' | 'seller' | 'admin';
export interface Credentials { email: string; password: string }
export interface CognitoConfig { region: string; userPoolId: string; userPoolClientId: string }

export function runDirectory(runId: string): string {
  if (!/^[a-zA-Z0-9-]+$/.test(runId)) throw new Error('Invalid e2e run identifier');
  return resolve(__dirname, '../../playwright/.auth', runId);
}

export function runtimeConfig(): { apiBaseUrl: string; cognito: CognitoConfig } {
  const config = JSON.parse(readFileSync(resolve(__dirname, '../../public/config.json'), 'utf8'));
  return { apiBaseUrl: config.apiBaseUrl, cognito: {
    region: process.env['AWS_REGION'] ?? config.cognito.region,
    userPoolId: process.env['COGNITO_USER_POOL_ID'] ?? config.cognito.userPoolId,
    userPoolClientId: process.env['COGNITO_USER_POOL_CLIENT_ID'] ?? config.cognito.userPoolClientId,
  } };
}

export function awsArguments(operation: string, payload: object, region: string, profile?: string): string[] {
  return ['cognito-idp', operation, '--region', region, '--cli-input-json', JSON.stringify(payload),
    '--output', 'json', '--no-cli-pager', ...(profile ? ['--profile', profile] : [])];
}

export function awsEnvironment(profile?: string): NodeJS.ProcessEnv {
  const env = { ...process.env };
  if (profile) {
    delete env['AWS_ACCESS_KEY_ID'];
    delete env['AWS_SECRET_ACCESS_KEY'];
    delete env['AWS_SESSION_TOKEN'];
  }
  return env;
}

export function shouldDeleteUser(email: string, runId: string): boolean {
  return /^[a-zA-Z0-9-]+$/.test(runId) && !email.startsWith('e2e-fixture') &&
    (['buyer', 'seller', 'admin', 'signup'] as const).some(role => email === `e2e-${runId}-${role}@example.com`);
}

export function awsFailure(operation: string, status: number | null): Error {
  return new Error(`Cognito ${operation} failed (exit ${status ?? 'unavailable'})`);
}

export function newCredentials(email: string): Credentials {
  return { email, password: `E2e!${randomBytes(24).toString('base64url')}9a` };
}

export class CognitoAdmin {
  constructor(readonly config: CognitoConfig, readonly profile = process.env['AWS_PROFILE']) {}

  private execute(operation: string, payload: object): void {
    const result = spawnSync('aws', awsArguments(operation, payload, this.config.region, this.profile), {
      shell: false, encoding: 'utf8', env: awsEnvironment(this.profile), timeout: 30_000,
    });
    // AWS diagnostics may echo the JSON input, including a password.
    if (result.error || result.status !== 0) throw awsFailure(operation, result.status);
  }

  createUser(credentials: Credentials, role: Role): void {
    this.execute('admin-create-user', { UserPoolId: this.config.userPoolId,
      Username: credentials.email, MessageAction: 'SUPPRESS',
      TemporaryPassword: credentials.password, UserAttributes: [
        { Name: 'email', Value: credentials.email }, { Name: 'email_verified', Value: 'true' },
        { Name: 'preferred_username', Value: `e2e_${role}_${randomBytes(6).toString('hex')}` },
      ] });
    this.execute('admin-set-user-password', { UserPoolId: this.config.userPoolId,
      Username: credentials.email, Password: credentials.password, Permanent: true });
    if (role === 'admin') this.execute('admin-add-user-to-group', {
      UserPoolId: this.config.userPoolId, Username: credentials.email, GroupName: 'ADMIN',
    });
  }

  // Only the dedicated signup scenario uses this: it replaces the emailed code the test cannot read.
  confirmSignUp(email: string): void {
    this.execute('admin-confirm-sign-up', { UserPoolId: this.config.userPoolId, Username: email });
  }

  deleteUser(email: string): void {
    this.execute('admin-delete-user', { UserPoolId: this.config.userPoolId, Username: email });
  }

  async accessToken(credentials: Credentials): Promise<string> {
    let response: Response;
    try {
      response = await fetch(`https://cognito-idp.${this.config.region}.amazonaws.com/`, {
        method: 'POST', headers: { 'Content-Type': 'application/x-amz-json-1.1',
          'X-Amz-Target': 'AWSCognitoIdentityProviderService.InitiateAuth' },
        body: JSON.stringify({ AuthFlow: 'USER_PASSWORD_AUTH', ClientId: this.config.userPoolClientId,
          AuthParameters: { USERNAME: credentials.email, PASSWORD: credentials.password } }),
        signal: AbortSignal.timeout(30_000),
      });
      const body = await response.json();
      if (response.ok && typeof body.AuthenticationResult?.AccessToken === 'string') {
        return body.AuthenticationResult.AccessToken;
      }
    } catch { throw new Error('Cognito sign-in request failed'); }
    throw new Error(`Cognito sign-in failed (HTTP ${response.status})`);
  }
}

export async function loginThroughSpa(page: Page, credentials: Credentials): Promise<void> {
  try {
    await page.goto('/login?returnUrl=%2F');
    await page.locator('#auth-email').fill(credentials.email);
    await page.locator('#auth-password').fill(credentials.password);
    await page.getByRole('button', { name: 'Log in', exact: true }).click();
    await page.waitForURL(url => url.pathname === '/', { timeout: 30_000 });
  } catch { throw new Error('SPA role login failed; check Cognito and gateway configuration'); }
}
