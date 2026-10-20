import { DOCUMENT } from '@angular/common';
import {
  AfterViewInit,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  inject,
  input,
  OnDestroy,
  output,
  viewChild,
} from '@angular/core';

let nextDialogId = 0;

@Component({
  selector: 'app-modal-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    dialog {
      width: calc(100% - 2 * var(--space-lg));
      max-width: 440px;
      padding: 0;
      border: 0;
      border-radius: var(--radius-xl);
      background: var(--surface-modal);
      color: var(--ink);
      box-shadow: var(--shadow-lg);
    }
    dialog.confirm {
      max-width: 380px;
    }
    dialog.wide {
      max-width: 960px;
    }
    dialog::backdrop {
      background: var(--scrim);
    }
    .body {
      position: relative;
      display: flex;
      flex-direction: column;
      gap: var(--space-md);
      padding: var(--space-lg) var(--space-lg) var(--space-lg);
    }
    h2 {
      margin: 0;
      padding-right: var(--hit-min);
      font-size: var(--text-heading);
      font-weight: var(--weight-bold);
      line-height: var(--leading-heading);
      overflow-wrap: anywhere;
    }
    .sub {
      margin: var(--space-xs) 0 0;
      color: var(--text-muted);
      overflow-wrap: anywhere;
    }
    .close {
      position: absolute;
      top: var(--space-sm);
      right: var(--space-sm);
      width: var(--hit-min);
      height: var(--hit-min);
      border: 0;
      background: none;
      color: var(--text-muted);
      font-size: var(--text-heading);
      cursor: pointer;
    }
    @media (max-width: 640px) {
      dialog:not(.confirm):not(.wide) {
        width: 100%;
        max-width: none;
        max-height: 90vh;
        margin: auto 0 0;
        border-radius: var(--radius-xl) var(--radius-xl) 0 0;
        overflow-y: auto;
      }
    }
  `,
  template: `<dialog
    #dialog
    [class.confirm]="size() === 'confirm'"
    [class.wide]="size() === 'wide'"
    [attr.aria-labelledby]="titleId"
    (close)="finish()"
    (click)="backdrop($event)"
    (keydown.escape)="escape($event)"
  >
    <div class="body">
      <div>
        <h2 [id]="titleId">{{ heading() }}</h2>
        @if (sub()) {
          <p class="sub">{{ sub() }}</p>
        }
      </div>
      <ng-content />
      <button type="button" class="close" aria-label="Close" (click)="dismiss()">&times;</button>
    </div>
  </dialog>`,
})
export class ModalDialog implements AfterViewInit, OnDestroy {
  readonly heading = input.required<string>();
  readonly sub = input('');
  readonly size = input<'confirm' | 'form' | 'wide'>('form');
  readonly closed = output<void>();
  readonly titleId = `modal-title-${nextDialogId++}`;

  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
  private readonly document = inject(DOCUMENT);
  private opener: HTMLElement | null = null;
  private priorOverflow = '';
  private finished = false;

  ngAfterViewInit(): void {
    this.opener = this.document.activeElement as HTMLElement | null;
    const element = this.dialog().nativeElement;
    if (typeof element.showModal === 'function') element.showModal();
    else element.setAttribute('open', '');
    this.priorOverflow = this.document.body.style.overflow;
    this.document.body.style.overflow = 'hidden';
  }

  ngOnDestroy(): void {
    this.finished = true;
    this.document.body.style.overflow = this.priorOverflow;
    const element = this.dialog().nativeElement;
    if (typeof element.close === 'function' && element.open) element.close();
    if (this.opener?.isConnected) this.opener.focus();
  }

  dismiss(): void {
    const element = this.dialog().nativeElement;
    if (typeof element.close === 'function' && element.open) element.close();
    this.finish();
  }

  finish(): void {
    if (this.finished) return;
    this.finished = true;
    this.closed.emit();
  }

  escape(event: Event): void {
    event.preventDefault();
    this.dismiss();
  }

  backdrop(event: MouseEvent): void {
    if (event.target === this.dialog().nativeElement) this.dismiss();
  }
}
