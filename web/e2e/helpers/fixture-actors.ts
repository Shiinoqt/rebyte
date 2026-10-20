import type { Browser, BrowserContext, Page, TestInfo } from '@playwright/test';
import { monitorPage } from './assert-clean-page';
import { CognitoAdmin, loginThroughSpa, runtimeConfig, type Credentials } from './cognito-admin';
import { GatewaySeed } from './api-seed';
import { readFixtureEnv, type FixtureKey } from './fixture-env';
import type { OrderView } from '../../src/app/core/api/api-models';

type Allowed = (failure: { kind: string; url: string; status?: number; reason?: string }) => boolean;
export interface Actor { context: BrowserContext; page: Page; verify: () => void }
export interface FixtureOrders { hardwareId: string; softwareId: string; buyer: Credentials; seller: Credentials }

function required(key: FixtureKey): string {
  const value = readFixtureEnv()[key];
  if (!value) throw new Error(`Missing ${key}; run npm --prefix web run e2e:fixtures`);
  return value;
}

export function fixtureOrders(): FixtureOrders {
  return { hardwareId: required('E2E_FIXTURE_HW_PAID_ORDER_ID'), softwareId: required('E2E_FIXTURE_SW_DELIVERED_ORDER_ID'),
    buyer: { email: required('E2E_FIXTURE_BUYER_EMAIL'), password: required('E2E_FIXTURE_BUYER_PASSWORD') },
    seller: { email: required('E2E_FIXTURE_SELLER_EMAIL'), password: required('E2E_FIXTURE_SELLER_PASSWORD') } };
}

// Reads through the gateway as the buyer so tests can decide between starting and already-reached states.
export async function readOrder(id: string, credentials: Credentials): Promise<OrderView> {
  const config = runtimeConfig();
  const token = await new CognitoAdmin(config.cognito).accessToken(credentials);
  return new GatewaySeed(config.apiBaseUrl).request<OrderView>('GET', `/orders/${encodeURIComponent(id)}`, token);
}

export async function signedInActor(browser: Browser, testInfo: TestInfo, credentials: Credentials,
  extraAllowed: readonly Allowed[] = []): Promise<Actor> {
  const context = await browser.newContext({ ...testInfo.project.use, storageState: { cookies: [], origins: [] } });
  const page = await context.newPage();
  const origins = [new URL(testInfo.project.use.baseURL!).origin, new URL(runtimeConfig().apiBaseUrl).origin];
  // A full-page navigation cancels the SPA's in-flight profile refresh; that abort is not an API failure.
  const verify = monitorPage(page, origins, [f => f.kind === 'unexpected-public-authorization',
    f => f.kind === 'requestfailed' && f.reason === 'net::ERR_ABORTED',
    f => f.kind === 'console-error' && f.reason === 'websocket-rate-limited', ...extraAllowed]);
  await loginThroughSpa(page, credentials);
  return { context, page, verify };
}
