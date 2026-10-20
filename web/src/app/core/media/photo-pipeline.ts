import { Inject, Injectable, InjectionToken } from '@angular/core';

export interface PhotoBrowserApis {
  createImageBitmap(file: Blob, options: ImageBitmapOptions): Promise<ImageBitmap>;
  createCanvas(): HTMLCanvasElement;
  createObjectURL(blob: Blob): string;
  revokeObjectURL(url: string): void;
}

export const PHOTO_BROWSER_APIS = new InjectionToken<PhotoBrowserApis>('PhotoBrowserApis', {
  providedIn: 'root',
  factory: () => ({
    createImageBitmap: (file, options) => createImageBitmap(file, options),
    createCanvas: () => document.createElement('canvas'),
    createObjectURL: (blob) => URL.createObjectURL(blob),
    revokeObjectURL: (url) => URL.revokeObjectURL(url),
  }),
});

export interface PhotoProcessingOptions {
  maxEdge: number;
  maxBytes: number;
}

export interface ProcessedPhoto {
  blob: Blob;
  width: number;
  height: number;
  url: string;
  cleanup(): void;
}

export type PhotoErrorCode =
  'photo-type' | 'photo-empty' | 'photo-too-large' | 'photo-processing-failed';

export class PhotoProcessingError extends Error {
  constructor(readonly code: PhotoErrorCode) {
    super(code);
    this.name = 'PhotoProcessingError';
  }
}

@Injectable({ providedIn: 'root' })
export class PhotoPipeline {
  constructor(@Inject(PHOTO_BROWSER_APIS) private readonly browser: PhotoBrowserApis) {}

  async process(file: File, options: PhotoProcessingOptions): Promise<ProcessedPhoto> {
    if (
      !Number.isInteger(options.maxEdge) ||
      options.maxEdge < 1 ||
      !Number.isInteger(options.maxBytes) ||
      options.maxBytes < 1
    ) {
      throw new RangeError('Photo limits must be positive integers');
    }
    if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) {
      throw new PhotoProcessingError('photo-type');
    }
    if (file.size === 0) throw new PhotoProcessingError('photo-empty');

    let bitmap: ImageBitmap | undefined;
    let canvas: HTMLCanvasElement | undefined;
    try {
      bitmap = await this.browser.createImageBitmap(file, { imageOrientation: 'from-image' });
      if (bitmap.width < 1 || bitmap.height < 1)
        throw new PhotoProcessingError('photo-processing-failed');
      const scale = Math.min(
        1,
        Math.min(options.maxEdge, 2000) / Math.max(bitmap.width, bitmap.height),
      );
      const width = Math.max(1, Math.floor(bitmap.width * scale));
      const height = Math.max(1, Math.floor(bitmap.height * scale));
      canvas = this.browser.createCanvas();
      canvas.width = width;
      canvas.height = height;
      const context = canvas.getContext('2d');
      if (!context) throw new PhotoProcessingError('photo-processing-failed');
      // JPEG has no alpha channel; a white backing preserves transparent input appearance.
      context.fillStyle = '#ffffff';
      context.fillRect(0, 0, width, height);
      context.drawImage(bitmap, 0, 0, width, height);

      for (const quality of [0.85, 0.75, 0.65, 0.55]) {
        const blob = await new Promise<Blob | null>((resolve) =>
          canvas!.toBlob(resolve, 'image/jpeg', quality),
        );
        if (!blob || blob.size === 0 || blob.type !== 'image/jpeg') {
          throw new PhotoProcessingError('photo-processing-failed');
        }
        if (blob.size <= options.maxBytes) {
          const url = this.browser.createObjectURL(blob);
          let cleaned = false;
          return {
            blob,
            width,
            height,
            url,
            cleanup: () => {
              if (!cleaned) {
                this.browser.revokeObjectURL(url);
                cleaned = true;
              }
            },
          };
        }
      }
      throw new PhotoProcessingError('photo-too-large');
    } catch (error) {
      if (error instanceof PhotoProcessingError) throw error;
      throw new PhotoProcessingError('photo-processing-failed');
    } finally {
      bitmap?.close();
      if (canvas) {
        canvas.width = 0;
        canvas.height = 0;
      }
    }
  }
}
