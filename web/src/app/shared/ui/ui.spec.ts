import { Type } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import { provideRouter } from '@angular/router';
import {
  Avatar,
  CopyField,
  EurPipe,
  ListingCard,
  ListingCardData,
  MoneyRows,
  StatusChip,
} from './content';
import { FormField, MoneyInput, StarRating } from './forms';
import {
  CountBadge,
  IconButton,
  LoadMore,
  MenuSelect,
  PillTabs,
  SegmentedControl,
} from './navigation';
import {
  Banner,
  BusyButton,
  EmptyState,
  ErrorState,
  Skeleton,
  ToastHost,
  ToastService,
} from './feedback';

async function render<T>(component: Type<T>, inputs: Record<string, unknown> = {}) {
  const fixture = TestBed.createComponent(component);
  for (const [name, value] of Object.entries(inputs)) fixture.componentRef.setInput(name, value);
  await fixture.whenStable();
  return fixture;
}
function root<T>(fixture: ComponentFixture<T>): HTMLElement {
  return fixture.nativeElement;
}
function button<T>(fixture: ComponentFixture<T>): HTMLButtonElement {
  return root(fixture).querySelector('button')!;
}

describe('Feedback primitives', () => {
  afterEach(() => {
    TestBed.resetTestingModule();
    vi.useRealTimers();
  });

  it('reserves a card-shaped loading placeholder with an observable busy label', async () => {
    const fixture = await render(Skeleton, { shape: 'card', label: 'Loading listings' });
    expect(root(fixture).getAttribute('data-ui')).toBe('skeleton');
    expect(root(fixture).getAttribute('aria-busy')).toBe('true');
    expect(root(fixture).getAttribute('aria-label')).toBe('Loading listings');
    expect(root(fixture).querySelector('.skeleton-card')).not.toBeNull();
  });

  it.each(['info', 'warning', 'error'] as const)(
    'announces a %s banner with text and severity',
    async (kind) => {
      const fixture = await render(Banner, { kind, text: 'One photo could not be uploaded.' });
      expect(root(fixture).getAttribute('role')).toBe(kind === 'info' ? 'status' : 'alert');
      expect(root(fixture).textContent).toContain('One photo could not be uploaded.');
      expect(root(fixture).querySelector('.visually-hidden')?.textContent?.trim()).toBe(
        kind === 'info' ? 'Information:' : kind === 'warning' ? 'Warning:' : 'Error:',
      );
      expect(root(fixture).querySelector('.banner-text')?.textContent?.trim()).toBe(
        'One photo could not be uploaded.',
      );
      expect(root(fixture).querySelector('.banner-icon svg')).not.toBeNull();
    },
  );

  it('gives empty results a heading and native action', async () => {
    const fixture = await render(EmptyState, {
      title: 'Nothing matches',
      text: 'Try another search.',
      actionLabel: 'Clear filters',
    });
    let actions = 0;
    fixture.componentInstance.action.subscribe(() => actions++);
    expect(root(fixture).querySelector('h2')?.textContent).toBe('Nothing matches');
    button(fixture).click();
    expect(actions).toBe(1);
    expect(button(fixture).type).toBe('button');
  });

  it('offers an error heading and retry button', async () => {
    const fixture = await render(ErrorState);
    let retries = 0;
    fixture.componentInstance.retry.subscribe(() => retries++);
    expect(root(fixture).getAttribute('role')).toBe('alert');
    expect(root(fixture).querySelector('h2')).not.toBeNull();
    expect(button(fixture).textContent).toContain('Try again');
    button(fixture).click();
    expect(retries).toBe(1);
  });

  it('blocks a busy action, preserves its label and explains a disabled action', async () => {
    const fixture = await render(BusyButton, { label: 'Publish listing', busy: true });
    let actions = 0;
    fixture.componentInstance.pressed.subscribe(() => actions++);
    expect(button(fixture).disabled).toBe(true);
    expect(button(fixture).getAttribute('aria-busy')).toBe('true');
    expect(button(fixture).textContent).toContain('Publish listing');
    button(fixture).click();
    expect(actions).toBe(0);
    fixture.componentRef.setInput('busy', false);
    fixture.componentRef.setInput('disabled', true);
    fixture.componentRef.setInput('reason', 'Software listings need a developer profile.');
    await fixture.whenStable();
    const reason = button(fixture).getAttribute('aria-describedby');
    expect(root(fixture).querySelector(`[id="${reason}"]`)?.textContent).toContain(
      'developer profile',
    );
    fixture.componentRef.setInput('disabled', false);
    await fixture.whenStable();
    button(fixture).click();
    expect(actions).toBe(1);
  });

  it('queues toasts FIFO with exact four and eight second lifetimes', () => {
    vi.useFakeTimers();
    const service = TestBed.inject(ToastService);
    service.show({ text: 'First', kind: 'success' });
    service.show({ text: 'Second', kind: 'info' });
    service.show({ text: 'Third', kind: 'error' });
    vi.advanceTimersByTime(3999);
    expect(service.current()?.text).toBe('First');
    vi.advanceTimersByTime(1);
    expect(service.current()?.text).toBe('Second');
    vi.advanceTimersByTime(4000);
    expect(service.current()?.text).toBe('Third');
    vi.advanceTimersByTime(7999);
    expect(service.current()?.text).toBe('Third');
    vi.advanceTimersByTime(1);
    expect(service.current()).toBeNull();
  });

  it('manual dismissal starts the next timer afresh and teardown clears it', () => {
    vi.useFakeTimers();
    const service = TestBed.inject(ToastService);
    service.show({ text: 'First', kind: 'error' });
    service.show({ text: 'Next', kind: 'info' });
    vi.advanceTimersByTime(3000);
    service.dismiss();
    vi.advanceTimersByTime(3999);
    expect(service.current()?.text).toBe('Next');
    TestBed.resetTestingModule();
    expect(vi.getTimerCount()).toBe(0);
  });

  it('announces toasts without moving focus and runs the action once before advancing', async () => {
    const fixture = await render(ToastHost);
    const service = TestBed.inject(ToastService);
    const before = document.activeElement;
    let actions = 0;
    service.show({
      text: 'Saved',
      kind: 'success',
      action: { label: 'View listing', run: () => actions++ },
    });
    service.show({ text: 'Failed', kind: 'error' });
    await fixture.whenStable();
    expect(document.activeElement).toBe(before);
    expect(
      root(fixture).querySelector('[role="status"][aria-live="polite"]')?.textContent,
    ).toContain('Saved');
    root(fixture).querySelector<HTMLButtonElement>('[data-toast-action]')!.click();
    await fixture.whenStable();
    expect(actions).toBe(1);
    expect(
      root(fixture).querySelector('[role="alert"][aria-live="assertive"]')?.textContent,
    ).toContain('Failed');
    root(fixture).querySelector<HTMLButtonElement>('[aria-label="Dismiss notification"]')!.click();
    await fixture.whenStable();
    expect(service.current()).toBeNull();
  });

  it('shows one row with a kind icon, the message and an icon close button', async () => {
    const fixture = await render(ToastHost);
    TestBed.inject(ToastService).show({ text: 'Profile photo removed', kind: 'success' });
    await fixture.whenStable();
    const status = root(fixture).querySelector('[role="status"]')!;
    expect(status.querySelector('.visually-hidden')?.textContent?.trim()).toBe('Success:');
    expect(status.querySelector('.toast-text')?.textContent?.trim()).toBe('Profile photo removed');
    expect(root(fixture).querySelector('.toast-icon svg')).not.toBeNull();
    const close = root(fixture).querySelector<HTMLButtonElement>(
      '[aria-label="Dismiss notification"]',
    )!;
    expect(close.textContent?.trim()).toBe('');
    expect(close.querySelector('svg')).not.toBeNull();
  });

  it('re-announces identical consecutive toasts inside persistent live regions', async () => {
    const fixture = await render(ToastHost);
    const service = TestBed.inject(ToastService);
    service.show({ text: 'Changes saved', kind: 'success' });
    service.show({ text: 'Changes saved', kind: 'success' });
    await fixture.whenStable();
    const region = root(fixture).querySelector('[role="status"]')!;
    const first = region.querySelector('span');
    service.dismiss();
    await fixture.whenStable();
    expect(root(fixture).querySelector('[role="status"]')).toBe(region);
    expect(region.querySelector('span')).not.toBe(first);
    expect(region.textContent).toContain('Changes saved');
  });
});

const choices = [
  { value: 'all', label: 'All', panelId: 'panel-all' },
  { value: 'hardware', label: 'Hardware', panelId: 'panel-hardware' },
  {
    value: 'software',
    label: 'Software',
    disabled: true,
    reason: 'Software listings need a developer profile.',
  },
];

describe('Navigation primitives', () => {
  afterEach(() => TestBed.resetTestingModule());

  describe('menu select', () => {
    const statuses = [
      { value: '', label: 'All' },
      { value: 'PAID', label: 'Paid' },
      { value: 'SHIPPED', label: 'Shipped' },
    ];
    const rows = (fixture: ComponentFixture<MenuSelect>) =>
      [...root(fixture).querySelectorAll<HTMLButtonElement>('[data-row]')];
    const press = async (fixture: ComponentFixture<MenuSelect>, key: string) => {
      (document.activeElement as HTMLElement).dispatchEvent(
        new KeyboardEvent('keydown', { key, bubbles: true }),
      );
      await fixture.whenStable();
    };
    const open = async (fixture: ComponentFixture<MenuSelect>) => {
      button(fixture).click();
      await fixture.whenStable();
    };

    it('opens, moves focus with the keyboard, closes and emits the chosen value', async () => {
      const fixture = await render(MenuSelect, {
        label: 'Status',
        options: statuses,
        selected: 'PAID',
      });
      const trigger = button(fixture);
      const chosen: string[] = [];
      fixture.componentInstance.selectedChange.subscribe((value) => chosen.push(value));
      expect(trigger.textContent).toContain('Status');
      expect(trigger.textContent).toContain('Paid');
      expect(trigger.getAttribute('aria-expanded')).toBe('false');

      await open(fixture);
      expect(trigger.getAttribute('aria-expanded')).toBe('true');
      expect(document.activeElement?.textContent?.trim()).toBe('Paid');
      expect(document.activeElement?.getAttribute('aria-current')).toBe('true');
      const focused = () => document.activeElement?.textContent?.trim();
      await press(fixture, 'ArrowDown');
      expect(focused()).toBe('Shipped');
      await press(fixture, 'ArrowDown');
      expect(focused()).toBe('All');
      await press(fixture, 'ArrowUp');
      expect(focused()).toBe('Shipped');
      await press(fixture, 'Home');
      expect(focused()).toBe('All');
      await press(fixture, 'End');
      expect(focused()).toBe('Shipped');

      await press(fixture, 'Escape');
      expect(rows(fixture)).toHaveLength(0);
      expect(trigger.getAttribute('aria-expanded')).toBe('false');
      expect(document.activeElement).toBe(trigger);

      await open(fixture);
      document.body.click();
      await fixture.whenStable();
      expect(rows(fixture)).toHaveLength(0);

      await open(fixture);
      rows(fixture)[2].click();
      await fixture.whenStable();
      expect(chosen).toEqual(['SHIPPED']);
      expect(rows(fixture)).toHaveLength(0);
      expect(document.activeElement).toBe(trigger);
    });

    it('names the trigger and menu, and supports the icon and end alignment variants', async () => {
      const plain = await render(MenuSelect, { label: 'Sort by', options: statuses });
      expect(button(plain).hasAttribute('aria-label')).toBe(false);
      await open(plain);
      expect(root(plain).querySelector('[role="group"]')?.getAttribute('aria-label')).toBe(
        'Sort by',
      );
      expect(button(plain).textContent).toContain('All');
      expect(root(plain).classList.contains('end')).toBe(false);
      expect(root(plain).classList.contains('with-icon')).toBe(false);

      const named = await render(MenuSelect, {
        label: 'Status',
        name: 'Filter loaded orders by status',
        options: statuses,
        selected: 'PAID',
        icon: 'M0 0h1',
        align: 'end',
      });
      expect(button(named).getAttribute('aria-label')).toBe('Filter loaded orders by status: Paid');
      expect(button(named).querySelectorAll('svg')).toHaveLength(2);
      expect(button(named).textContent).toContain('Status');
      expect(root(named).classList.contains('end')).toBe(true);
      expect(root(named).classList.contains('with-icon')).toBe(true);
      await open(named);
      expect(root(named).querySelector('[role="group"]')?.getAttribute('aria-label')).toBe(
        'Filter loaded orders by status',
      );
    });
  });

  it.each([
    [0, null],
    [1, '1'],
    [100, '99+'],
  ] as const)('renders %s unread without losing the full count', async (count, display) => {
    const fixture = await render(CountBadge, { count, label: 'unread messages' });
    const badge = root(fixture).querySelector('span');
    expect(badge?.textContent ?? null).toBe(display);
    if (count) {
      expect(badge?.getAttribute('aria-label')).toBe(`${count} unread messages`);
      expect(badge?.getAttribute('role')).toBe('status');
    }
  });

  it.each([
    { name: 'pill tabs', component: PillTabs },
    { name: 'segmented control', component: SegmentedControl },
  ])('moves roving focus, wraps and skips disabled choices in $name', async ({ component }) => {
    const fixture = await render(component, {
      label: 'Listing type',
      options: choices,
      selected: 'all',
    });
    const role = component === PillTabs ? 'tab' : 'radio';
    const selection = component === PillTabs ? 'aria-selected' : 'aria-checked';
    const buttons = [...root(fixture).querySelectorAll<HTMLButtonElement>(`[role="${role}"]`)];
    let chosen = '';
    fixture.componentInstance.selectedChange.subscribe((value) => {
      chosen = value;
      fixture.componentRef.setInput('selected', value);
    });
    expect(buttons.map((b) => b.tabIndex)).toEqual([0, -1, -1]);
    expect(buttons[0].getAttribute(selection)).toBe('true');
    buttons[0].focus();
    buttons[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    await fixture.whenStable();
    expect(chosen).toBe('hardware');
    expect(document.activeElement).toBe(buttons[1]);
    expect(buttons[1].getAttribute(selection)).toBe('true');
    buttons[1].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    await fixture.whenStable();
    expect(chosen).toBe('all');
    buttons[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true }));
    await fixture.whenStable();
    expect(chosen).toBe('hardware');
    buttons[1].dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true }));
    await fixture.whenStable();
    expect(chosen).toBe('all');
    buttons[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }));
    await fixture.whenStable();
    expect(chosen).toBe('hardware');
    expect(buttons[2].disabled).toBe(true);
    const reason = buttons[2].getAttribute('aria-describedby');
    const description = root(fixture).querySelector(`[id="${reason}"]`);
    expect(description?.textContent).toContain('developer profile');
    expect(
      root(fixture).querySelector('[role="tablist"], [role="radiogroup"]')?.contains(description),
    ).toBe(false);
    if (component === PillTabs)
      expect(buttons[1].getAttribute('aria-controls')).toBe('panel-hardware');
  });

  it('offers tab semantics for a page-switching segmented control', async () => {
    const fixture = await render(SegmentedControl, {
      label: 'Account',
      mode: 'tabs',
      options: choices,
      selected: 'hardware',
    });
    expect(root(fixture).querySelector('[role="tablist"]')?.getAttribute('aria-label')).toBe(
      'Account',
    );
    expect(root(fixture).querySelector('[aria-selected="true"]')?.textContent?.trim()).toBe(
      'Hardware',
    );
  });

  it('announces appended results and keeps fetching outside LoadMore', async () => {
    const fixture = await render(LoadMore, { count: 24, noun: 'listings' });
    let requests = 0;
    fixture.componentInstance.load.subscribe(() => requests++);
    button(fixture).click();
    expect(requests).toBe(1);
    fixture.componentRef.setInput('loading', true);
    await fixture.whenStable();
    expect(button(fixture).disabled).toBe(true);
    expect(button(fixture).getAttribute('aria-busy')).toBe('true');
    fixture.componentRef.setInput('count', 48);
    fixture.componentRef.setInput('loading', false);
    await fixture.whenStable();
    expect(
      root(fixture).querySelector('[role="status"][aria-live="polite"]')?.textContent,
    ).toContain('48 listings');
    fixture.componentRef.setInput('hasMore', false);
    await fixture.whenStable();
    expect(root(fixture).querySelector('button')).toBeNull();
    expect(root(fixture).querySelector('[role="status"]')?.textContent).toContain('48 listings');
  });

  it('labels an icon action, exposes pressed state and links its disabled reason', async () => {
    const fixture = await render(IconButton, {
      label: 'Follow',
      pressed: false,
      disabled: true,
      reason: 'Log in to follow.',
    });
    let actions = 0;
    fixture.componentInstance.activated.subscribe(() => actions++);
    button(fixture).click();
    expect(actions).toBe(0);
    expect(button(fixture).getAttribute('aria-label')).toBe('Follow');
    expect(button(fixture).getAttribute('aria-pressed')).toBe('false');
    const reason = button(fixture).getAttribute('aria-describedby');
    expect(root(fixture).querySelector(`[id="${reason}"]`)?.textContent).toBe('Log in to follow.');
    fixture.componentRef.setInput('disabled', false);
    await fixture.whenStable();
    button(fixture).click();
    expect(actions).toBe(1);
  });
});

describe('Content and form primitives', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: [provideRouter([])] }));
  afterEach(() => {
    TestBed.resetTestingModule();
    vi.unstubAllGlobals();
  });

  it('falls back to a username initial and recovers from a failed avatar URL', async () => {
    const fixture = await render(Avatar, { username: 'damien' });
    expect(root(fixture).textContent).toContain('D');
    expect(root(fixture).getAttribute('aria-label')).toBe('@damien');
    fixture.componentRef.setInput('url', '/avatar.jpg');
    await fixture.whenStable();
    const image = root(fixture).querySelector('img')!;
    expect(image.getAttribute('alt')).toBe('@damien');
    image.dispatchEvent(new Event('error'));
    await fixture.whenStable();
    expect(root(fixture).querySelector('img')).toBeNull();
    expect(root(fixture).textContent).toContain('D');
    fixture.componentRef.setInput('url', '/new-avatar.jpg');
    await fixture.whenStable();
    expect(root(fixture).querySelector('img')).not.toBeNull();
  });

  it('preserves a full long title, uses a decorative placeholder and keeps the heart outside the card link', async () => {
    const title = 'A very long listing title '.repeat(20);
    const fixture = await render(ListingCard, {
      listing: { id: 'gpu-1', title, price: '310.01', type: 'HARDWARE', status: 'ACTIVE' },
    });
    const link = root(fixture).querySelector('a')!;
    expect(link.getAttribute('href')).toBe('/listings/gpu-1');
    expect(root(fixture).querySelector('.title')?.textContent).toBe(title);
    expect(root(fixture).querySelector('.placeholder')?.getAttribute('aria-hidden')).toBe('true');
    expect(root(fixture).querySelector('.meta')).toBeNull();
    const heart = root(fixture).querySelector<HTMLButtonElement>('.favourite')!;
    expect(link.contains(heart)).toBe(false);
    expect(heart.getAttribute('aria-pressed')).toBe('false');
    let toggles = 0;
    fixture.componentInstance.favouriteChange.subscribe(() => toggles++);
    heart.click();
    expect(toggles).toBe(1);
    fixture.componentRef.setInput('favourite', true);
    await fixture.whenStable();
    expect(heart.getAttribute('aria-pressed')).toBe('true');
    expect(heart.getAttribute('aria-label')).toContain('Remove from favourites');
    expect(root(fixture).textContent).not.toMatch(/guarantee|arrives|Free returns/);
  });

  it('renders a favourite projection without inventing a listing type', async () => {
    const listing: ListingCardData = {
      id: 'saved-1',
      title: 'Saved listing',
      price: '10.00',
      coverUrl: null,
    };
    const fixture = await render(ListingCard, { listing });
    expect(root(fixture).querySelector('.type')).toBeNull();
    expect(root(fixture).querySelector('.meta')).toBeNull();
    expect(root(fixture).textContent).toContain('€10.00');
  });

  it('renders only supplied hardware and software metadata and recovers from a failed cover', async () => {
    const fixture = await render(ListingCard, {
      listing: {
        id: '1',
        title: '<img src=x onerror=alert(1)>',
        price: '9.90',
        type: 'HARDWARE',
        coverUrl: '/gpu.jpg',
        condition: 'VERY_GOOD',
        shippingLocation: 'Turin',
        sellerUsername: 'seller',
      },
    });
    expect(root(fixture).querySelector('.title img')).toBeNull();
    const image = root(fixture).querySelector('img')!;
    expect(image.getAttribute('loading')).toBe('lazy');
    expect(image.getAttribute('alt')).toContain('<img');
    expect(root(fixture).querySelector('.meta')?.textContent).toBe('Very good · Turin · @seller');
    image.dispatchEvent(new Event('error'));
    await fixture.whenStable();
    expect(root(fixture).querySelector('img')).toBeNull();
    fixture.componentRef.setInput('listing', {
      id: '2',
      title: 'Tool',
      price: '10',
      type: 'SOFTWARE',
      coverUrl: '/tool.jpg',
      platforms: ['WINDOWS', 'MACOS'],
      currentVersion: '1.2',
    });
    await fixture.whenStable();
    expect(root(fixture).querySelector('img')).not.toBeNull();
    expect(root(fixture).querySelector('.meta')?.textContent).toBe('Windows, macOS · v1.2');
    expect(root(fixture).textContent).toContain('SOFTWARE');
  });

  it.each([
    ['0', '€0.00'],
    ['310.10', '€310.10'],
    ['1.005', '€1.01'],
    ['9007199254740993.01', '€9,007,199,254,740,993.01'],
    ['-0.01', '-€0.01'],
  ])('formats decimal %s as exact English EUR', (value, expected) => {
    expect(new EurPipe().transform(value)).toBe(expected);
  });

  it('renders supplied money rows with an explicit total and tabular values', async () => {
    const fixture = await render(MoneyRows, {
      rows: [
        { label: 'Item', value: '310.10' },
        { label: 'Shipping', value: '9.90' },
        { label: 'Total', value: '320.00', total: true },
      ],
    });
    expect(root(fixture).querySelectorAll('dt')).toHaveLength(3);
    expect(root(fixture).querySelector('.total dd')?.textContent).toBe('€320.00');
    expect(root(fixture).querySelector('.total dt')?.textContent).toBe('Total');
  });

  it('shows status in words as well as semantic tone', async () => {
    const fixture = await render(StatusChip, { text: 'Refunded', tone: 'danger' });
    expect(root(fixture).textContent).toBe('Refunded');
    expect(root(fixture).getAttribute('data-tone')).toBe('danger');
  });

  it('copies the exact value and confirms only after clipboard success', async () => {
    let copied = '';
    vi.stubGlobal('navigator', {
      clipboard: {
        writeText: async (value: string) => {
          copied = value;
        },
      },
    });
    const fixture = await render(CopyField, { label: 'Order id', value: 'order-123' });
    button(fixture).click();
    await fixture.whenStable();
    expect(copied).toBe('order-123');
    expect(TestBed.inject(ToastService).current()?.text).toBe('Order id copied');
  });

  it('keeps copy failure visible without claiming success', async () => {
    vi.stubGlobal('navigator', {
      clipboard: {
        writeText: async () => {
          throw new Error('Denied');
        },
      },
    });
    const fixture = await render(CopyField, { label: 'Tracking code', value: 'code' });
    button(fixture).click();
    await fixture.whenStable();
    expect(root(fixture).querySelector('[role="alert"]')?.textContent).toContain('Copy failed');
    expect(TestBed.inject(ToastService).current()).toBeNull();
  });

  it.each(['text', 'textarea', 'select'] as const)(
    'labels a %s field and binds hint, required and error state',
    async (kind) => {
      const fixture = await render(FormField, {
        id: 'recipient',
        label: 'Recipient',
        kind,
        hint: 'Who receives the parcel',
        required: true,
        options: [{ value: 'damien', label: 'Damien' }],
      });
      const control = root(fixture).querySelector<HTMLInputElement>('input, textarea, select')!;
      expect(root(fixture).querySelector('label')?.htmlFor).toBe('recipient');
      expect(control.getAttribute('aria-required')).toBe('true');
      expect(control.getAttribute('aria-invalid')).toBe('false');
      fixture.componentRef.setInput('error', 'Enter a recipient.');
      await fixture.whenStable();
      expect(control.getAttribute('aria-invalid')).toBe('true');
      expect(control.getAttribute('aria-describedby')).toBe('recipient-hint recipient-error');
      expect(root(fixture).querySelector('#recipient-error')?.textContent?.trim()).toBe(
        'Enter a recipient.',
      );
      fixture.componentRef.setInput('error', '');
      await fixture.whenStable();
      expect(control.getAttribute('aria-describedby')).toBe('recipient-hint');
    },
  );

  it('prefills a native select and preserves selection when its options change', async () => {
    const fixture = await render(FormField, {
      id: 'condition',
      label: 'Condition',
      kind: 'select',
      value: 'GOOD',
      options: [{ value: 'GOOD', label: 'Good' }],
    });
    const select = root(fixture).querySelector('select')!;
    expect(select.value).toBe('GOOD');
    fixture.componentRef.setInput('options', [
      { value: 'LIKE_NEW', label: 'Like new' },
      { value: 'GOOD', label: 'Good' },
    ]);
    await fixture.whenStable();
    expect(select.value).toBe('GOOD');
  });

  it('emits text values and blur separately so callers can validate on blur', async () => {
    const fixture = await render(FormField, { id: 'title', label: 'Title' });
    let value = '';
    let blurred = false;
    fixture.componentInstance.valueChange.subscribe((v) => (value = v));
    fixture.componentInstance.blurred.subscribe(() => (blurred = true));
    const control = root(fixture).querySelector<HTMLInputElement>('input')!;
    control.value = 'New title';
    control.dispatchEvent(new Event('input'));
    expect(value).toBe('New title');
    expect(blurred).toBe(false);
    control.dispatchEvent(new Event('blur'));
    expect(blurred).toBe(true);
  });

  it('keeps money as decimal text and rejects excess precision on blur', async () => {
    const fixture = await render(MoneyInput, {
      id: 'price',
      label: 'Price',
      value: '9007199254740993.01',
    });
    const control = root(fixture).querySelector<HTMLInputElement>('input')!;
    expect(control.inputMode).toBe('decimal');
    expect(control.type).toBe('text');
    expect(control.value).toBe('9007199254740993.01');
    let value = '';
    fixture.componentInstance.valueChange.subscribe((v) => {
      value = v;
      fixture.componentRef.setInput('value', v);
    });
    control.value = '310.123';
    control.dispatchEvent(new Event('input'));
    control.dispatchEvent(new Event('blur'));
    await fixture.whenStable();
    expect(control.getAttribute('aria-invalid')).toBe('true');
    expect(root(fixture).querySelector('[role="alert"]')?.textContent).toContain(
      'at most 2 decimals',
    );
    control.value = '310.10';
    control.dispatchEvent(new Event('input'));
    control.dispatchEvent(new Event('blur'));
    await fixture.whenStable();
    expect(value).toBe('310.10');
    expect(control.getAttribute('aria-invalid')).toBe('false');
  });

  it('offers five roving star buttons with arrow, Home and End keys and a printed value', async () => {
    const fixture = await render(StarRating, { label: 'Review rating', editable: true, value: 2 });
    const stars = [...root(fixture).querySelectorAll<HTMLButtonElement>('[role="radio"]')];
    let selected = 0;
    fixture.componentInstance.valueChange.subscribe((v) => {
      selected = v;
      fixture.componentRef.setInput('value', v);
    });
    expect(stars).toHaveLength(5);
    expect(stars.map((b) => b.tabIndex)).toEqual([-1, 0, -1, -1, -1]);
    stars[1].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }));
    await fixture.whenStable();
    expect(selected).toBe(3);
    expect(document.activeElement).toBe(stars[2]);
    expect(stars[2].getAttribute('aria-checked')).toBe('true');
    stars[2].dispatchEvent(new KeyboardEvent('keydown', { key: 'End' }));
    await fixture.whenStable();
    expect(selected).toBe(5);
    stars[4].dispatchEvent(new KeyboardEvent('keydown', { key: 'Home' }));
    await fixture.whenStable();
    expect(selected).toBe(1);
    expect(root(fixture).textContent).toContain('Poor');
    expect(root(fixture).textContent).not.toContain('out of 5');
  });

  it('displays a numeric rating without editable controls', async () => {
    const fixture = await render(StarRating, { value: 4.5 });
    expect(root(fixture).querySelector('button')).toBeNull();
    expect(root(fixture).textContent).toContain('4.5 out of 5');
  });
});

describe('Shared UI style contract', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('uses token colours, font sizes and radii in all four component stylesheets', async () => {
    TestBed.configureTestingModule({ providers: [provideRouter([])] });
    await render(Banner, { text: 'Ready' });
    await render(PillTabs, { label: 'Type', options: choices });
    await render(ListingCard, {
      listing: { id: '1', title: 'GPU', price: '1.00', type: 'HARDWARE' },
    });
    await render(FormField, { id: 'field', label: 'Field' });
    const styles = [...document.querySelectorAll('style')]
      .map((style) => style.textContent ?? '')
      .filter((text) => text.includes('_nghost-') || text.includes('_ngcontent-'));
    expect(styles).toHaveLength(4);
    for (const css of styles) {
      expect(css).not.toMatch(/#[\da-f]{3,8}\b/i);
      expect(css).not.toMatch(/font-size\s*:\s*[\d.]+px/i);
      expect(css).not.toMatch(/border(?:-[a-z]+)*-radius\s*:\s*(?!var\()[\d.]/i);
      expect(css).not.toMatch(/(?:rgba?|hsla?)\(/i);
    }
  });
});
