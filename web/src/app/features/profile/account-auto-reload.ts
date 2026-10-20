import { DOCUMENT } from '@angular/common';
import { DestroyRef, inject } from '@angular/core';
import { defer, EMPTY, expand, map, Observable, reduce, throwError } from 'rxjs';
import { CursorPage } from '../../core/api/api-models';

export function startAccountAutoReload(destroyRef: DestroyRef, reload: () => boolean): () => void {
  const document = inject(DOCUMENT);
  const view = document.defaultView;
  if (!view) return () => {};
  let stopped = false;
  let lastStarted = Date.now();
  const update = () => {
    if (
      stopped ||
      document.visibilityState !== 'visible' ||
      !view.navigator.onLine ||
      Date.now() - lastStarted < 1000
    )
      return;
    if (reload()) lastStarted = Date.now();
  };
  const timer = view.setInterval(update, 30000);
  view.addEventListener('focus', update);
  view.addEventListener('online', update);
  document.addEventListener('visibilitychange', update);
  const stop = () => {
    if (stopped) return;
    stopped = true;
    view.clearInterval(timer);
    view.removeEventListener('focus', update);
    view.removeEventListener('online', update);
    document.removeEventListener('visibilitychange', update);
  };
  destroyRef.onDestroy(stop);
  return stop;
}

export function reloadThroughAnchor<T>(
  read: (cursor: string | null) => Observable<CursorPage<T>>,
  key: (row: T) => string,
  anchor: string | null,
): Observable<CursorPage<T>> {
  return defer(() => {
    const cursors = new Set<string>();
    return read(null).pipe(
      expand((page) => {
        if (
          anchor === null ||
          page.nextCursor === null ||
          page.items.some((item) => key(item) === anchor)
        )
          return EMPTY;
        if (cursors.has(page.nextCursor))
          return throwError(() => new Error('Repeated pagination cursor'));
        cursors.add(page.nextCursor);
        return read(page.nextCursor);
      }, 1),
      reduce(
        (result, page) => {
          for (const item of page.items) result.rows.set(key(item), item);
          result.cursor = page.nextCursor;
          return result;
        },
        { rows: new Map<string, T>(), cursor: null as string | null },
      ),
      map((result) => ({ items: [...result.rows.values()], nextCursor: result.cursor })),
    );
  });
}
