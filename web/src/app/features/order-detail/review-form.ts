import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { OrderView } from '../../core/api/api-models';
import { AuthStore } from '../../core/auth/auth.store';
import { BusyButton } from '../../shared/ui/feedback';
import { FormField, StarRating } from '../../shared/ui/forms';
import { ORDER_CARD_STYLES, OrderExtras, validateReview } from './problem-card';

@Component({
  selector: 'app-review-form',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, BusyButton, FormField, StarRating],
  styles: ORDER_CARD_STYLES,
  template: `
    @if (order().review; as review) {
      <section class="optional-card">
        <h2>Review</h2>
        <app-star-rating [value]="review.rating" />
        @if (review.authorUsername) {
          <p>{{ review.authorUsername }}</p>
        }
        @if (review.comment) {
          <p class="prose">{{ review.comment }}</p>
        }
        <p class="note">{{ review.createdAt | date: 'd MMM y' }}</p>
      </section>
    } @else if (work.actions().review) {
      <section class="optional-card">
        <h2>Leave a review</h2>
        <form class="dialog-form" novalidate (submit)="submit($event)">
          <app-star-rating
            label="Rating"
            [editable]="true"
            [value]="rating() === '' ? 0 : +rating()"
            [disabled]="!!work.busy()"
            (valueChange)="rating.set('' + $event)"
          />
          @if (errors().rating) {
            <p class="field-error" role="alert">{{ errors().rating }}</p>
          }
          <app-form-field
            [id]="'review-comment'"
            label="Comment (optional)"
            kind="textarea"
            placeholder="How was the item and the seller?"
            [value]="comment()"
            [hint]="comment().length + ' / 2000 characters'"
            [error]="errors().comment ?? ''"
            [disabled]="!!work.busy()"
            (valueChange)="comment.set($event)"
            (blurred)="validate()"
          />
          <app-busy-button
            class="wide"
            type="submit"
            label="Post review"
            [busy]="work.busy() === 'review'"
            [disabled]="!!work.busy() && work.busy() !== 'review'"
          />
        </form>
      </section>
    } @else if (isBuyer() && (order().status === 'PAID' || order().status === 'SHIPPED')) {
      <p class="note">You can review this order once it is delivered.</p>
    }
  `,
})
export class ReviewForm {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  readonly order = input.required<OrderView>();
  readonly work = inject(OrderExtras);
  private readonly auth = inject(AuthStore);
  readonly isBuyer = computed(() => this.auth.identity()?.sub === this.order().buyerSub);
  readonly rating = signal('');
  readonly comment = signal('');
  readonly errors = signal<{ rating?: string; comment?: string }>({});
  validate(): void {
    this.errors.set(
      validateReview({
        rating: this.rating() === '' ? NaN : Number(this.rating()),
        comment: this.comment(),
      }),
    );
  }
  submit(event: Event): void {
    event.preventDefault();
    this.validate();
    if (Object.keys(this.errors()).length) {
      afterNextRender(
        () =>
          this.host.nativeElement
            .querySelector<HTMLElement>(
              this.errors().rating
                ? 'app-star-rating button[tabindex="0"]'
                : '[aria-invalid="true"]',
            )
            ?.focus(),
        { injector: this.injector },
      );
      return;
    }
    this.work.review({ rating: Number(this.rating()), comment: this.comment() || null });
  }
}
