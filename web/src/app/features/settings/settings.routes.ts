import { CanDeactivateFn, Routes } from '@angular/router';
import { SettingsPage } from './settings.page';

export const settingsLeaveGuard: CanDeactivateFn<SettingsPage> = (page) => page.canLeave();

export const SETTINGS_ROUTES: Routes = [
  { path: '', component: SettingsPage, canDeactivate: [settingsLeaveGuard] },
];
