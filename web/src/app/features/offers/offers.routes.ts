import { Routes } from '@angular/router';
import { OffersPage } from './offers.page';
import { AccountSectionPage } from '../profile/account-layout';

export const OFFERS_ROUTES: Routes = [
  {
    path: '',
    component: AccountSectionPage,
    data: { section: 'offers' },
    children: [{ path: '', component: OffersPage }],
  },
];
