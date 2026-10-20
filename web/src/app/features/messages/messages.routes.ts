import { Routes } from '@angular/router';
import { MessagesPage } from './messages.page';

export const MESSAGES_ROUTES: Routes = [
  { path: '', component: MessagesPage },
  { path: ':id', component: MessagesPage },
];
