import { bootstrapApplication } from '@angular/platform-browser';
import { appConfig } from './app/app.config';
import { App } from './app/app';

bootstrapApplication(App, appConfig).catch((err: unknown) => {
  const message = err instanceof Error ? err.message : 'Unknown startup error';
  const box = document.createElement('div');
  box.className = 'startup-error';
  box.setAttribute('role', 'alert');
  box.textContent = `rebyte could not start. ${message}`;
  document.body.replaceChildren(box);
});
