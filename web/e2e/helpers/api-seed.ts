import { setTimeout as delay } from 'node:timers/promises';
import type { CategoryTreeNode, CreateCategoryRequest, DefineAttributeRequest, ListingCreatedResponse,
  ListingDetailResponse, PublishHardwareRequest, PublishSoftwareRequest, SearchResultPage,
  UploadPolicy } from '../../src/app/core/api/api-models';
import type { SeedData } from './test-data';

export function hardwareRequest(runId: string, categoryId: string, attributeId: string): PublishHardwareRequest {
  return { title: `[e2e] ${runId} hardware`, description: 'Isolated marketplace hardware fixture',
    price: '12.00', categoryId, photos: [], paymentMethods: ['PAYPAL'], condition: 'GOOD',
    shippingLocation: 'Rome, Italy', shippingCost: '3.00', attributes: [{ attributeId, value: 'Standard' }] };
}
export function softwareRequest(runId: string, categoryId: string, attributeId: string): PublishSoftwareRequest {
  return { title: `[e2e] ${runId} software`, description: 'Isolated marketplace software fixture',
    price: '5.00', categoryId, photos: [], paymentMethods: ['PAYPAL'], platforms: ['LINUX'],
    versionNumber: '1.0', notes: null, licenceKeyPattern: null, attributes: [{ attributeId, value: 'Standard' }] };
}
export async function poll<T>(read: (signal: AbortSignal) => Promise<T>, ready: (value: T) => boolean,
  timeoutMs = 30_000, intervalMs = 1_000): Promise<T> {
  if (!Number.isFinite(timeoutMs) || !Number.isFinite(intervalMs) || timeoutMs <= 0 || intervalMs <= 0) {
    throw new Error('Invalid polling bounds');
  }
  const controller = new AbortController();
  let timer: ReturnType<typeof setTimeout>;
  const expired = new Promise<never>((_, reject) => {
    timer = setTimeout(() => { controller.abort(); reject(new Error('Polling timed out')); }, timeoutMs);
  });
  const loop = async () => {
    while (!controller.signal.aborted) {
      const value = await read(controller.signal);
      if (ready(value)) return value;
      await delay(intervalMs, undefined, { signal: controller.signal });
    }
    throw new Error('Polling timed out');
  };
  try { return await Promise.race([loop(), expired]); }
  finally { clearTimeout(timer!); controller.abort(); }
}

export async function waitForOrder(gateway: GatewaySeed, id: string, token: string,
  status: 'PAID' | 'DELIVERED'): Promise<void> {
  let lastStatus = 'not observed';
  try {
    await poll(async signal => {
      const order = await gateway.request<{ status: string }>('GET', `/orders/${encodeURIComponent(id)}`, token, undefined, signal);
      const known = ['AWAITING_PAYMENT', 'PAID', 'SHIPPED', 'DELIVERED', 'CANCELLED', 'REFUNDED'];
      lastStatus = known.includes(order.status) ? order.status : 'unknown';
      return lastStatus;
    }, observed => observed === status, 300_000, 2000);
  } catch (error) {
    if (error instanceof Error && error.message === 'Polling timed out') {
      throw new Error(`Order confirmation timed out; last status ${lastStatus}; verify PAYPAL_WEBHOOK_ID and a public tunnel to /payments/webhook`);
    }
    throw error;
  }
}

export class GatewaySeed {
  constructor(readonly baseURL: string) {}

  async request<T>(method: 'GET' | 'POST' | 'PUT', path: string, token: string, body?: unknown,
    signal = AbortSignal.timeout(30_000)): Promise<T> {
    let response: Response;
    try {
      response = await fetch(`${this.baseURL}${path}`, { method,
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal });
    } catch { throw new Error(`Gateway ${method} request failed`); }
    if (!response.ok) throw new Error(`Gateway ${method} failed (HTTP ${response.status})`);
    try { return response.status === 204 ? undefined as T : await response.json() as T; }
    catch { throw new Error(`Gateway ${method} returned invalid JSON`); }
  }

  async upload(policy: UploadPolicy, bytes: Uint8Array, contentType: string): Promise<void> {
    const form = new FormData();
    for (const [key, value] of Object.entries({ key: policy.key, 'x-amz-algorithm': policy.algorithm,
      'x-amz-credential': policy.credential, 'x-amz-date': policy.date, policy: policy.policy,
      'x-amz-signature': policy.signature, 'Content-Type': contentType })) form.append(key, value);
    if (policy.securityToken) form.append('x-amz-security-token', policy.securityToken);
    // S3 ignores every field following file.
    form.append('file', new Blob([new Uint8Array(bytes)], { type: contentType }), 'fixture');
    try {
      const response = await fetch(policy.url, { method: 'POST', body: form, signal: AbortSignal.timeout(30_000) });
      if (!response.ok) throw new Error();
    } catch { throw new Error('Fixture S3 upload failed'); }
  }

  async photo(id: string, token: string): Promise<void> {
    const path = `/listings/${encodeURIComponent(id)}/photos`;
    const policy = await this.request<UploadPolicy>('POST', `${path}/upload-policy`, token);
    const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aHM8AAAAASUVORK5CYII=', 'base64');
    await this.upload(policy, png, 'image/png');
    await this.request('POST', `${path}/confirm`, token, { key: policy.key });
  }

  async artifact(id: string, token: string): Promise<void> {
    const path = `/listings/${encodeURIComponent(id)}/versions/1.0`;
    const policy = await this.request<UploadPolicy>('POST', `${path}/artifact-upload-policy`, token);
    await this.upload(policy, Buffer.from('Rebyte e2e software fixture version 1.0\n'), 'application/octet-stream');
    await this.request('POST', `${path}/artifact/confirm`, token, { key: policy.key });
  }

  async seed(runId: string, adminToken: string, sellerToken: string): Promise<Omit<SeedData, 'storageStates'>> {
    const roots = await this.request<CategoryTreeNode[]>('GET', '/categories', adminToken);
    const categories: { id: string; attributeId: string }[] = [];
    for (const name of ['Hardware', 'Software']) {
      const parent = roots.find(category => category.name === name);
      if (!parent) throw new Error(`Missing fixed ${name} root category`);
      const body: CreateCategoryRequest = { name: `[e2e] ${runId} ${name}`, parentId: parent.id };
      const category = await this.request<{ id: string }>('POST', '/categories', adminToken, body);
      const attribute: DefineAttributeRequest = { name: 'fixture_edition', type: 'ENUM', required: true,
        allowedValues: ['Standard', 'Expanded'], unit: null };
      const defined = await this.request<{ id: string }>('POST', `/categories/${encodeURIComponent(category.id)}/attributes`, adminToken, attribute);
      categories.push({ id: category.id, attributeId: defined.id });
    }
    const [hardware, software] = categories;
    await this.request('POST', '/users/me/developer-profile', sellerToken);
    const hw = await this.request<ListingCreatedResponse>('POST', '/listings/hardware', sellerToken,
      hardwareRequest(runId, hardware.id, hardware.attributeId));
    const sw = await this.request<ListingCreatedResponse>('POST', '/listings/software', sellerToken,
      softwareRequest(runId, software.id, software.attributeId));
    await this.photo(hw.id, sellerToken);
    await this.photo(sw.id, sellerToken);
    await poll(signal => this.request<SearchResultPage>('GET', `/search?q=${encodeURIComponent(runId)}`, sellerToken, undefined, signal),
      result => [hw.id, sw.id].every(id => result.hits.some(hit => hit.id === id && !!hit.coverUrl)), 60_000);
    for (const id of [hw.id, sw.id]) {
      await poll(signal => this.request<ListingDetailResponse>('GET', `/listings/${encodeURIComponent(id)}`, sellerToken, undefined, signal),
        listing => listing.photos.length > 0);
    }
    return { runId, hardwareCategoryId: hardware.id, softwareCategoryId: software.id,
      hardwareAttributeId: hardware.attributeId, softwareAttributeId: software.attributeId,
      hardwareListingId: hw.id, softwareListingId: sw.id };
  }

  async publicFixtures(seed: Omit<SeedData, 'storageStates'>, adminToken: string, sellerToken: string): Promise<NonNullable<SeedData['publicFixtures']>> {
    const longAttribute = 'long_'.repeat(11).slice(0, 60);
    const attribute = await this.request<{ id: string }>('POST', `/categories/${seed.hardwareCategoryId}/attributes`, adminToken,
      { name: longAttribute, type: 'ENUM', required: false, allowedValues: ['LongValue'.repeat(12)], unit: null });
    await this.request('PUT', '/users/me', sellerToken, { username: `e2e_long_${seed.runId.replaceAll('-', '').slice(0, 21)}`,
      location: 'Rome, Italy', bio: 'LongUnbrokenBiography'.repeat(25).slice(0, 500) });
    const longTitle = `[e2e] ${seed.runId} ${'LongUnbrokenTitle'.repeat(9)}`;
    let noPhotoListingId = '';
    let longListingId = '';
    for (let index = 1; index <= 25; index++) {
      const body = hardwareRequest(seed.runId, seed.hardwareCategoryId, seed.hardwareAttributeId);
      body.title = index === 2 ? longTitle : `[e2e] ${seed.runId} hardware ${index}`;
      if (index === 2) {
        body.description = 'LongUnbrokenDescription'.repeat(25);
        body.attributes.push({ attributeId: attribute.id, value: 'LongValue'.repeat(12) });
      }
      const listing = await this.request<ListingCreatedResponse>('POST', '/listings/hardware', sellerToken, body);
      if (index === 1) noPhotoListingId = listing.id;
      if (index === 2) longListingId = listing.id;
    }
    await poll(signal => this.request<SearchResultPage>('GET', `/search?q=${encodeURIComponent(seed.runId)}&size=24`, sellerToken, undefined, signal),
      result => result.total === 27, 60_000);
    return { noPhotoListingId, longListingId, longTitle, longAttribute };
  }
}
