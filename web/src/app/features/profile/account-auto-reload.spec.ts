import { DestroyRef } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom, of, Subject, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { CursorPage } from '../../core/api/api-models';
import { reloadThroughAnchor, startAccountAutoReload } from './account-auto-reload';

describe('visible account auto reload', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
  });
  afterEach(() => {
    TestBed.resetTestingModule();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });
  const start = (reload: () => boolean) =>
    TestBed.runInInjectionContext(() => startAccountAutoReload(TestBed.inject(DestroyRef), reload));

  it('reloads every 30 seconds only while visible and coalesces return events', () => {
    const reload = vi.fn(() => true);
    start(reload);
    vi.advanceTimersByTime(30000);
    expect(reload).toHaveBeenCalledTimes(1);
    vi.mocked(Object.getOwnPropertyDescriptor(document, 'visibilityState')!.get!).mockReturnValue(
      'hidden',
    );
    vi.advanceTimersByTime(30000);
    expect(reload).toHaveBeenCalledTimes(1);
    vi.mocked(Object.getOwnPropertyDescriptor(document, 'visibilityState')!.get!).mockReturnValue(
      'visible',
    );
    document.dispatchEvent(new Event('visibilitychange'));
    window.dispatchEvent(new Event('focus'));
    expect(reload).toHaveBeenCalledTimes(2);
  });

  it('retries a previously busy view and reloads on reconnect', () => {
    const reload = vi.fn().mockReturnValueOnce(false).mockReturnValue(true);
    start(reload);
    vi.advanceTimersByTime(30000);
    window.dispatchEvent(new Event('focus'));
    expect(reload).toHaveBeenCalledTimes(2);
    vi.advanceTimersByTime(1001);
    window.dispatchEvent(new Event('online'));
    expect(reload).toHaveBeenCalledTimes(3);
  });

  it('unregisters timers and events on explicit stop or destruction', () => {
    const reload = vi.fn(() => true);
    const stop = start(reload);
    stop();
    stop();
    vi.advanceTimersByTime(30000);
    window.dispatchEvent(new Event('focus'));
    expect(reload).not.toHaveBeenCalled();
    start(reload);
    TestBed.resetTestingModule();
    vi.advanceTimersByTime(30000);
    document.dispatchEvent(new Event('visibilitychange'));
    window.dispatchEvent(new Event('online'));
    expect(reload).not.toHaveBeenCalled();
  });
});

describe('refreshing already loaded cursor pages', () => {
  const key = (item: { id: string }) => item.id;
  const page = (ids: string[], nextCursor: string | null): CursorPage<{ id: string }> => ({
    items: ids.map((id) => ({ id })),
    nextCursor,
  });
  it('refreshes through the oldest loaded row, preserving older pages when new rows arrive', async () => {
    const read = vi
      .fn()
      .mockReturnValueOnce(of(page(['new', 'a'], 'fresh/+==')))
      .mockReturnValueOnce(of(page([], 'fresh-2')))
      .mockReturnValueOnce(of(page(['a', 'b'], 'older')));
    const result = await firstValueFrom(reloadThroughAnchor(read, key, 'b'));
    expect(result).toEqual(page(['new', 'a', 'b'], 'older'));
    expect(read.mock.calls).toEqual([[null], ['fresh/+=='], ['fresh-2']]);
  });
  it('reads only the first page for an initially empty view', async () => {
    const read = vi.fn(() => of(page(['new'], 'more')));
    expect(await firstValueFrom(reloadThroughAnchor(read, key, null))).toEqual(
      page(['new'], 'more'),
    );
    expect(read).toHaveBeenCalledTimes(1);
  });
  it('finishes at exhaustion when a previous row is no longer returned', async () => {
    const read = vi.fn(() => of(page(['new'], null)));
    expect(await firstValueFrom(reloadThroughAnchor(read, key, 'removed'))).toEqual(
      page(['new'], null),
    );
  });
  it('propagates read failures and rejects repeating cursors without emitting partial data', async () => {
    const read = vi
      .fn()
      .mockReturnValueOnce(of(page(['a'], 'loop')))
      .mockReturnValueOnce(of(page([], 'loop')));
    await expect(firstValueFrom(reloadThroughAnchor(read, key, 'b'))).rejects.toThrow('cursor');
    await expect(
      firstValueFrom(reloadThroughAnchor(() => throwError(() => new Error('offline')), key, null)),
    ).rejects.toThrow('offline');
  });
  it('cancels the entire traversal when unsubscribed', () => {
    const delayed = new Subject<CursorPage<{ id: string }>>();
    const read = vi
      .fn()
      .mockReturnValueOnce(of(page(['a'], 'next')))
      .mockReturnValueOnce(delayed);
    const next = vi.fn();
    const subscription = reloadThroughAnchor(read, key, 'b').subscribe(next);
    expect(delayed.observed).toBe(true);
    subscription.unsubscribe();
    expect(delayed.observed).toBe(false);
    expect(next).not.toHaveBeenCalled();
  });
});
