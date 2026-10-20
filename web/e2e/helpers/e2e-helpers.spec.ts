import { afterEach, describe, expect, it, vi } from 'vitest';
import { awsArguments, awsEnvironment, awsFailure, runDirectory, shouldDeleteUser } from './cognito-admin';
import { GatewaySeed, hardwareRequest, softwareRequest, poll, waitForOrder } from './api-seed';
import { serializeSeed, type SeedData } from './test-data';
import { parseFixtureEnv, updateFixtureEnv, type FixtureEnv } from './fixture-env';
import { EventEmitter } from 'node:events';
import type { Page } from '@playwright/test';
import { monitorPage } from './assert-clean-page';
import { jpegFixture } from './upload-fixtures';
import { assertFreshFixtures, assertOpenProblems } from './fixture-state';
import { isBearerConnect, lastTypingTtl, parseStompMessage, presenceTransitions } from './two-session';

afterEach(() => { vi.unstubAllEnvs(); vi.unstubAllGlobals(); vi.useRealTimers(); });

describe('public page evidence', () => {
  it('fails on gateway HTTP errors as well as browser errors without echoing query secrets', () => {
    const page = new EventEmitter();
    const verify = monitorPage(page as unknown as Page, ['http://localhost:4200', 'http://localhost:8080']);
    page.emit('response', { url: () => 'http://localhost:8080/search?token=sensitive', status: () => 503 });
    expect(verify).toThrow();
  });

  it('fails if an anonymous page sends authorization without exposing header or query values', () => {
    const page = new EventEmitter();
    const verify = monitorPage(page as unknown as Page, ['http://localhost:8080']);
    page.emit('request', { url: () => 'http://localhost:8080/search?token=sensitive',
      headers: () => ({ authorization: 'Bearer sensitive' }) });
    try { verify(); throw new Error('Expected anonymous authorization failure'); }
    catch (error) {
      expect(String(error)).toContain('unexpected-public-authorization');
      expect(String(error)).not.toContain('sensitive');
    }
  });

  it.each(['pageerror', 'console', 'requestfailed'])('fails on unexpected %s evidence', event => {
    const page = Object.assign(new EventEmitter(), { url: () => 'http://localhost:4200/' });
    const verify = monitorPage(page as unknown as Page, ['http://localhost:4200']);
    const value = event === 'console' ? { type: () => 'error' } :
      event === 'requestfailed' ? { url: () => 'http://localhost:4200/config.json' } : new Error('test-only error');
    page.emit(event, value);
    expect(verify).toThrow();
  });
});

describe('Cognito CLI safety', () => {
  it('rejects traversal and absolute run directories before cleanup', () => {
    for (const runId of ['../outside', '/outside', 'C:\\outside', '', '..']) {
      expect(() => runDirectory(runId)).toThrow('Invalid e2e run identifier');
    }
    expect(runDirectory('run-123').replaceAll('\\', '/')).toMatch(/\/playwright\/\.auth\/run-123$/);
  });
  it('constructs structured JSON arguments without shell interpolation', () => {
    const payload = { Password: 'test-only-$(echo unsafe);value' };
    expect(awsArguments('admin-set-user-password', payload, 'eu-south-1', 'rebyte')).toEqual([
      'cognito-idp', 'admin-set-user-password', '--region', 'eu-south-1',
      '--cli-input-json', JSON.stringify(payload), '--output', 'json', '--no-cli-pager',
      '--profile', 'rebyte',
    ]);
  });

  it('redacts AWS errors by retaining only operation and exit status', () => {
    expect(awsFailure('admin-create-user', 255).message).toBe('Cognito admin-create-user failed (exit 255)');
  });

  it('lets an explicit AWS profile take precedence over inherited credential keys', () => {
    vi.stubEnv('AWS_ACCESS_KEY_ID', 'test-only-key');
    vi.stubEnv('AWS_SECRET_ACCESS_KEY', 'test-only-secret');
    vi.stubEnv('AWS_SESSION_TOKEN', 'test-only-session');
    expect(awsEnvironment('rebyte')['AWS_ACCESS_KEY_ID']).toBeUndefined();
    expect(awsEnvironment('rebyte')['AWS_SECRET_ACCESS_KEY']).toBeUndefined();
    expect(awsEnvironment('rebyte')['AWS_SESSION_TOKEN']).toBeUndefined();
    expect(awsEnvironment()['AWS_ACCESS_KEY_ID']).toBe('test-only-key');
  });

  it('deletes only the exact current-run roles and never persistent fixtures', () => {
    expect(shouldDeleteUser('e2e-abc123-buyer@example.com', 'abc123')).toBe(true);
    expect(shouldDeleteUser('e2e-abc123-seller@example.com', 'abc123')).toBe(true);
    expect(shouldDeleteUser('e2e-abc123-admin@example.com', 'abc123')).toBe(true);
    for (const email of ['e2e-old-buyer@example.com', 'e2e-abc123-other@example.com',
      'e2e-fixture-abc123-buyer@example.com', 'ordinary@example.com']) {
      expect(shouldDeleteUser(email, 'abc123')).toBe(false);
    }
    expect(shouldDeleteUser('e2e-abc123-signup@example.com', 'abc123')).toBe(true);
    expect(shouldDeleteUser('e2e-old-signup@example.com', 'abc123')).toBe(false);
    expect(shouldDeleteUser('e2e--buyer@example.com', '')).toBe(false);
    expect(shouldDeleteUser('e2e-.*-buyer@example.com', '.*')).toBe(false);
  });
});

describe('paid-order fixture safety', () => {
  it('updates only allowlisted fixture keys and preserves unrelated lines exactly', () => {
    const original = '# keep this comment\r\nUNRELATED=unchanged\r\nE2E_FIXTURE_BUYER_EMAIL=old@example.com\r\n';
    const updated = updateFixtureEnv(original, { E2E_FIXTURE_BUYER_EMAIL: 'e2e-fixture-new@example.com',
      E2E_FIXTURE_BUYER_PASSWORD: 'test-only-quoted-"-value' });
    expect(updated).toContain('# keep this comment\r\nUNRELATED=unchanged\r\n');
    expect(parseFixtureEnv(updated)).toEqual({ E2E_FIXTURE_BUYER_EMAIL: 'e2e-fixture-new@example.com',
      E2E_FIXTURE_BUYER_PASSWORD: 'test-only-quoted-"-value' });
    expect(parseFixtureEnv('UNRELATED=secret\n')).toEqual({});
  });

  it('rejects unknown keys, injected lines and malformed values without echoing values', () => {
    for (const changes of [{ UNTRUSTED: 'sensitive' }, { E2E_FIXTURE_BUYER_PASSWORD: 'sensitive\nINJECT=value' }]) {
      expect(() => updateFixtureEnv('', changes as FixtureEnv)).toThrow('Invalid fixture environment data');
    }
    for (const text of ['E2E_FIXTURE_BUYER_EMAIL="sensitive\n',
      'E2E_FIXTURE_BUYER_EMAIL=one\nE2E_FIXTURE_BUYER_EMAIL=two\n']) {
      expect(() => parseFixtureEnv(text)).toThrow('Invalid fixture environment data');
    }
  });

  it('bounds paid-order confirmation and reports only status plus the webhook prerequisite', async () => {
    vi.useFakeTimers();
    const gateway = new GatewaySeed('http://gateway.test');
    const read = vi.spyOn(gateway, 'request').mockResolvedValue({ status: 'AWAITING_PAYMENT', secret: 'sensitive' });
    const assertion = expect(waitForOrder(gateway, 'order1', 'sensitive', 'PAID')).rejects
      .toThrow('last status AWAITING_PAYMENT; verify PAYPAL_WEBHOOK_ID and a public tunnel to /payments/webhook');
    await vi.advanceTimersByTimeAsync(300_000);
    await assertion;
    expect(read.mock.calls.length).toBeLessThanOrEqual(150);
  });

  it('cancels a hung polling request at its deadline', async () => {
    vi.useFakeTimers();
    let signal: AbortSignal | undefined;
    const result = poll(async current => { signal = current; return new Promise<string>(() => {}); },
      () => false, 1000, 100);
    const assertion = expect(result).rejects.toThrow('Polling timed out');
    await vi.advanceTimersByTimeAsync(1000);
    await assertion;
    expect(signal?.aborted).toBe(true);
  });
});

describe('gateway seed contracts', () => {
  it('redacts gateway authorization, response content and transport diagnostics', async () => {
    const gateway = new GatewaySeed('http://gateway.test');
    const fetcher = vi.fn().mockResolvedValue(new Response('password=sensitive token=sensitive', { status: 403 }));
    vi.stubGlobal('fetch', fetcher);
    await expect(gateway.request('POST', '/categories', 'sensitive', { name: 'test' }))
      .rejects.toThrow('Gateway POST failed (HTTP 403)');
    expect(fetcher.mock.calls[0][1].headers.Authorization).toBe('Bearer sensitive');
    fetcher.mockRejectedValue(new Error('Authorization: Bearer sensitive'));
    await expect(gateway.request('GET', '/categories', 'sensitive')).rejects.toThrow('Gateway GET request failed');
  });

  it('sends S3 fields before file without an Authorization header', async () => {
    const fetcher = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetcher);
    await new GatewaySeed('http://gateway.test').upload({ url: 'https://s3.test', key: 'key', algorithm: 'algorithm',
      credential: 'credential', date: 'date', policy: 'policy', signature: 'signature', securityToken: null },
      new Uint8Array([1]), 'application/octet-stream');
    const init = fetcher.mock.calls[0][1];
    expect(init.headers).toBeUndefined();
    expect([...init.body.keys()].at(-1)).toBe('file');
    expect(init.body.get('Content-Type')).toBe('application/octet-stream');
    expect(init.body.has('x-amz-security-token')).toBe(false);
  });
  it('uses gateway hardware and software DTOs with tagged titles and enum values', () => {
    expect(hardwareRequest('run123', 'hw-cat', 'hw-attr')).toMatchObject({
      title: '[e2e] run123 hardware', categoryId: 'hw-cat', price: '12.00', condition: 'GOOD',
      shippingLocation: 'Rome, Italy', shippingCost: '3.00', paymentMethods: ['PAYPAL'],
      attributes: [{ attributeId: 'hw-attr', value: 'Standard' }],
    });
    expect(softwareRequest('run123', 'sw-cat', 'sw-attr')).toMatchObject({
      title: '[e2e] run123 software', categoryId: 'sw-cat', price: '5.00', platforms: ['LINUX'],
      versionNumber: '1.0', licenceKeyPattern: null,
      attributes: [{ attributeId: 'sw-attr', value: 'Standard' }],
    });
    expect(softwareRequest('run123', 'sw-cat', 'sw-attr')).not.toHaveProperty('shippingCost');
  });

  it('serializes only nonsecret seed identifiers and role state paths', () => {
    const data = { runId: 'run123', hardwareCategoryId: 'hc', softwareCategoryId: 'sc',
      hardwareAttributeId: 'ha', softwareAttributeId: 'sa', hardwareListingId: 'hl', softwareListingId: 'sl',
      storageStates: { buyer: 'b.json', seller: 's.json', admin: 'a.json', token: 'sensitive' },
      password: 'sensitive', accessToken: 'sensitive' };
    const serialized = serializeSeed(data as SeedData);
    expect(JSON.parse(serialized).hardwareListingId).toBe('hl');
    expect(serialized).not.toMatch(/sensitive|password|token/i);
  });

  it('bounds polling when search never becomes consistent', async () => {
    vi.useFakeTimers();
    const read = vi.fn().mockResolvedValue('pending');
    const outcome = poll(read, value => value === 'ready', 5000, 1000);
    const assertion = expect(outcome).rejects.toThrow('Polling timed out');
    await vi.advanceTimersByTimeAsync(5000);
    await assertion;
    expect(read.mock.calls.length).toBeLessThanOrEqual(5);
  });
});

describe('upload fixtures', () => {
  it('returns stable distinct JPEG bytes', () => {
    const red = jpegFixture('red');
    expect(red.buffer.subarray(0, 2).toString('hex')).toBe('ffd8');
    expect(red.buffer.equals(jpegFixture('red').buffer)).toBe(true);
    expect(red.buffer.equals(jpegFixture('blue').buffer)).toBe(false);
  });
});

describe('strict fixture start state', () => {
  const hardware = { status: 'PAID' };
  const software = { status: 'DELIVERED' };

  it('accepts a PAID hardware and a DELIVERED software order that nothing has touched', () => {
    expect(() => assertFreshFixtures(hardware, software)).not.toThrow();
  });

  it.each([
    ['shipped hardware', { status: 'SHIPPED' }, software],
    ['hardware with a problem', { status: 'PAID', problem: { status: 'OPEN' } }, software],
    ['hardware with a review', { status: 'PAID', review: { id: 'r' } }, software],
    ['software with a problem', hardware, { status: 'DELIVERED', problem: { status: 'OPEN' } }],
    ['software with a review', hardware, { status: 'DELIVERED', review: { id: 'r' } }],
    ['refunded software', hardware, { status: 'REFUNDED' }],
  ])('reports %s as consumed with the rerun instruction', (_name, hw, sw) => {
    expect(() => assertFreshFixtures(hw, sw)).toThrow('fixture consumed: rerun npm --prefix web run e2e:fixtures');
  });
});

describe('relaxed browser evidence', () => {
  const socket429 = "WebSocket connection to 'ws://localhost:8080/ws' failed: Error during WebSocket handshake: Unexpected response code: 429";
  const allowSocket = [(failure: { kind: string; reason?: string }) => failure.kind === 'console-error' && failure.reason === 'websocket-rate-limited'];
  const fakePage = () => Object.assign(new EventEmitter(), { url: () => 'http://localhost:4200/orders/1' });
  const consoleError = (text: string) => ({ type: () => 'error', text: () => text });

  it('lets a rate limited websocket handshake pass only where the caller allows it', () => {
    const page = fakePage();
    const verify = monitorPage(page as unknown as Page, ['http://localhost:8080'], allowSocket);
    page.emit('console', consoleError(socket429));
    expect(verify).not.toThrow();
    const strict = fakePage();
    const verifyStrict = monitorPage(strict as unknown as Page, ['http://localhost:8080']);
    strict.emit('console', consoleError(socket429));
    expect(verifyStrict).toThrow();
  });

  it('labels the browser console line for a conflict response without keeping its text', () => {
    const page = fakePage();
    const verify = monitorPage(page as unknown as Page, ['http://localhost:8080'],
      [failure => failure.kind === 'console-error' && failure.reason === 'conflict-response']);
    page.emit('console', consoleError('Failed to load resource: the server responded with a status of 409 (Conflict)'));
    expect(verify).not.toThrow();
    page.emit('console', consoleError('Failed to load resource: the server responded with a status of 500 (Internal Server Error)'));
    expect(verify).toThrow();
  });

  it('still fails other console errors and never echoes their text', () => {
    const page = fakePage();
    const verify = monitorPage(page as unknown as Page, ['http://localhost:8080'], allowSocket);
    page.emit('console', consoleError('Authorization failed for token sensitive'));
    try { verify(); throw new Error('Expected console failure'); }
    catch (error) { expect(String(error)).toContain('console-error'); expect(String(error)).not.toContain('sensitive'); }
  });

  it('keeps a navigation abort distinguishable from other request failures', () => {
    const page = fakePage();
    const verify = monitorPage(page as unknown as Page, ['http://localhost:8080'], [failure => failure.reason === 'net::ERR_ABORTED']);
    page.emit('requestfailed', { url: () => 'http://localhost:8080/users/me', failure: () => ({ errorText: 'net::ERR_ABORTED' }) });
    expect(verify).not.toThrow();
    page.emit('requestfailed', { url: () => 'http://localhost:8080/users/me', failure: () => ({ errorText: 'net::ERR_CONNECTION_REFUSED' }) });
    expect(verify).toThrow();
  });
});

describe('STOMP frame evidence', () => {
  const NUL = String.fromCharCode(0);
  const message = (queue: string, body: unknown) =>
    `MESSAGE\ndestination:/user/queue/${queue}\ncontent-type:application/json\nsubscription:sub-0\nmessage-id:1\n\n${JSON.stringify(body)}${NUL}`;

  it('reads the queue and JSON body of a MESSAGE frame', () => {
    expect(parseStompMessage(message('typing', { ttlMs: 5000 }))).toEqual({ queue: 'typing', body: { ttlMs: 5000 } });
    expect(parseStompMessage(Buffer.from(message('presence', { online: true })))?.queue).toBe('presence');
  });

  it.each([['a heartbeat', '\n'], ['a CONNECTED frame', `CONNECTED\nversion:1.2\n\n${NUL}`],
    ['a non-JSON body', `MESSAGE\ndestination:/user/queue/typing\n\nnot json${NUL}`],
    ['a frame for another destination', `MESSAGE\ndestination:/topic/x\n\n{}${NUL}`]])('ignores %s', (_name, frame) => {
    expect(parseStompMessage(frame)).toBeNull();
  });

  it('reports a bearer CONNECT without exposing the header value', () => {
    expect(isBearerConnect(`CONNECT\nAuthorization:Bearer abc\naccept-version:1.2\n\n${NUL}`)).toBe(true);
    expect(isBearerConnect(`CONNECT\naccept-version:1.2\n\n${NUL}`)).toBe(false);
    expect(isBearerConnect(`SEND\nAuthorization:Bearer abc\n\n${NUL}`)).toBe(false);
  });

  it('lists the online flags the given user transitioned through', () => {
    const frames = [{ queue: 'presence', body: { userSub: 'a', online: true } }, { queue: 'presence', body: { userSub: 'b', online: false } },
      { queue: 'typing', body: { userSub: 'a' } }, { queue: 'presence', body: { userSub: 'a', online: false } }];
    expect(presenceTransitions(frames, 'a')).toEqual([true, false]);
  });

  it('uses the ttl carried by the latest typing frame and refuses to guess one', () => {
    expect(lastTypingTtl([{ queue: 'typing', body: { ttlMs: 1 } }, { queue: 'typing', body: { ttlMs: 5000 } }])).toBe(5000);
    expect(() => lastTypingTtl([{ queue: 'presence', body: {} }])).toThrow('No typing frame');
  });
});

describe('strict admin problem start state', () => {
  const open = { status: 'DELIVERED', problem: { status: 'OPEN' } };

  it('accepts two orders whose problems are both still open', () => {
    expect(() => assertOpenProblems(open, open)).not.toThrow();
  });

  it.each([
    ['no hardware problem', { status: 'DELIVERED' }, open],
    ['a refund pending hardware problem', { status: 'DELIVERED', problem: { status: 'REFUND_PENDING' } }, open],
    ['a resolved software problem', open, { status: 'DELIVERED', problem: { status: 'RESOLVED' } }],
    ['a software order without a problem', open, { status: 'DELIVERED' }],
  ])('reports %s as consumed with both rerun instructions', (_name, hw, sw) => {
    expect(() => assertOpenProblems(hw, sw))
      .toThrow('fixture consumed: rerun npm --prefix web run e2e:fixtures and plan 06-42 Task 3');
  });
});
