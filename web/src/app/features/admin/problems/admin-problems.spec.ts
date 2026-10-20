import { computed, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter, Router } from '@angular/router';
import { BehaviorSubject, of, Subject, throwError } from 'rxjs';
import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  AdminProblemItem,
  AdminProblemPage,
  AdminProblemQuery,
} from '../../../core/api/api-models';
import { OrderApi } from '../../../core/api/order-api';
import { AuthStore } from '../../../core/auth/auth.store';
import { AdminProblemsStore, noteError, trimNote } from './admin-problems.store';
import { AdminProblemsPage } from './admin-problems.page';

const problem = (
  id = 'problem-1',
  status: AdminProblemItem['status'] = 'OPEN',
): AdminProblemItem => ({
  id,
  orderId: 'order-1',
  reason: 'DAMAGED',
  description: 'The screen arrived cracked.',
  status,
  outcome: null,
  resolutionNote: null,
  resolvedBy: null,
  createdAt: '2026-10-12T09:00:00Z',
  resolvedAt: null,
});
const page = (items = [problem()], nextCursor: string | null = null): AdminProblemPage => ({
  items,
  nextCursor,
});

function harness(admin = true, params?: BehaviorSubject<ReturnType<typeof convertToParamMap>>) {
  const identity = signal<{ sub: string } | null>({ sub: 'admin-1' });
  const role = signal(admin);
  let cleanup = () => {};
  const api = {
    adminProblems: vi.fn((_query: AdminProblemQuery) => of(page())),
    adminRefundProblem: vi.fn(() => of(problem('problem-1', 'REFUND_PENDING'))),
    adminDismissProblem: vi.fn(() =>
      of({ ...problem(), status: 'RESOLVED' as const, outcome: 'DISMISS' as const }),
    ),
  };
  TestBed.configureTestingModule({
    providers: [
      AdminProblemsStore,
      { provide: OrderApi, useValue: api },
      {
        provide: AuthStore,
        useValue: {
          identity,
          isAdmin: computed(() => role() && !!identity()),
          registerCleanup: (fn: () => void) => {
            cleanup = fn;
            return () => {};
          },
        },
      },
    ],
  });
  if (params) {
    TestBed.configureTestingModule({
      imports: [AdminProblemsPage],
      providers: [
        provideRouter([]),
        { provide: ActivatedRoute, useValue: { queryParamMap: params } },
      ],
    });
    TestBed.overrideComponent(AdminProblemsPage, { set: { providers: [] } });
  }
  const store = TestBed.inject(AdminProblemsStore);
  TestBed.tick();
  return { store, api, identity, role, cleanup: () => cleanup() };
}

afterEach(() => TestBed.resetTestingModule());

describe('admin problem queue', () => {
  it('loads both active statuses by omitting status, with bounded page size', () => {
    const { store, api } = harness();
    expect(api.adminProblems).toHaveBeenCalledWith({ size: 20 });
    expect(store.items()).toEqual([problem()]);
    expect(store.state()).toBe('ready');
  });

  it.each(['OPEN', 'REFUND_PENDING'] as const)(
    'sends the %s filter and reuses its cached page',
    (filter) => {
      const { store, api } = harness();
      store.select(filter);
      expect(api.adminProblems).toHaveBeenLastCalledWith({ size: 20, status: filter });
      store.select('ALL');
      store.select(filter);
      expect(api.adminProblems).toHaveBeenCalledTimes(2);
    },
  );

  it('continues an empty page using the opaque cursor unchanged', () => {
    const { store, api } = harness();
    api.adminProblems
      .mockReturnValueOnce(of(page([], 'opaque/+==')))
      .mockReturnValueOnce(of(page([problem('next')])));
    store.reload();
    expect(store.items()).toEqual([]);
    expect(store.cursor()).toBe('opaque/+==');
    store.more();
    expect(api.adminProblems).toHaveBeenLastCalledWith({ size: 20, cursor: 'opaque/+==' });
    expect(store.items()[0].id).toBe('next');
    expect(store.cursor()).toBeNull();
  });

  it('keeps the filter and cursor together and deduplicates page overlap', () => {
    const { store, api } = harness();
    api.adminProblems
      .mockReturnValueOnce(of(page([problem()], 'open-cursor')))
      .mockReturnValueOnce(of(page([problem(), problem('next')])));
    store.select('OPEN');
    store.select('ALL');
    store.select('OPEN');
    store.more();
    expect(api.adminProblems).toHaveBeenLastCalledWith({
      size: 20,
      status: 'OPEN',
      cursor: 'open-cursor',
    });
    expect(store.items().map((item) => item.id)).toEqual(['problem-1', 'next']);
  });

  it('preserves the previous page and cursor when continuation fails, allowing retry', () => {
    const { store, api } = harness();
    api.adminProblems
      .mockReturnValueOnce(of(page([problem()], 'next')))
      .mockReturnValueOnce(throwError(() => ({ status: 503 })))
      .mockReturnValueOnce(of(page([problem('next')])));
    store.reload();
    store.more();
    expect(store.items()).toHaveLength(1);
    expect(store.cursor()).toBe('next');
    expect(store.error()).toContain('Try again');
    store.more();
    expect(store.items()).toHaveLength(2);
  });

  it('cancels a stale filter response instead of contaminating the new filter', () => {
    const { store, api } = harness();
    const delayed = new Subject<AdminProblemPage>();
    api.adminProblems.mockReturnValueOnce(delayed);
    store.select('OPEN');
    store.select('REFUND_PENDING');
    delayed.next(page([problem('stale')]));
    expect(store.items().some((item) => item.id === 'stale')).toBe(false);
    store.select('OPEN');
    expect(api.adminProblems).toHaveBeenLastCalledWith({ size: 20, status: 'OPEN' });
  });

  it('blocks duplicate continuation requests', () => {
    const { store, api } = harness();
    api.adminProblems.mockReturnValueOnce(of(page([], 'next')));
    store.reload();
    api.adminProblems.mockReturnValueOnce(new Subject<AdminProblemPage>());
    store.more();
    store.more();
    expect(api.adminProblems).toHaveBeenCalledTimes(3);
  });

  it('does not request or render admin data for a non-admin', () => {
    const { store, api } = harness(false);
    store.select('OPEN');
    store.more();
    store.reload();
    expect(api.adminProblems).not.toHaveBeenCalled();
    expect(store.state()).toBe('forbidden');
    expect(store.items()).toEqual([]);
  });

  it.each([401, 403])('clears all cached data on backend %s', (status) => {
    const { store, api } = harness();
    api.adminProblems.mockReturnValueOnce(throwError(() => ({ status })));
    store.select('OPEN');
    expect(store.state()).toBe('forbidden');
    expect(store.items()).toEqual([]);
    store.select('ALL');
    expect(store.items()).toEqual([]);
    expect(api.adminProblems).toHaveBeenCalledTimes(2);
  });

  it('clears sensitive data and cancels requests on sign-out', () => {
    const { store, api, identity, cleanup } = harness();
    const delayed = new Subject<AdminProblemPage>();
    api.adminProblems.mockReturnValueOnce(delayed);
    store.reload();
    cleanup();
    identity.set(null);
    TestBed.tick();
    delayed.next(page([problem('stale')]));
    expect(store.items()).toEqual([]);
    expect(store.state()).toBe('forbidden');
  });

  it('refetches data for a different admin identity', () => {
    const { store, api, identity } = harness();
    api.adminProblems.mockReturnValueOnce(of(page([problem('new-admin')])));
    identity.set({ sub: 'admin-2' });
    TestBed.tick();
    expect(store.items()[0].id).toBe('new-admin');
    expect(api.adminProblems).toHaveBeenCalledTimes(2);
  });
});

function render(admin = true) {
  const params = new BehaviorSubject(convertToParamMap({ status: 'OPEN' }));
  const result = harness(admin, params);
  const fixture = TestBed.createComponent(AdminProblemsPage);
  fixture.detectChanges();
  return { ...result, params, fixture, root: fixture.nativeElement as HTMLElement };
}

describe('admin problem screen', () => {
  it('restores the filter from the URL and changes it without dropping other query parameters', async () => {
    const { fixture, params, store, root } = render();
    expect(store.filter()).toBe('OPEN');
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
    const select = root.querySelector('select')!;
    select.value = 'REFUND_PENDING';
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    expect(navigate).toHaveBeenCalledWith(
      [],
      expect.objectContaining({
        queryParams: { status: 'REFUND_PENDING' },
        queryParamsHandling: 'merge',
      }),
    );
    params.next(convertToParamMap({ status: 'OPEN' }));
    fixture.detectChanges();
    expect(select.value).toBe('OPEN');
    expect(root.querySelector('a')?.getAttribute('href')).toBe('/orders/order-1');
  });

  it('renders optional resolution audit fields and expandable descriptions', () => {
    const { fixture, root, api, store } = render();
    api.adminProblems.mockReturnValueOnce(
      of(
        page([
          {
            ...problem(),
            description: 'Long report '.repeat(25),
            outcome: 'DISMISS',
            resolvedBy: 'admin-42',
            resolvedAt: '2026-10-12T10:00:00Z',
            resolutionNote: 'Evidence checked.',
          },
        ]),
      ),
    );
    store.reload();
    fixture.detectChanges();
    expect(root.textContent).toContain('admin-42');
    expect(root.textContent).toContain('Evidence checked.');
    expect(root.textContent).toContain('Dismissed');
    expect(root.querySelectorAll('details')).toHaveLength(2);
  });

  it('keeps Show more available on empty filtered pages', () => {
    const { fixture, root, api, store } = render();
    api.adminProblems.mockReturnValueOnce(of(page([], 'continue')));
    store.reload();
    fixture.detectChanges();
    expect(root.textContent).toContain('No problems on this page');
    expect(root.querySelector('app-load-more button')).not.toBeNull();
  });

  it('renders a uniform access state without leaked rows or controls', () => {
    const { root } = render(false);
    expect(root.textContent).toContain('Admin access required');
    expect(root.querySelector('table')).toBeNull();
    expect(root.querySelector('select')).toBeNull();
  });

  it('opens a refund confirmation without posting and cancels without a command', () => {
    const { fixture, root, api } = render();
    root.querySelector<HTMLButtonElement>('.row-actions button')!.click();
    fixture.detectChanges();
    expect(root.querySelector('dialog')).not.toBeNull();
    expect(root.textContent).toContain('This cannot be undone');
    expect(api.adminRefundProblem).not.toHaveBeenCalled();
    fixture.componentInstance.closeDialog();
    fixture.detectChanges();
    expect(root.querySelector('dialog')).toBeNull();
    expect(api.adminRefundProblem).not.toHaveBeenCalled();
  });

  it('requires a dismiss reason and submits only through the confirmation form', () => {
    const { fixture, root, api } = render();
    root.querySelector<HTMLButtonElement>('.row-actions .quiet')!.click();
    fixture.detectChanges();
    const component = fixture.componentInstance;
    expect(root.querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(true);
    component.confirm();
    expect(api.adminDismissProblem).not.toHaveBeenCalled();
    component.note.setValue('  Buyer supplied evidence  ');
    fixture.detectChanges();
    root
      .querySelector('form')!
      .dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    fixture.detectChanges();
    expect(api.adminDismissProblem).toHaveBeenCalledWith('problem-1', {
      note: 'Buyer supplied evidence',
    });
    expect(root.querySelector('dialog')).toBeNull();
  });

  it('disables actions while a confirmed command is pending and never auto-reposts', () => {
    const { fixture, root, api } = render();
    api.adminRefundProblem.mockReturnValueOnce(new Subject<AdminProblemItem>());
    fixture.componentInstance.open(problem(), 'refund');
    fixture.detectChanges();
    fixture.componentInstance.confirm();
    fixture.detectChanges();
    expect(root.querySelector<HTMLButtonElement>('button[type=submit]')!.disabled).toBe(true);
    expect(
      [...root.querySelectorAll<HTMLButtonElement>('.row-actions button')].every(
        (button) => button.disabled,
      ),
    ).toBe(true);
    fixture.componentInstance.confirm();
    expect(api.adminRefundProblem).toHaveBeenCalledTimes(1);
    fixture.componentInstance.closeDialog();
    fixture.detectChanges();
    expect(root.textContent).toContain('Submitting request...');
    expect(api.adminRefundProblem).toHaveBeenCalledTimes(1);
  });

  it('clears an open dialog and its note when another admin signs in', () => {
    const { fixture, root, identity } = render();
    fixture.componentInstance.open(problem(), 'refund');
    fixture.componentInstance.note.setValue('private previous admin note');
    fixture.detectChanges();
    identity.set({ sub: 'another-admin' });
    TestBed.tick();
    fixture.detectChanges();
    expect(root.querySelector('dialog')).toBeNull();
    expect(fixture.componentInstance.note.value).toBe('');
  });
});

describe('problem resolution notes', () => {
  it('normalizes optional blank notes and preserves permitted multiline Unicode text', () => {
    expect(trimNote(' \t\r\n ')).toBeNull();
    expect(trimNote('  Verified: è\nSecond\tline  ')).toBe('Verified: è\nSecond\tline');
    expect(noteError(' \t\n ', false)).toBeNull();
    expect(noteError(' \t\n ', true)).toContain('reason');
  });
  it.each([false, true])(
    'validates the trimmed 1000-character boundary, required=%s',
    (required) => {
      expect(noteError(' ' + 'a'.repeat(1000) + ' ', required)).toBeNull();
      expect(noteError('a'.repeat(1001), required)).toContain('1,000');
    },
  );
  it.each(['\u0000', '\u0008', '\u000b', '\u000c', '\u000e', '\u001f', '\u007f'])(
    'rejects interior prohibited control %j',
    (character) => {
      expect(noteError('before' + character + 'after', false)).toContain('control');
    },
  );
  it('matches Java trim and NotBlank semantics for Unicode whitespace', () => {
    expect(trimNote('\u00a0word\u00a0')).toBe('\u00a0word\u00a0');
    expect(noteError('\u2003', true)).toContain('reason');
    expect(noteError('\u00a0', true)).toBeNull();
  });
});

describe('admin problem commands', () => {
  it('requests a refund with an optional trimmed note and refetches the active filter', () => {
    const { store, api } = harness();
    store.select('OPEN');
    api.adminProblems.mockReturnValueOnce(of(page([], null)));
    store.resolve('problem-1', 'refund', '  checked  ');
    expect(api.adminRefundProblem).toHaveBeenCalledWith('problem-1', { note: 'checked' });
    expect(api.adminProblems).toHaveBeenLastCalledWith({ size: 20, status: 'OPEN' });
    expect(store.notice()).toBe('Refund requested. Awaiting payment confirmation.');
    expect(store.busyId()).toBeNull();
    expect(store.items()).toEqual([]);
  });

  it('invalidates cached filters after a successful resolution', () => {
    const { store, api } = harness();
    store.select('OPEN');
    store.select('ALL');
    store.resolve('problem-1', 'refund', '');
    store.select('OPEN');
    expect(api.adminProblems).toHaveBeenCalledTimes(4);
    expect(api.adminRefundProblem).toHaveBeenCalledWith('problem-1', { note: null });
  });

  it('dismisses with a required reason and never sends invalid notes', () => {
    const { store, api } = harness();
    store.resolve('problem-1', 'dismiss', '   ');
    store.resolve('problem-1', 'refund', 'x'.repeat(1001));
    store.resolve('problem-1', 'refund', 'a\u0000b');
    expect(api.adminDismissProblem).not.toHaveBeenCalled();
    expect(api.adminRefundProblem).not.toHaveBeenCalled();
    store.resolve('problem-1', 'dismiss', '  Evidence checked  ');
    expect(api.adminDismissProblem).toHaveBeenCalledWith('problem-1', { note: 'Evidence checked' });
    expect(store.notice()).toBe('Problem dismissed.');
  });

  it('allows refund retry for pending problems but prevents dismissal', () => {
    const { store, api } = harness();
    api.adminProblems.mockReturnValueOnce(of(page([problem('problem-1', 'REFUND_PENDING')])));
    store.reload();
    store.resolve('problem-1', 'dismiss', 'Reason');
    expect(api.adminDismissProblem).not.toHaveBeenCalled();
    store.resolve('problem-1', 'refund', '');
    expect(api.adminRefundProblem).toHaveBeenCalledTimes(1);
  });

  it('uses one in-flight command and only closes confirmation after acceptance', () => {
    const { store, api } = harness();
    const delayed = new Subject<AdminProblemItem>();
    api.adminRefundProblem.mockReturnValueOnce(delayed);
    const accepted = vi.fn();
    store.resolve('problem-1', 'refund', '', accepted);
    store.resolve('problem-1', 'refund', '', accepted);
    store.resolve('problem-1', 'dismiss', 'Reason', accepted);
    expect(api.adminRefundProblem).toHaveBeenCalledTimes(1);
    expect(api.adminDismissProblem).not.toHaveBeenCalled();
    expect(store.busyId()).toBe('problem-1');
    expect(accepted).not.toHaveBeenCalled();
    delayed.next(problem('problem-1', 'REFUND_PENDING'));
    expect(accepted).toHaveBeenCalledOnce();
  });

  it.each([undefined, 'problem-not-open'])(
    'handles a 409 conflict without requiring a code (%s)',
    (code) => {
      const { store, api } = harness();
      api.adminRefundProblem.mockReturnValueOnce(throwError(() => ({ status: 409, code })));
      const close = vi.fn();
      store.resolve('problem-1', 'refund', '', close);
      expect(store.notice()).toContain('changed');
      expect(api.adminProblems).toHaveBeenCalledTimes(2);
      expect(close).toHaveBeenCalledOnce();
    },
  );

  it('shows a truthful unavailable state and allows an explicit retry', () => {
    const { store, api } = harness();
    api.adminRefundProblem.mockReturnValueOnce(
      throwError(() => ({ status: 503, code: 'refund-unavailable' })),
    );
    const close = vi.fn();
    store.resolve('problem-1', 'refund', '', close);
    expect(store.actionError()).toContain('Check the order');
    expect(store.notice()).toBe('');
    expect(close).not.toHaveBeenCalled();
    store.resolve('problem-1', 'refund', '', close);
    expect(api.adminRefundProblem).toHaveBeenCalledTimes(2);
  });

  it.each([401, 403])('clears data and closes resolution after backend %s', (status) => {
    const { store, api } = harness();
    api.adminDismissProblem.mockReturnValueOnce(throwError(() => ({ status })));
    const close = vi.fn();
    store.resolve('problem-1', 'dismiss', 'Reason', close);
    expect(store.items()).toEqual([]);
    expect(store.state()).toBe('forbidden');
    expect(close).toHaveBeenCalledOnce();
  });

  it('cancels commands on identity changes and ignores late acceptance', () => {
    const { store, api, identity } = harness();
    const delayed = new Subject<AdminProblemItem>();
    api.adminRefundProblem.mockReturnValueOnce(delayed);
    const close = vi.fn();
    store.resolve('problem-1', 'refund', '', close);
    identity.set({ sub: 'admin-2' });
    TestBed.tick();
    delayed.next(problem());
    expect(store.notice()).toBe('');
    expect(store.busyId()).toBeNull();
    expect(close).not.toHaveBeenCalled();
  });

  it('blocks commands for non-admins and unknown or resolved rows', () => {
    const { store, api, role } = harness();
    store.resolve('missing', 'refund', '');
    api.adminProblems.mockReturnValueOnce(of(page([problem('problem-1', 'RESOLVED')])));
    store.reload();
    store.resolve('problem-1', 'refund', '');
    role.set(false);
    store.resolve('problem-1', 'dismiss', 'Reason');
    expect(api.adminRefundProblem).not.toHaveBeenCalled();
    expect(api.adminDismissProblem).not.toHaveBeenCalled();
  });
});
