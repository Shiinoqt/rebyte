import { Inject, Injectable, InjectionToken } from '@angular/core';
import type { UploadPolicy } from '../api/api-models';

export const S3_XHR_FACTORY = new InjectionToken<() => XMLHttpRequest>('S3XhrFactory', {
  providedIn: 'root',
  factory: () => () => new XMLHttpRequest(),
});

@Injectable({ providedIn: 'root' })
export class S3Uploader {
  constructor(@Inject(S3_XHR_FACTORY) private readonly createXhr: () => XMLHttpRequest) {}

  upload(
    policy: UploadPolicy,
    blob: Blob,
    contentType: string,
    onProgress: (percent: number) => void = () => {},
    signal?: AbortSignal,
  ): Promise<void> {
    return new Promise((resolve, reject) => {
      const aborted = () => new DOMException('Upload aborted', 'AbortError');
      if (signal?.aborted) {
        reject(aborted());
        return;
      }

      const form = new FormData();
      form.append('key', policy.key);
      form.append('x-amz-algorithm', policy.algorithm);
      form.append('x-amz-credential', policy.credential);
      form.append('x-amz-date', policy.date);
      if (policy.securityToken) form.append('x-amz-security-token', policy.securityToken);
      form.append('policy', policy.policy);
      form.append('x-amz-signature', policy.signature);
      form.append('Content-Type', contentType);
      // S3 ignores form fields after file, so it must be appended last.
      form.append('file', blob);

      const xhr = this.createXhr();
      let settled = false;
      let lastPercent = -1;
      const finish = (error?: unknown) => {
        if (settled) return;
        settled = true;
        signal?.removeEventListener('abort', abort);
        xhr.upload.onprogress = null;
        xhr.onload = null;
        xhr.onerror = null;
        xhr.onabort = null;
        xhr.ontimeout = null;
        if (error !== undefined) reject(error);
        else resolve();
      };
      const abort = () => {
        finish(aborted());
        xhr.abort();
      };
      const report = (percent: number) => {
        if (!settled && percent > lastPercent) {
          lastPercent = percent;
          onProgress(percent);
        }
      };
      xhr.upload.onprogress = (event) => {
        if (
          event.lengthComputable &&
          event.total > 0 &&
          Number.isFinite(event.loaded) &&
          Number.isFinite(event.total)
        ) {
          report(Math.max(0, Math.min(100, Math.round((event.loaded / event.total) * 100))));
        }
      };
      xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          report(100);
          finish();
        } else finish(new Error(`s3-${xhr.status}`));
      };
      xhr.onerror = () => finish(new Error('s3-network'));
      xhr.onabort = () => finish(aborted());
      xhr.ontimeout = () => finish(new Error('s3-timeout'));
      try {
        // Raw XHR bypasses API interceptors; the browser supplies the multipart boundary.
        xhr.open('POST', policy.url);
        signal?.addEventListener('abort', abort, { once: true });
        xhr.send(form);
      } catch (error) {
        finish(error);
      }
    });
  }
}
