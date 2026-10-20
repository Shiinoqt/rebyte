import { Routes } from '@angular/router';
import { PaymentReturnPage } from './payment-return.page';

export const PAYMENT_RETURN_ROUTES: Routes = [
  { path: '', component: PaymentReturnPage, data: { mode: 'return' } },
];

export const PAYMENT_CANCEL_ROUTES: Routes = [
  { path: '', component: PaymentReturnPage, data: { mode: 'cancel' } },
];
