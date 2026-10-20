import { CanDeactivateFn, Routes } from '@angular/router';
import { SellEditPage } from './sell-edit.page';

export const listingFormLeaveGuard: CanDeactivateFn<SellEditPage> = (page) => page.canLeave();

// The parent entry in app.routes.ts supplies data.mode ('create' or 'edit') and, for edit, :id.
export const LISTING_FORM_ROUTES: Routes = [
  { path: '', component: SellEditPage, canDeactivate: [listingFormLeaveGuard] },
];
