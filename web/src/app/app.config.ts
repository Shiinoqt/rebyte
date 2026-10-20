import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { routes } from './app.routes';
import { AppConfigService } from './core/config/app-config';
import { AuthStore } from './core/auth/auth.store';
import { authInterceptor } from './core/http/auth.interceptor';
import { problemInterceptor } from './core/http/problem.interceptor';
import { RealtimeService } from './core/realtime/realtime.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(
      routes,
      withComponentInputBinding(),
      withInMemoryScrolling({ scrollPositionRestoration: 'enabled' }),
    ),
    provideHttpClient(withInterceptors([problemInterceptor, authInterceptor])),
    provideAppInitializer(async () => {
      const config = inject(AppConfigService);
      const auth = inject(AuthStore);
      const realtime = inject(RealtimeService);
      await config.load();
      await auth.init();
      realtime.connectToSession();
    }),
  ],
};
