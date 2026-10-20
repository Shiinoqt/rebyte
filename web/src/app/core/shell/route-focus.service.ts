import { DOCUMENT } from '@angular/common';
import {
  AfterRenderRef,
  DestroyRef,
  Injectable,
  Injector,
  afterNextRender,
  inject,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter } from 'rxjs';
import { AuthStore } from '../auth/auth.store';

@Injectable({ providedIn: 'root' })
export class RouteFocusService {
  private readonly router = inject(Router);
  private readonly document = inject(DOCUMENT);
  private readonly auth = inject(AuthStore);
  private readonly injector = inject(Injector);
  private readonly destroyRef = inject(DestroyRef);
  private started = false;
  private pending?: AfterRenderRef;
  private previousPath = this.pathFromUrl(this.router.url);

  start(): void {
    if (this.started) return;
    this.started = true;
    this.destroyRef.onDestroy(() => this.pending?.destroy());
    this.router.events
      .pipe(
        filter((event) => event instanceof NavigationEnd),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((event) => {
        const nextPath = this.pathFromUrl(event.urlAfterRedirects);
        if (nextPath === this.previousPath) return;
        this.previousPath = nextPath;
        this.pending?.destroy();
        // The first page load keeps the browser's own start-of-document focus so the skip link stays first.
        if (event.id === 1 || this.auth.status() === 'resolving') return;
        // Wait for the newly activated page to render before selecting its heading.
        this.pending = afterNextRender(
          () => {
            if (this.auth.status() === 'resolving') return;
            const heading = this.document.querySelector<HTMLElement>('main#main h1') ??
              this.document.querySelector<HTMLElement>('main#main');
            if (!heading) return;
            heading.setAttribute('tabindex', '-1');
            heading.focus({ preventScroll: true });
          },
          { injector: this.injector },
        );
      });
  }

  private pathFromUrl(url: string): string {
    return url.split(/[?#]/, 1)[0];
  }
}
