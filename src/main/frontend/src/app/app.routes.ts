import { Routes } from '@angular/router';

// The route table. A page is a new component file registered here with one entry that lazily
// imports it; an entry's `data: { section, label }` is read by the shell to build the nav bar.
export const routes: Routes = [
  { path: '', redirectTo: 'home', pathMatch: 'full' },
  {
    path: 'home',
    data: { section: 'home', label: 'Home', order: 0 },
    loadComponent: () => import('./features/home/home').then((m) => m.Home),
  },
  {
    path: 'clients',
    data: { section: 'clients', label: 'Clients', order: 1 },
    loadComponent: () => import('./features/clients/clients').then((m) => m.Clients),
  },
];
