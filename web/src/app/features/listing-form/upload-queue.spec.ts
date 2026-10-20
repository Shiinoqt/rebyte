import { of, Subject, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { CatalogApi } from '../../core/api/catalog-api';
import { EditListingRequest, EditListingResponse } from '../../core/api/api-models';
import { S3Uploader } from '../../core/media/s3-uploader';
import { ListingPhoto } from './photo-picker';
import { UploadQueueService } from './upload-queue.service';

const request: EditListingRequest = {
  title: 'Camera',
  description: 'Works.',
  photos: [],
  attributes: [],
  categoryId: null,
  licenceKeyPattern: null,
};
const response: EditListingResponse = {
  listing: {
    id: 'l1',
    title: 'Camera',
    price: '1',
    status: 'ACTIVE',
    publication: '2026-10-08T00:00:00Z',
  },
  droppedAttributeIds: [],
};
const stored = (n: number): ListingPhoto => ({
  id: `s${n}`,
  name: `Photo ${n}`,
  key: `old-${n}`,
  url: `https://cdn/${n}`,
});
const fresh = (n: number): ListingPhoto => ({
  id: `n${n}`,
  name: `${n}.jpg`,
  url: `blob:${n}`,
  processed: {
    blob: new Blob(['image'], { type: 'image/jpeg' }),
    url: `blob:${n}`,
    width: 10,
    height: 10,
    cleanup: vi.fn(),
  },
});
function setup() {
  const calls: string[] = [];
  let policyNumber = 0;
  const catalog = {
    editListing: vi.fn((id: string, body: EditListingRequest) => {
      calls.push(`save:${body.photos.join(',')}`);
      return of(response);
    }),
    photoUploadPolicy: vi.fn(() => {
      const key = `new-${++policyNumber}`;
      calls.push(`policy:${key}`);
      return of({
        key,
        url: 'https://s3',
        algorithm: 'a',
        credential: 'c',
        date: 'd',
        policy: 'p',
        signature: 's',
        securityToken: null,
      });
    }),
    confirmPhoto: vi.fn((id: string, body: { key: string }) => {
      calls.push(`confirm:${body.key}`);
      return of([body.key]);
    }),
  };
  const uploader = {
    upload: vi.fn(
      async (
        policy: { key: string },
        blob: Blob,
        contentType: string,
        progress: (n: number) => void,
      ) => {
        calls.push(`upload:${policy.key}`);
        progress(50);
        progress(100);
      },
    ),
  };
  const queue = new UploadQueueService(
    catalog as unknown as CatalogApi,
    uploader as unknown as S3Uploader,
  );
  return { queue, catalog, uploader, calls };
}

describe('sequential listing photo save', () => {
  it('posts the real S3 multipart transport without bearer headers and puts file last', async () => {
    const { catalog } = setup();
    const headers = vi.fn();
    const forms: FormData[] = [];
    const xhr = {
      upload: { onprogress: null },
      status: 204,
      open: vi.fn(),
      setRequestHeader: headers,
      onload: null as null | (() => void),
      send(form: FormData) {
        forms.push(form);
        this.onload?.();
      },
    };
    const uploader = new S3Uploader(() => xhr as unknown as XMLHttpRequest);
    const queue = new UploadQueueService(catalog as unknown as CatalogApi, uploader);
    expect(await queue.start({ id: 'l1', photos: [fresh(1)], request, release: false })).toBe(true);
    expect(headers).not.toHaveBeenCalled();
    expect(Array.from(forms[0].keys()).slice(-2)).toEqual(['Content-Type', 'file']);
    expect(forms[0].get('Content-Type')).toBe('image/jpeg');
    expect(xhr.open).toHaveBeenCalledWith('POST', 'https://s3');
  });
  it.each([1, 3])(
    'releases slots before replacing %i photos in a five-photo listing and saves visual order',
    async (count) => {
      const { queue, calls } = setup();
      const photos = [
        ...Array.from({ length: count }, (_, i) => fresh(i)),
        ...Array.from({ length: 5 - count }, (_, i) => stored(i + count)),
      ];
      expect(await queue.start({ id: 'l1', photos, request, release: true })).toBe(true);
      const retained = Array.from({ length: 5 - count }, (_, i) => `old-${i + count}`);
      expect(calls[0]).toBe(`save:${retained.join(',')}`);
      expect(calls.slice(1, -1)).toEqual(
        Array.from({ length: count }, (_, i) => [
          `policy:new-${i + 1}`,
          `upload:new-${i + 1}`,
          `confirm:new-${i + 1}`,
        ]).flat(),
      );
      expect(calls.at(-1)).toBe(
        `save:${[...Array.from({ length: count }, (_, i) => `new-${i + 1}`), ...retained].join(',')}`,
      );
    },
  );

  it('does not request upload policies if releasing removed slots fails', async () => {
    const { queue, catalog, uploader } = setup();
    catalog.editListing.mockReturnValueOnce(throwError(() => new Error('release')));
    expect(
      await queue.start({ id: 'l1', photos: [stored(1), fresh(1)], request, release: true }),
    ).toBe(false);
    expect(catalog.photoUploadPolicy).not.toHaveBeenCalled();
    expect(uploader.upload).not.toHaveBeenCalled();
    expect(queue.failureStage()).toBe('release');
  });

  it('retries failed uploads with a fresh policy and keeps already confirmed replacements', async () => {
    const { queue, catalog, uploader, calls } = setup();
    uploader.upload.mockRejectedValueOnce(new Error('network'));
    expect(
      await queue.start({ id: 'l1', photos: [fresh(1), fresh(2)], request, release: true }),
    ).toBe(false);
    expect(queue.states()['n2'].stage).toBe('done');
    expect(await queue.retry()).toBe(true);
    expect(calls.filter((c) => c.startsWith('save:'))).toEqual(['save:', 'save:new-3,new-2']);
    expect(catalog.confirmPhoto.mock.calls.map((c) => c[1].key)).toEqual(['new-2', 'new-3']);
    expect(catalog.photoUploadPolicy).toHaveBeenCalledTimes(3);
  });

  it('retries uncertain confirmation with the uploaded key and no new policy or upload', async () => {
    const { queue, catalog, uploader } = setup();
    catalog.confirmPhoto.mockReturnValueOnce(throwError(() => new Error('lost response')));
    expect(await queue.start({ id: 'l1', photos: [fresh(1)], request, release: false })).toBe(
      false,
    );
    expect(await queue.retry()).toBe(true);
    expect(catalog.photoUploadPolicy).toHaveBeenCalledOnce();
    expect(uploader.upload).toHaveBeenCalledOnce();
    expect(catalog.confirmPhoto.mock.calls.map((c) => c[1].key)).toEqual(['new-1', 'new-1']);
  });

  it('continues with stored and confirmed keys only after partial failure', async () => {
    const { queue, catalog, uploader, calls } = setup();
    catalog.confirmPhoto.mockReturnValueOnce(throwError(() => new Error('lost response')));
    expect(
      await queue.start({
        id: 'l1',
        photos: [fresh(1), stored(2), fresh(3)],
        request,
        release: true,
      }),
    ).toBe(false);
    expect(await queue.continueSuccessful()).toBe(true);
    expect(calls.filter((c) => c.startsWith('save:'))).toEqual(['save:old-2', 'save:old-2,new-2']);
    expect(uploader.upload).toHaveBeenCalledTimes(2);
  });

  it('retries final save without uploading again or resetting retained keys', async () => {
    const { queue, catalog, uploader, calls } = setup();
    catalog.editListing.mockImplementationOnce((id, body) => {
      calls.push(`save:${body.photos.join(',')}`);
      return of(response);
    });
    catalog.editListing.mockImplementationOnce((id, body) => {
      calls.push(`save:${body.photos.join(',')}`);
      return throwError(() => new Error('final save'));
    });
    expect(
      await queue.start({ id: 'l1', photos: [stored(1), fresh(2)], request, release: true }),
    ).toBe(false);
    expect(queue.failureStage()).toBe('final');
    expect(await queue.retry()).toBe(true);
    expect(calls.filter((c) => c.startsWith('save:'))).toEqual([
      'save:old-1',
      'save:old-1,new-1',
      'save:old-1,new-1',
    ]);
    expect(uploader.upload).toHaveBeenCalledOnce();
  });

  it('does not start another upload before confirmation and exposes live progress', async () => {
    const { queue, catalog, uploader } = setup();
    const confirm = new Subject<string[]>();
    catalog.confirmPhoto.mockReturnValueOnce(confirm);
    const pending = queue.start({
      id: 'l1',
      photos: [fresh(1), fresh(2)],
      request,
      release: false,
    });
    await vi.waitFor(() => expect(queue.states()['n1'].stage).toBe('confirming'));
    expect(queue.states()['n1'].progress).toBe(100);
    expect(catalog.photoUploadPolicy).toHaveBeenCalledOnce();
    expect(uploader.upload).toHaveBeenCalledOnce();
    expect(queue.busy()).toBe(true);
    confirm.next(['new-1']);
    confirm.complete();
    expect(await pending).toBe(true);
  });
});
