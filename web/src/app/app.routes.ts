import { Routes } from '@angular/router';
import { adminGuard, authGuard, guestGuard } from './core/auth/auth.guards';

export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    loadChildren: () =>
      import('./features/home-search/home-search.routes').then((m) => m.HOME_SEARCH_ROUTES),
  },
  {
    path: 'login',
    canActivate: [guestGuard],
    data: { authStep: 'login' },
    loadChildren: () => import('./features/auth/auth.routes').then((m) => m.AUTH_ROUTES),
  },
  {
    path: 'signup',
    canActivate: [guestGuard],
    data: { authStep: 'signup' },
    loadChildren: () => import('./features/auth/auth.routes').then((m) => m.AUTH_ROUTES),
  },
  {
    path: 'confirm',
    canActivate: [guestGuard],
    data: { authStep: 'confirm' },
    loadChildren: () => import('./features/auth/auth.routes').then((m) => m.AUTH_ROUTES),
  },
  {
    path: 'forgot',
    canActivate: [guestGuard],
    data: { authStep: 'forgot' },
    loadChildren: () => import('./features/auth/auth.routes').then((m) => m.AUTH_ROUTES),
  },
  {
    path: 'listings/:id/edit',
    canActivate: [authGuard],
    data: { mode: 'edit' },
    loadChildren: () =>
      import('./features/listing-form/listing-form.routes').then((m) => m.LISTING_FORM_ROUTES),
  },
  {
    path: 'listings/:id/releases',
    canActivate: [authGuard],
    loadChildren: () =>
      import('./features/developer/developer.routes').then((m) => m.DEVELOPER_ROUTES),
  },
  {
    path: 'listings/:id',
    loadChildren: () =>
      import('./features/listing-detail/listing-detail.routes').then(
        (m) => m.LISTING_DETAIL_ROUTES,
      ),
  },
  {
    path: 'u/:sub',
    loadChildren: () =>
      import('./features/profile/seller-profile.routes').then((m) => m.SELLER_PROFILE_ROUTES),
  },
  {
    path: 'sell',
    canActivate: [authGuard],
    data: { mode: 'create' },
    loadChildren: () =>
      import('./features/listing-form/listing-form.routes').then((m) => m.LISTING_FORM_ROUTES),
  },
  {
    path: 'checkout/return',
    canActivate: [authGuard],
    loadChildren: () =>
      import('./features/payment-return/payment-return.routes').then(
        (m) => m.PAYMENT_RETURN_ROUTES,
      ),
  },
  {
    path: 'checkout/cancel',
    canActivate: [authGuard],
    loadChildren: () =>
      import('./features/payment-return/payment-return.routes').then(
        (m) => m.PAYMENT_CANCEL_ROUTES,
      ),
  },
  {
    path: 'checkout/:listingId',
    canActivate: [authGuard],
    loadChildren: () =>
      import('./features/checkout/checkout.routes').then((m) => m.CHECKOUT_ROUTES),
  },
  {
    path: 'favourites',
    canActivate: [authGuard],
    loadChildren: () =>
      import('./features/favourites/favourites.routes').then((m) => m.FAVOURITES_ROUTES),
  },
  {
    path: 'orders',
    canActivate: [authGuard],
    loadChildren: () => import('./features/orders/orders.routes').then((m) => m.ORDERS_ROUTES),
  },
  {
    path: 'offers',
    canActivate: [authGuard],
    loadChildren: () => import('./features/offers/offers.routes').then((m) => m.OFFERS_ROUTES),
  },
  {
    path: 'orders/:id',
    canActivate: [authGuard],
    loadChildren: () =>
      import('./features/order-detail/order-detail.routes').then((m) => m.ORDER_DETAIL_ROUTES),
  },
  {
    path: 'conversations',
    canActivate: [authGuard],
    loadChildren: () =>
      import('./features/messages/messages.routes').then((m) => m.MESSAGES_ROUTES),
  },
  {
    path: 'notifications',
    canActivate: [authGuard],
    loadChildren: () =>
      import('./features/notifications/notifications.routes').then((m) => m.NOTIFICATIONS_ROUTES),
  },
  {
    path: 'me',
    canActivate: [authGuard],
    loadChildren: () => import('./features/profile/me.routes').then((m) => m.ME_ROUTES),
  },
  {
    path: 'settings',
    canActivate: [authGuard],
    loadChildren: () =>
      import('./features/settings/settings.routes').then((m) => m.SETTINGS_ROUTES),
  },
  {
    path: 'admin/problems',
    canActivate: [adminGuard],
    loadChildren: () =>
      import('./features/admin/admin-moderation.routes').then((m) => m.ADMIN_MODERATION_ROUTES),
  },
  {
    path: 'admin/categories',
    canActivate: [adminGuard],
    loadChildren: () =>
      import('./features/admin/admin-categories.routes').then((m) => m.ADMIN_CATEGORIES_ROUTES),
  },
  {
    path: 'admin/operations',
    canActivate: [adminGuard],
    loadChildren: () =>
      import('./features/admin/admin-operations.routes').then((m) => m.ADMIN_OPERATIONS_ROUTES),
  },
  {
    path: '**',
    loadComponent: () => import('./core/shell/not-found.page').then((m) => m.NotFoundPage),
  },
];
