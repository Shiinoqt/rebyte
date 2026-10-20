import { TestBed } from '@angular/core/testing';
import { PHOTO_BROWSER_APIS, PhotoPipeline, type PhotoBrowserApis } from './photo-pipeline';
import type { ChatImageUploadPolicyResponse, UploadPolicy } from '../api/api-models';
import { S3_XHR_FACTORY, S3Uploader } from './s3-uploader';

function image(type = 'image/jpeg', size = 10): File {
  return new File([new Uint8Array(size)], 'selected-photo', { type });
}

function photoHarness(width = 4000, height = 3000, sizes = [100]) {
  const operations: unknown[][] = [];
  const bitmap = { width, height, close: vi.fn() } as unknown as ImageBitmap;
  const context = {
    fillStyle: '',
    fillRect: (...args: number[]) => operations.push(['fill', context.fillStyle, ...args]),
    drawImage: (...args: unknown[]) => operations.push(['draw', ...args]),
  };
  let encoded = 0;
  const canvas = {
    width: 0,
    height: 0,
    getContext: vi.fn((): typeof context | null => context),
    toBlob: vi.fn((callback: BlobCallback, type: string, quality: number) => {
      operations.push(['encode', canvas.width, canvas.height, type, quality]);
      callback(new Blob([new Uint8Array(sizes[Math.min(encoded++, sizes.length - 1)])], { type }));
    }),
  };
  const apis: PhotoBrowserApis = {
    createImageBitmap: vi.fn(async () => bitmap),
    createCanvas: () => canvas as unknown as HTMLCanvasElement,
    createObjectURL: vi.fn(() => 'blob:preview'),
    revokeObjectURL: vi.fn(),
  };
  TestBed.configureTestingModule({ providers: [{ provide: PHOTO_BROWSER_APIS, useValue: apis }] });
  return { pipeline: TestBed.inject(PhotoPipeline), apis, bitmap, canvas, operations };
}

describe('PhotoPipeline', () => {
  it.each([
    [4000, 3000, 2000, 1500],
    [3000, 4000, 1500, 2000],
    [640, 480, 640, 480],
    [2000, 1000, 2000, 1000],
    [100000, 1, 2000, 1],
  ])('bounds %i x %i without upscaling', async (width, height, expectedWidth, expectedHeight) => {
    const h = photoHarness(width, height);
    const result = await h.pipeline.process(image(), { maxEdge: 2000, maxBytes: 2097152 });
    expect([result.width, result.height]).toEqual([expectedWidth, expectedHeight]);
    expect(result.blob.type).toBe('image/jpeg');
    expect(result.blob.size).toBeLessThanOrEqual(2097152);
    expect(h.operations).toContainEqual(['draw', h.bitmap, 0, 0, expectedWidth, expectedHeight]);
    expect(h.bitmap.close).toHaveBeenCalledOnce();
  });

  it('scales the EXIF-oriented bitmap dimensions', async () => {
    const h = photoHarness();
    vi.mocked(h.apis.createImageBitmap).mockImplementation(async (_file, options) => {
      return {
        ...h.bitmap,
        width: options.imageOrientation === 'from-image' ? 3000 : 4000,
        height: options.imageOrientation === 'from-image' ? 4000 : 3000,
      };
    });
    const file = image();
    const result = await h.pipeline.process(file, { maxEdge: 2000, maxBytes: 2097152 });
    expect(h.apis.createImageBitmap).toHaveBeenCalledWith(file, { imageOrientation: 'from-image' });
    expect([result.width, result.height]).toEqual([1500, 2000]);
  });

  it.each(['image/jpeg', 'image/png', 'image/webp'])(
    'encodes %s as JPEG on white before drawing',
    async (type) => {
      const h = photoHarness();
      const result = await h.pipeline.process(image(type), { maxEdge: 2000, maxBytes: 1048576 });
      expect(h.operations[0]).toEqual(['fill', '#ffffff', 0, 0, 2000, 1500]);
      expect(h.operations[1]).toEqual(['draw', h.bitmap, 0, 0, 2000, 1500]);
      expect(h.operations[2]).toEqual(['encode', 2000, 1500, 'image/jpeg', 0.85]);
      expect(result.blob.type).toBe('image/jpeg');
    },
  );

  it.each([2097152, 524288, 1048576])(
    'retries quality only until the %i-byte flow cap is met',
    async (maxBytes) => {
      const h = photoHarness(4000, 3000, [maxBytes + 10, maxBytes + 1, maxBytes, 1]);
      const result = await h.pipeline.process(image(), { maxEdge: 2000, maxBytes });
      expect(result.blob.size).toBe(maxBytes);
      expect(h.operations.filter((op) => op[0] === 'encode').map((op) => op[4])).toEqual([
        0.85, 0.75, 0.65,
      ]);
    },
  );

  it('tries the last quality before returning a photo-too-large error', async () => {
    const h = photoHarness(4000, 3000, [101]);
    await expect(
      h.pipeline.process(image(), { maxEdge: 2000, maxBytes: 100 }),
    ).rejects.toMatchObject({ code: 'photo-too-large' });
    expect(h.operations.filter((op) => op[0] === 'encode').map((op) => op[4])).toEqual([
      0.85, 0.75, 0.65, 0.55,
    ]);
    expect(h.apis.createObjectURL).not.toHaveBeenCalled();
    expect(h.bitmap.close).toHaveBeenCalledOnce();
  });

  it('can meet the cap at the last quality', async () => {
    const h = photoHarness(4000, 3000, [101, 101, 101, 100]);
    const result = await h.pipeline.process(image(), { maxEdge: 2000, maxBytes: 100 });
    expect(result.blob.size).toBe(100);
    expect(h.canvas.toBlob).toHaveBeenCalledTimes(4);
  });

  it.each(['image/heic', 'image/gif', 'text/plain', ''])(
    'rejects unsupported %s before decoding',
    async (type) => {
      const h = photoHarness();
      await expect(
        h.pipeline.process(image(type), { maxEdge: 2000, maxBytes: 100 }),
      ).rejects.toMatchObject({ code: 'photo-type' });
      expect(h.apis.createImageBitmap).not.toHaveBeenCalled();
    },
  );

  it('rejects an empty image before decoding', async () => {
    const h = photoHarness();
    await expect(
      h.pipeline.process(image('image/png', 0), { maxEdge: 2000, maxBytes: 100 }),
    ).rejects.toMatchObject({ code: 'photo-empty' });
    expect(h.apis.createImageBitmap).not.toHaveBeenCalled();
  });

  it('honours a smaller requested edge and always caps at 2000', async () => {
    const h = photoHarness();
    const small = await h.pipeline.process(image(), { maxEdge: 800, maxBytes: 100 });
    const large = await h.pipeline.process(image(), { maxEdge: 4000, maxBytes: 100 });
    expect([small.width, small.height]).toEqual([800, 600]);
    expect([large.width, large.height]).toEqual([2000, 1500]);
  });

  it('returns a preview URL with idempotent cleanup', async () => {
    const h = photoHarness();
    const result = await h.pipeline.process(image(), { maxEdge: 2000, maxBytes: 100 });
    expect(result.url).toBe('blob:preview');
    expect(h.apis.createObjectURL).toHaveBeenCalledWith(result.blob);
    expect(h.apis.revokeObjectURL).not.toHaveBeenCalled();
    result.cleanup();
    result.cleanup();
    expect(h.apis.revokeObjectURL).toHaveBeenCalledExactlyOnceWith('blob:preview');
  });

  it('reports a decode failure as a field error', async () => {
    const h = photoHarness();
    vi.mocked(h.apis.createImageBitmap).mockRejectedValue(new Error('invalid bytes'));
    await expect(
      h.pipeline.process(image(), { maxEdge: 2000, maxBytes: 100 }),
    ).rejects.toMatchObject({ code: 'photo-processing-failed' });
  });

  it.each(['missing-context', 'null-blob', 'wrong-mime', 'empty-blob'])(
    'releases the bitmap on %s',
    async (failure) => {
      const h = photoHarness();
      if (failure === 'missing-context') h.canvas.getContext.mockReturnValue(null);
      else
        h.canvas.toBlob.mockImplementation((callback) =>
          callback(
            failure === 'null-blob'
              ? null
              : new Blob([new Uint8Array(failure === 'empty-blob' ? 0 : 10)], {
                  type: failure === 'wrong-mime' ? 'image/png' : 'image/jpeg',
                }),
          ),
        );
      await expect(
        h.pipeline.process(image(), { maxEdge: 2000, maxBytes: 100 }),
      ).rejects.toMatchObject({ code: 'photo-processing-failed' });
      expect(h.bitmap.close).toHaveBeenCalledOnce();
      expect(h.apis.createObjectURL).not.toHaveBeenCalled();
    },
  );

  it.each([
    { maxEdge: 0, maxBytes: 100 },
    { maxEdge: 2000, maxBytes: 0 },
    { maxEdge: NaN, maxBytes: 100 },
  ])('rejects invalid processing limits', async (options) => {
    const h = photoHarness();
    await expect(h.pipeline.process(image(), options)).rejects.toThrow(RangeError);
    expect(h.apis.createImageBitmap).not.toHaveBeenCalled();
  });
});

class FakeXhr {
  status = 0;
  withCredentials = false;
  upload = { onprogress: null as ((event: ProgressEvent) => void) | null };
  onload: (() => void) | null = null;
  onerror: (() => void) | null = null;
  onabort: (() => void) | null = null;
  ontimeout: (() => void) | null = null;
  method = '';
  url = '';
  body: FormData | undefined;
  headers: [string, string][] = [];
  aborted = false;
  open(method: string, url: string): void {
    this.method = method;
    this.url = url;
  }
  setRequestHeader(name: string, value: string): void {
    this.headers.push([name, value]);
  }
  send(body: FormData): void {
    this.body = body;
  }
  abort(): void {
    this.aborted = true;
    this.onabort?.();
  }
  progress(loaded: number, total: number, lengthComputable = true): void {
    this.upload.onprogress?.({ loaded, total, lengthComputable } as ProgressEvent);
  }
  complete(status = 204): void {
    this.status = status;
    this.onload?.();
  }
}

const POLICY: UploadPolicy = {
  url: 'https://media.s3.eu-south-1.amazonaws.com/',
  key: 'listings/example/object',
  algorithm: 'AWS4-HMAC-SHA256',
  credential: 'test-access/20261008/eu-south-1/s3/aws4_request',
  date: '20261008T090000Z',
  policy: 'test-base64-policy',
  signature: 'test-signature',
  securityToken: null,
};

function uploadHarness() {
  const requests: FakeXhr[] = [];
  const factory = vi.fn(() => {
    const request = new FakeXhr();
    requests.push(request);
    return request as unknown as XMLHttpRequest;
  });
  TestBed.configureTestingModule({ providers: [{ provide: S3_XHR_FACTORY, useValue: factory }] });
  return { uploader: TestBed.inject(S3Uploader), requests, factory };
}

describe('S3Uploader', () => {
  it.each(['listings/example/object', 'avatars/example/object'])(
    'posts ordered JPEG fields for %s',
    async (key) => {
      const h = uploadHarness();
      const blob = new Blob(['jpeg'], { type: 'image/jpeg' });
      const promise = h.uploader.upload({ ...POLICY, key }, blob, 'image/jpeg');
      const xhr = h.requests[0];
      const entries = [...xhr.body!.entries()];
      expect(entries.slice(0, -1)).toEqual([
        ['key', key],
        ['x-amz-algorithm', 'AWS4-HMAC-SHA256'],
        ['x-amz-credential', 'test-access/20261008/eu-south-1/s3/aws4_request'],
        ['x-amz-date', '20261008T090000Z'],
        ['policy', 'test-base64-policy'],
        ['x-amz-signature', 'test-signature'],
        ['Content-Type', 'image/jpeg'],
      ]);
      expect(entries.at(-1)![0]).toBe('file');
      expect((entries.at(-1)![1] as Blob).type).toBe('image/jpeg');
      expect((entries.at(-1)![1] as Blob).size).toBe(4);
      expect([xhr.method, xhr.url]).toEqual(['POST', POLICY.url]);
      expect(xhr.headers).toEqual([]);
      expect(xhr.withCredentials).toBe(false);
      xhr.complete();
      await expect(promise).resolves.toBeUndefined();
    },
  );

  it('uses the artifact policy Content-Type even for a differently typed blob', async () => {
    const h = uploadHarness();
    const blob = new Blob(['installer'], { type: 'application/zip' });
    const promise = h.uploader.upload(
      { ...POLICY, key: 'software/example/version/object' },
      blob,
      'application/octet-stream',
    );
    const entries = [...h.requests[0].body!.entries()];
    expect(entries.slice(-2).map(([name]) => name)).toEqual(['Content-Type', 'file']);
    expect(h.requests[0].body!.get('Content-Type')).toBe('application/octet-stream');
    expect((h.requests[0].body!.get('file') as Blob).type).toBe('application/zip');
    h.requests[0].complete();
    await promise;
  });

  it.each(['image/jpeg', 'image/png', 'image/webp'])(
    'uses the exact chat policy type %s',
    async (contentType) => {
      const h = uploadHarness();
      const policy: ChatImageUploadPolicyResponse = {
        ...POLICY,
        key: 'chat/example/object',
        contentType,
      };
      const promise = h.uploader.upload(
        policy,
        new Blob(['chat'], { type: contentType }),
        policy.contentType,
      );
      const entries = [...h.requests[0].body!.entries()];
      expect(entries.slice(-2).map(([name]) => name)).toEqual(['Content-Type', 'file']);
      expect(h.requests[0].body!.get('Content-Type')).toBe(contentType);
      expect(h.requests[0].headers).toEqual([]);
      h.requests[0].complete();
      await promise;
    },
  );

  it('includes a session token only when present, before file', async () => {
    const h = uploadHarness();
    const promise = h.uploader.upload(
      { ...POLICY, securityToken: 'test-session-token' },
      image(),
      'image/jpeg',
    );
    expect([...h.requests[0].body!.keys()]).toEqual([
      'key',
      'x-amz-algorithm',
      'x-amz-credential',
      'x-amz-date',
      'x-amz-security-token',
      'policy',
      'x-amz-signature',
      'Content-Type',
      'file',
    ]);
    expect(h.requests[0].body!.get('x-amz-security-token')).toBe('test-session-token');
    h.requests[0].complete();
    await promise;
  });

  it('omits an empty session token', async () => {
    const h = uploadHarness();
    const promise = h.uploader.upload({ ...POLICY, securityToken: '' }, image(), 'image/jpeg');
    expect(h.requests[0].body!.has('x-amz-security-token')).toBe(false);
    h.requests[0].complete();
    await promise;
  });

  it('reports bounded monotonic percentages and ignores indeterminate progress', async () => {
    const h = uploadHarness();
    const progress: number[] = [];
    const promise = h.uploader.upload(POLICY, image(), 'image/jpeg', (percent) =>
      progress.push(percent),
    );
    const xhr = h.requests[0];
    xhr.progress(1, 4);
    xhr.progress(2, 4);
    xhr.progress(1, 4);
    xhr.progress(9, 10, false);
    xhr.progress(1, 0);
    xhr.progress(1000, 10);
    xhr.progress(1, 4);
    xhr.complete();
    await promise;
    expect(progress).toEqual([25, 50, 100]);
    xhr.progress(1, 2);
    expect(progress).toEqual([25, 50, 100]);
  });

  it('reports completion even if there are no progress events', async () => {
    const h = uploadHarness();
    const progress = vi.fn();
    const promise = h.uploader.upload(POLICY, image(), 'image/jpeg', progress);
    h.requests[0].complete();
    await promise;
    expect(progress).toHaveBeenCalledExactlyOnceWith(100);
  });

  it.each([200, 201, 204, 299])('accepts only successful HTTP status %i', async (status) => {
    const h = uploadHarness();
    const promise = h.uploader.upload(POLICY, image(), 'image/jpeg');
    h.requests[0].complete(status);
    await expect(promise).resolves.toBeUndefined();
  });

  it.each([0, 199, 300, 403, 500])(
    'rejects HTTP status %i without claiming completion',
    async (status) => {
      const h = uploadHarness();
      const progress = vi.fn();
      const promise = h.uploader.upload(POLICY, image(), 'image/jpeg', progress);
      h.requests[0].complete(status);
      await expect(promise).rejects.toThrow(`s3-${status}`);
      expect(progress).not.toHaveBeenCalled();
    },
  );

  it.each(['network', 'timeout'])(
    'rejects a %s failure and releases event handlers',
    async (failure) => {
      const h = uploadHarness();
      const promise = h.uploader.upload(POLICY, image(), 'image/jpeg');
      if (failure === 'network') h.requests[0].onerror?.();
      else h.requests[0].ontimeout?.();
      await expect(promise).rejects.toThrow(`s3-${failure}`);
      expect(h.requests[0].upload.onprogress).toBeNull();
      expect(h.requests[0].onload).toBeNull();
    },
  );

  it('aborts an in-flight request through AbortSignal', async () => {
    const h = uploadHarness();
    const controller = new AbortController();
    const promise = h.uploader.upload(POLICY, image(), 'image/jpeg', undefined, controller.signal);
    controller.abort();
    await expect(promise).rejects.toMatchObject({ name: 'AbortError' });
    expect(h.requests[0].aborted).toBe(true);
    expect(h.requests[0].upload.onprogress).toBeNull();
  });

  it('rejects an already aborted signal without creating a request', async () => {
    const h = uploadHarness();
    const controller = new AbortController();
    controller.abort();
    await expect(
      h.uploader.upload(POLICY, image(), 'image/jpeg', undefined, controller.signal),
    ).rejects.toMatchObject({ name: 'AbortError' });
    expect(h.factory).not.toHaveBeenCalled();
  });

  it('rejects an XHR abort event', async () => {
    const h = uploadHarness();
    const promise = h.uploader.upload(POLICY, image(), 'image/jpeg');
    h.requests[0].abort();
    await expect(promise).rejects.toMatchObject({ name: 'AbortError' });
  });

  it('removes the signal listener after success', async () => {
    const h = uploadHarness();
    const controller = new AbortController();
    const remove = vi.spyOn(controller.signal, 'removeEventListener');
    const promise = h.uploader.upload(POLICY, image(), 'image/jpeg', undefined, controller.signal);
    h.requests[0].complete();
    await promise;
    expect(remove).toHaveBeenCalledWith('abort', expect.any(Function));
    controller.abort();
    expect(h.requests[0].aborted).toBe(false);
  });

  it('keeps concurrent uploads and progress independent', async () => {
    const h = uploadHarness();
    const firstProgress: number[] = [];
    const secondProgress: number[] = [];
    const first = h.uploader.upload(POLICY, image(), 'image/jpeg', (percent) =>
      firstProgress.push(percent),
    );
    const second = h.uploader.upload(
      { ...POLICY, key: 'avatars/second/object' },
      image(),
      'image/jpeg',
      (percent) => secondProgress.push(percent),
    );
    h.requests[0].progress(3, 4);
    h.requests[1].progress(1, 4);
    h.requests[1].complete();
    h.requests[0].complete();
    await Promise.all([first, second]);
    expect(firstProgress).toEqual([75, 100]);
    expect(secondProgress).toEqual([25, 100]);
    expect(h.requests[1].body!.get('key')).toBe('avatars/second/object');
  });
});
