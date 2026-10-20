import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, Subject, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ListingDetailResponse, VersionResponse } from '../../core/api/api-models';
import { CatalogApi } from '../../core/api/catalog-api';
import { UserApi } from '../../core/api/user-api';
import { AuthStore } from '../../core/auth/auth.store';
import { S3Uploader, S3_XHR_FACTORY } from '../../core/media/s3-uploader';
import {
  ARTIFACT_MAX_BYTES,
  compareVersions,
  ReleaseService,
  versionError,
} from './release.service';
import { ReleasePage } from './release.page';

const version = (number = '1'): VersionResponse => ({
  number,
  release: '2026-10-12T12:00:00Z',
  notes: null,
  artifactAvailable: false,
  current: true,
});
const policy = {
  url: 'https://bucket.s3.eu-south-1.amazonaws.com',
  key: 'software/file',
  algorithm: 'test',
  credential: 'test',
  date: 'test',
  policy: 'test',
  signature: 'test',
  securityToken: null,
};

function harness(
  options: { type?: string; owner?: string; developer?: boolean } = {},
  render = false,
) {
  let cleanup = () => {};
  const auth = {
    identity: () => ({ sub: 'owner' }),
    registerCleanup: (fn: () => void) => {
      cleanup = fn;
      return () => {};
    },
  };
  const api = {
    listing: vi.fn(() =>
      of({
        id: 'software',
        title: 'Toolkit',
        type: options.type ?? 'SOFTWARE',
        sellerSub: options.owner ?? 'owner',
      } as ListingDetailResponse),
    ),
    versions: vi.fn(() => of([version()])),
    releaseVersion: vi.fn((_id: string, body: { number: string; notes: string | null }) =>
      of(version(body.number)),
    ),
    artifactUploadPolicy: vi.fn(() => of(policy)),
    confirmArtifact: vi.fn(() => of({ ...version(), artifactAvailable: true })),
  };
  const uploader = { upload: vi.fn(async () => {}) };
  TestBed.configureTestingModule({
    providers: [
      ReleaseService,
      { provide: CatalogApi, useValue: api },
      {
        provide: UserApi,
        useValue: { getPublicProfile: () => of({ isDeveloper: options.developer ?? true }) },
      },
      { provide: AuthStore, useValue: auth },
      { provide: S3Uploader, useValue: uploader },
    ],
  });
  if (render) {
    TestBed.configureTestingModule({
      imports: [ReleasePage],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { paramMap: of(convertToParamMap({ id: 'software' })) },
        },
      ],
    });
    TestBed.overrideComponent(ReleasePage, { set: { providers: [] } });
  }
  const service = TestBed.inject(ReleaseService);
  service.load('software');
  return { service, api, uploader, cleanup: () => cleanup() };
}

afterEach(() => TestBed.resetTestingModule());

describe('software version contract', () => {
  it.each([
    '',
    ' 1',
    '1 ',
    '1\n',
    '1..2',
    '1.',
    '-1',
    '1a',
    '1.2.3.4.5.6.7.8.9',
    '1000000000',
    '１',
  ])('rejects invalid %s', (value) => {
    expect(versionError(value, [])).toBe('invalid-version-number');
  });
  it('accepts eight components of nine digits and compares numerically', () => {
    expect(versionError(Array(8).fill('999999999').join('.'), [])).toBeNull();
    expect(compareVersions('1.10', '1.9')).toBeGreaterThan(0);
    expect(compareVersions('01.0', '1')).toBe(0);
    expect(versionError('1.0', [version('1')])).toBe('duplicate-version-number');
    expect(versionError('0.9', [version('1')])).toBe('version-number-not-increasing');
  });
});

describe('developer release orchestration', () => {
  it.each([{ type: 'HARDWARE' }, { owner: 'other' }, { developer: false }])(
    'refuses inaccessible listing %j',
    async (options) => {
      const { service, api } = harness(options);
      expect(service.status()).toBe('forbidden');
      await service.release({ number: '2', notes: null });
      expect(api.releaseVersion).not.toHaveBeenCalled();
      expect(api.versions).not.toHaveBeenCalled();
    },
  );
  it('merges the authoritative new version and clears prior current flags', async () => {
    const { service, api } = harness();
    await service.release({ number: '2', notes: 'New tools' });
    expect(api.releaseVersion).toHaveBeenCalledWith('software', {
      number: '2',
      notes: 'New tools',
    });
    expect(service.versions().map((v) => [v.number, v.current])).toEqual([
      ['2', true],
      ['1', false],
    ]);
  });
  it.each(['duplicate-version-number', 'invalid-version-number', 'version-number-not-increasing'])(
    'maps server %s',
    async (code) => {
      const { service, api } = harness();
      api.releaseVersion.mockImplementation(() => throwError(() => ({ code })));
      await service.release({ number: '2', notes: null });
      expect(service.releaseError()).toBe(code);
      expect(service.releasing()).toBe(false);
    },
  );
  it('suppresses concurrent releases', async () => {
    const { service, api } = harness();
    const reply = new Subject<VersionResponse>();
    api.releaseVersion.mockReturnValue(reply);
    const pending = service.release({ number: '2', notes: null });
    await service.release({ number: '3', notes: null });
    expect(api.releaseVersion).toHaveBeenCalledTimes(1);
    reply.next(version('2'));
    reply.complete();
    await pending;
  });
  it('performs policy, direct upload and confirm in order with octet-stream', async () => {
    const { service, api, uploader } = harness();
    const events: string[] = [];
    api.artifactUploadPolicy.mockImplementation(() => {
      events.push('policy');
      return of(policy);
    });
    uploader.upload.mockImplementation(async () => {
      events.push('upload');
    });
    api.confirmArtifact.mockImplementation(() => {
      events.push('confirm');
      return of({ ...version(), artifactAvailable: true });
    });
    const file = new File(['abc'], 'installer.zip', { type: 'application/zip' });
    service.selectFile('1', file);
    await service.upload('1');
    expect(events).toEqual(['policy', 'upload', 'confirm']);
    expect(uploader.upload).toHaveBeenCalledWith(
      policy,
      file,
      'application/octet-stream',
      expect.any(Function),
      expect.any(AbortSignal),
    );
    expect(api.confirmArtifact).toHaveBeenCalledWith('software', '1', { key: policy.key });
    expect(api.versions).toHaveBeenCalledTimes(2);
  });
  it.each([0, ARTIFACT_MAX_BYTES + 1])('rejects file size %i before policy', async (size) => {
    const { service, api } = harness();
    const file = new File(['x'], 'test');
    Object.defineProperty(file, 'size', { value: size });
    service.selectFile('1', file);
    await service.upload('1');
    expect(api.artifactUploadPolicy).not.toHaveBeenCalled();
    expect(service.uploads()['1'].error).toBe('invalid-artifact-size');
  });
  it('accepts exactly 100 MiB and retains file for fresh-policy retry', async () => {
    const { service, api, uploader } = harness();
    const file = new File(['x'], 'test');
    Object.defineProperty(file, 'size', { value: ARTIFACT_MAX_BYTES });
    service.selectFile('1', file);
    uploader.upload.mockRejectedValueOnce(new Error('s3-403'));
    await service.upload('1');
    expect(api.confirmArtifact).not.toHaveBeenCalled();
    expect(service.uploads()['1'].file).toBe(file);
    await service.upload('1');
    expect(api.artifactUploadPolicy).toHaveBeenCalledTimes(2);
  });
  it('reports confirm failure and reconciles already attached conflicts', async () => {
    const { service, api } = harness();
    service.selectFile('1', new File(['x'], 'test'));
    api.confirmArtifact.mockReturnValueOnce(throwError(() => ({ code: 'artifact-not-uploaded' })));
    await service.upload('1');
    expect(service.uploads()['1'].error).toBe('artifact-not-uploaded');
    api.confirmArtifact.mockReturnValueOnce(
      throwError(() => ({ code: 'artifact-already-attached' })),
    );
    await service.upload('1');
    expect(api.versions).toHaveBeenCalledTimes(2);
  });
  it('never uploads an attached version', async () => {
    const { service, api } = harness();
    service.versions.set([{ ...version(), artifactAvailable: true }]);
    service.selectFile('1', new File(['x'], 'test'));
    await service.upload('1');
    expect(api.artifactUploadPolicy).not.toHaveBeenCalled();
  });
  it('sign-out aborts uploads and prevents confirmation', async () => {
    const { service, api, uploader, cleanup } = harness();
    let finish!: () => void;
    uploader.upload.mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve;
        }),
    );
    service.selectFile('1', new File(['x'], 'test'));
    const pending = service.upload('1');
    await Promise.resolve();
    cleanup();
    finish();
    await pending;
    expect(api.confirmArtifact).not.toHaveBeenCalled();
    expect(service.listing()).toBeNull();
  });
});

it('raw S3 multipart has octet-stream before file and no authorization headers', async () => {
  let form!: FormData;
  const xhr = {
    upload: {},
    status: 204,
    open: vi.fn(),
    send: vi.fn((data: FormData) => {
      form = data;
      queueMicrotask(() => (xhr as any).onload());
    }),
    setRequestHeader: vi.fn(),
    abort: vi.fn(),
  };
  TestBed.configureTestingModule({ providers: [{ provide: S3_XHR_FACTORY, useValue: () => xhr }] });
  await TestBed.inject(S3Uploader).upload(
    policy,
    new File(['x'], 'file.zip', { type: 'application/zip' }),
    'application/octet-stream',
  );
  expect(form.get('Content-Type')).toBe('application/octet-stream');
  expect([...form.keys()].at(-1)).toBe('file');
  expect(xhr.setRequestHeader).not.toHaveBeenCalled();
});

it('does not let a late upload refresh erase a newly released version', async () => {
  const { service, api } = harness();
  const refresh = new Subject<VersionResponse[]>();
  api.versions.mockReturnValueOnce(refresh);
  service.selectFile('1', new File(['x'], 'installer'));
  const pending = service.upload('1');
  await vi.waitFor(() => expect(api.versions).toHaveBeenCalledTimes(2));
  await service.release({ number: '2', notes: null });
  refresh.next([{ ...version(), artifactAvailable: true }]);
  refresh.complete();
  await pending;
  expect(service.versions().map((v) => v.number)).toEqual(['2', '1']);
});

describe('release screen confirmation', () => {
  function screen() {
    const context = harness({}, true);
    const fixture = TestBed.createComponent(ReleasePage);
    fixture.detectChanges();
    return { ...context, fixture, page: fixture.componentInstance };
  }
  it('chooses a file first and uploads only after the explicit second action', async () => {
    const { page, api, service } = screen();
    const input = document.createElement('input');
    const picker = vi.spyOn(input, 'click').mockImplementation(() => {});
    page.attachmentAction('1', input);
    expect(picker).toHaveBeenCalledOnce();
    expect(api.artifactUploadPolicy).not.toHaveBeenCalled();
    service.selectFile('1', new File(['installer'], 'installer.zip'));
    expect(page.attachmentLabel('1')).toBe('Upload installer');
    expect(api.artifactUploadPolicy).not.toHaveBeenCalled();
    page.attachmentAction('1', input);
    await vi.waitFor(() => expect(api.confirmArtifact).toHaveBeenCalledOnce());
  });
  it('requires the permanent confirmation and allows cancelling without a write', async () => {
    const { page, fixture, api } = screen();
    page.openRelease();
    fixture.detectChanges();
    page.form.setValue({ number: '2', notes: 'Improved tools' });
    await page.confirmRelease();
    expect(api.releaseVersion).not.toHaveBeenCalled();
    page.reviewRelease();
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('This release is permanent');
    expect(api.releaseVersion).not.toHaveBeenCalled();
    page.closeDialog();
    await page.confirmRelease();
    expect(api.releaseVersion).not.toHaveBeenCalled();
  });
  it('posts only after confirmation and maps conflict onto the number field', async () => {
    const { page, fixture, api } = screen();
    page.openRelease();
    page.form.setValue({ number: '2', notes: '' });
    page.reviewRelease();
    fixture.detectChanges();
    api.releaseVersion.mockReturnValueOnce(
      throwError(() => ({ code: 'duplicate-version-number' })),
    );
    await page.confirmRelease();
    fixture.detectChanges();
    expect(page.dialog()).toBe('form');
    expect(page.fieldError()).toBe('duplicate-version-number');
    expect(
      fixture.nativeElement.querySelector('#version-number').getAttribute('aria-invalid'),
    ).toBe('true');
    page.form.controls.number.setValue('3');
    page.reviewRelease();
    await page.confirmRelease();
    expect(api.releaseVersion).toHaveBeenLastCalledWith('software', { number: '3', notes: null });
    expect(page.dialog()).toBeNull();
  });
  it('prevents equivalent versions from reaching confirmation', () => {
    const { page, api } = screen();
    page.openRelease();
    page.form.controls.number.setValue('1.0');
    page.reviewRelease();
    expect(page.dialog()).toBe('form');
    expect(page.fieldError()).toBe('duplicate-version-number');
    expect(api.releaseVersion).not.toHaveBeenCalled();
  });
});
