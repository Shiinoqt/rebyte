import { expect, type Browser, type BrowserContext, type Page, type TestInfo, type WebSocketRoute } from '@playwright/test';
import { monitorPage } from './assert-clean-page';
import { runtimeConfig } from './cognito-admin';

export interface RealtimeFrame { queue: string; body: Record<string, unknown> }
export type TimedFrame = RealtimeFrame & { at: number };

const QUEUE = /^\/user\/queue\/(messages|notifications|unread|presence|typing)$/;

function frameParts(data: string | Buffer): { command: string; headers: Map<string, string>; body: string } {
  const text = typeof data === 'string' ? data : data.toString('utf8');
  const split = text.indexOf('\n\n');
  const [command = '', ...lines] = (split < 0 ? text : text.slice(0, split)).split('\n');
  const headers = new Map<string, string>();
  for (const line of lines) {
    const colon = line.indexOf(':');
    if (colon > 0 && !headers.has(line.slice(0, colon).toLowerCase())) headers.set(line.slice(0, colon).toLowerCase(), line.slice(colon + 1));
  }
  return { command, headers, body: split < 0 ? '' : text.slice(split + 2).replace(/\0$/, '') };
}

export function parseStompMessage(data: string | Buffer): RealtimeFrame | null {
  const { command, headers, body } = frameParts(data);
  const queue = QUEUE.exec(headers.get('destination') ?? '')?.[1];
  if (command !== 'MESSAGE' || !queue) return null;
  try {
    const parsed: unknown = JSON.parse(body);
    return parsed && typeof parsed === 'object' ? { queue, body: parsed as Record<string, unknown> } : null;
  } catch { return null; }
}

// Booleans only: the header value is a bearer token and must never reach a log or an assertion message.
export function isBearerConnect(data: string | Buffer): boolean {
  const { command, headers } = frameParts(data);
  return command === 'CONNECT' && /^Bearer \S+/.test(headers.get('authorization') ?? '');
}

export function presenceTransitions(frames: RealtimeFrame[], userSub: string): boolean[] {
  return frames.filter(frame => frame.queue === 'presence' && frame.body['userSub'] === userSub)
    .map(frame => frame.body['online'] === true);
}

export function lastTypingTtl(frames: RealtimeFrame[]): number {
  const ttl = frames.filter(frame => frame.queue === 'typing').at(-1)?.body['ttlMs'];
  if (typeof ttl !== 'number') throw new Error('No typing frame with a ttlMs was received');
  return ttl;
}

export interface SocketTrace {
  received: TimedFrame[];
  bearerConnects: number;
  connected: number;
  /** While true every new socket is closed before it reaches the gateway, holding the SPA in its reconnect loop. */
  blocked: boolean;
  /** Closes every live socket on both sides, so the backend sees a real disconnect and the SPA reconnects by itself. */
  drop(): void;
}

// Passes the SPA's real STOMP socket through to the gateway unchanged and only records what crosses it.
export async function traceSocket(page: Page): Promise<SocketTrace> {
  const live = new Set<{ page: WebSocketRoute; server: WebSocketRoute }>();
  const trace: SocketTrace = { received: [], bearerConnects: 0, connected: 0, blocked: false,
    drop: () => { for (const pair of live) { pair.server.close({ code: 1000, reason: 'e2e drop' }); pair.page.close({ code: 1000, reason: 'e2e drop' }); } } };
  await page.routeWebSocket(/\/ws$/, route => {
    if (trace.blocked) { void route.close({ code: 1000, reason: 'e2e blocked' }); return; }
    const server = route.connectToServer();
    const pair = { page: route, server };
    live.add(pair);
    route.onMessage(data => { if (isBearerConnect(data)) trace.bearerConnects++; server.send(data); });
    server.onMessage(data => {
      const frame = parseStompMessage(data);
      if (frame) trace.received.push({ ...frame, at: Date.now() });
      else if (typeof data === 'string' && data.startsWith('CONNECTED')) trace.connected++;
      route.send(data);
    });
    route.onClose(() => { live.delete(pair); void server.close(); });
    server.onClose(() => { live.delete(pair); void route.close(); });
  });
  return trace;
}

export interface Session { context: BrowserContext; page: Page; trace: SocketTrace; verify: () => void }


export async function openSession(browser: Browser, testInfo: TestInfo, storageState: string): Promise<Session> {
  const context = await browser.newContext({ ...testInfo.project.use, storageState });
  const page = await context.newPage();
  const origins = [new URL(testInfo.project.use.baseURL!).origin, new URL(runtimeConfig().apiBaseUrl).origin];
  // A navigation cancels in-flight refreshes, the gateway rate limits /ws handshakes on purpose and an existing
  // conversation answers 409 with the id to open instead.
  const verify = monitorPage(page, origins, [f => f.kind === 'unexpected-public-authorization',
    f => f.kind === 'requestfailed' && f.reason === 'net::ERR_ABORTED',
    f => f.kind === 'console-error' && f.reason === 'websocket-rate-limited',
    f => f.kind === 'console-error' && f.reason === 'conflict-response',
    f => f.kind === 'http' && f.status === 409 && f.url.endsWith('/conversations')]);
  const trace = await traceSocket(page);
  const trusted = new Set(origins);
  const leaks: string[] = [];
  // Chat images travel to and from S3 directly; a bearer reaching any other origin than the app and the gateway is a leak.
  page.on('request', request => {
    const url = new URL(request.url());
    if (/^https?:$/.test(url.protocol) && !trusted.has(url.origin) && request.headers()['authorization']) leaks.push(url.hostname);
  });
  return { context, page, trace, verify: () => { verify(); expect(leaks, 'Bearer sent to a third-party origin').toEqual([]); } };
}
