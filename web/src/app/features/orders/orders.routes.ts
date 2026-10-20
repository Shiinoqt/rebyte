import { Routes } from '@angular/router';
import { OrdersPage } from './orders.page';
import { AccountSectionPage } from '../profile/account-layout';

export const ORDERS_ROUTES: Routes = [
  {
    path: '',
    component: AccountSectionPage,
    data: { section: 'orders' },
    children: [{ path: '', component: OrdersPage }],
  },
];
