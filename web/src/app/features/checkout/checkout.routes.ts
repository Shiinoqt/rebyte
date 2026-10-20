import { Routes } from '@angular/router';
import { CheckoutPage } from './checkout.page';

// The parent entry in app.routes.ts supplies :listingId.
export const CHECKOUT_ROUTES: Routes = [{ path: '', component: CheckoutPage }];
