import { Routes } from '@angular/router';
import { OrderDetailPage } from './order-detail.page';

// The parent entry in app.routes.ts supplies :id.
export const ORDER_DETAIL_ROUTES: Routes = [{ path: '', component: OrderDetailPage }];
