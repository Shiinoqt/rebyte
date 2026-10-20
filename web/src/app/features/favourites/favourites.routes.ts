import { Routes } from '@angular/router';
import { FavouritesPage } from './favourites.page';
import { AccountSectionPage } from '../profile/account-layout';

export const FAVOURITES_ROUTES: Routes = [
  {
    path: '',
    component: AccountSectionPage,
    data: { section: 'favourites' },
    children: [{ path: '', component: FavouritesPage }],
  },
];
