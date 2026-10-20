import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { of, throwError, Subject } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { provideTestAppConfig } from '../../../testing/test-config';
import { CatalogApi } from '../../core/api/catalog-api';
import { FavouriteEntry } from '../../core/api/api-models';
import { HomeFavourites } from '../home-search/home-favourites.service';
import { FAVOURITES_ROUTES } from './favourites.routes';
import { AccountSummary } from '../profile/account-summary';

const entry = (listingId: string, coverUrl: string | null = null): FavouriteEntry => ({
  listingId,
  title: listingId,
  price: '12.00',
  photos: [],
  coverUrl,
  status: 'AVAILABLE',
  createdAt: '2026-10-01T00:00:00Z',
});
describe('standalone favourites', () => {
  it('cancels an in-flight next page when removal resets pagination', async () => {
    const stale = new Subject<unknown>();
    const favourites = vi
      .fn()
      .mockReturnValueOnce(
        of({ favourites: [entry('one')], page: 0, size: 20, totalElements: 2, totalPages: 2 }),
      )
      .mockReturnValueOnce(stale)
      .mockReturnValueOnce(
        of({ favourites: [entry('two')], page: 0, size: 20, totalElements: 1, totalPages: 1 }),
      );
    const { harness, root } = await open(favourites);
    root.querySelector<HTMLButtonElement>('[data-load-favourites]')!.click();
    harness.detectChanges();
    root.querySelector<HTMLButtonElement>('button.favourite')!.click();
    harness.detectChanges();
    stale.next({
      favourites: [entry('stale')],
      page: 1,
      size: 20,
      totalElements: 2,
      totalPages: 2,
    });
    harness.detectChanges();
    expect(root.querySelector('[data-favourite-entry="two"]')).not.toBeNull();
    expect(root.querySelector('[data-favourite-entry="stale"]')).toBeNull();
  });
  it('restarts page zero after removal so shifting page offsets cannot skip an unseen entry', async () => {
    const favourites = vi
      .fn()
      .mockReturnValueOnce(
        of({ favourites: [entry('one')], page: 0, size: 20, totalElements: 2, totalPages: 2 }),
      )
      .mockReturnValueOnce(
        of({ favourites: [entry('two')], page: 0, size: 20, totalElements: 1, totalPages: 1 }),
      );
    const { harness, root } = await open(favourites);
    root.querySelector<HTMLButtonElement>('button.favourite')!.click();
    harness.detectChanges();
    expect(favourites.mock.calls).toEqual([[{ page: 0, size: 20 }], [{ page: 0, size: 20 }]]);
    expect(root.querySelector('[data-favourite-entry="two"]')).not.toBeNull();
    expect(root.querySelector('[data-load-favourites]')).toBeNull();
  });
  afterEach(() => TestBed.resetTestingModule());
  async function open(
    favourites = vi
      .fn()
      .mockReturnValue(of({ favourites: [], page: 0, size: 20, totalElements: 0, totalPages: 0 })),
    toggle = vi.fn().mockReturnValue(of(true)),
  ) {
    TestBed.configureTestingModule({
      providers: [
        provideTestAppConfig(),
        provideRouter(
          [{ path: 'favourites', children: FAVOURITES_ROUTES }],
          withComponentInputBinding(),
        ),
        { provide: CatalogApi, useValue: { favourites } },
        { provide: HomeFavourites, useValue: { toggle } },
      ],
    });
    const harness = await RouterTestingHarness.create('/favourites');
    await harness.fixture.whenStable();
    harness.detectChanges();
    return { harness, root: harness.routeNativeElement as HTMLElement };
  }
  it('pages without cursor, deduplicates in order, renders covers and stops at totalPages', async () => {
    const favourites = vi
      .fn()
      .mockReturnValueOnce(
        of({
          favourites: [entry('one', '/cover.jpg')],
          page: 0,
          size: 20,
          totalElements: 2,
          totalPages: 2,
        }),
      )
      .mockReturnValueOnce(
        of({
          favourites: [entry('one'), entry('two')],
          page: 1,
          size: 20,
          totalElements: 2,
          totalPages: 2,
        }),
      );
    const { harness, root } = await open(favourites);
    expect(root.querySelectorAll('[data-favourite-entry]')).toHaveLength(1);
    expect(root.querySelector('img')?.getAttribute('src')).toBe('/cover.jpg');
    root.querySelector<HTMLButtonElement>('[data-load-favourites]')!.click();
    await harness.fixture.whenStable();
    harness.detectChanges();
    expect(favourites.mock.calls).toEqual([[{ page: 0, size: 20 }], [{ page: 1, size: 20 }]]);
    expect(
      [...root.querySelectorAll('[data-favourite-entry]')].map((row) =>
        row.getAttribute('data-favourite-entry'),
      ),
    ).toEqual(['one', 'two']);
    expect(root.querySelector('[data-load-favourites]')).toBeNull();
    expect(root.querySelector('a[href="/listings/two"]')).not.toBeNull();
    expect(root.querySelector('.placeholder')).not.toBeNull();
  });
  it.each([true, false])(
    'removes only after shared favourite mutation success=%s',
    async (success) => {
      const favourites = vi
        .fn()
        .mockReturnValue(
          of({ favourites: [entry('one')], page: 0, size: 20, totalElements: 1, totalPages: 1 }),
        );
      const toggle = vi.fn().mockReturnValue(of(success));
      const { harness, root } = await open(favourites, toggle);
      const refreshSummary = vi.spyOn(TestBed.inject(AccountSummary), 'refresh');
      root.querySelector<HTMLButtonElement>('button.favourite')!.click();
      await harness.fixture.whenStable();
      harness.detectChanges();
      expect(toggle).toHaveBeenCalledExactlyOnceWith('one', false);
      expect(refreshSummary).toHaveBeenCalledTimes(success ? 1 : 0);
      expect(root.querySelectorAll('[data-favourite-entry]')).toHaveLength(success ? 0 : 1);
      expect(root.textContent).toContain(success ? 'No favourites yet' : 'Try again');
    },
  );
  it('retries a failed next page without advancing or discarding entries', async () => {
    const favourites = vi
      .fn()
      .mockReturnValueOnce(
        of({ favourites: [entry('one')], page: 0, size: 20, totalElements: 2, totalPages: 2 }),
      )
      .mockReturnValueOnce(throwError(() => new Error('offline')))
      .mockReturnValueOnce(
        of({ favourites: [entry('two')], page: 1, size: 20, totalElements: 2, totalPages: 2 }),
      );
    const { harness, root } = await open(favourites);
    root.querySelector<HTMLButtonElement>('[data-load-favourites]')!.click();
    harness.detectChanges();
    root.querySelector<HTMLButtonElement>('app-error-state button')!.click();
    harness.detectChanges();
    expect(favourites.mock.calls.map((call) => call[0].page)).toEqual([0, 1, 1]);
    expect(root.querySelectorAll('[data-favourite-entry]')).toHaveLength(2);
  });
});
