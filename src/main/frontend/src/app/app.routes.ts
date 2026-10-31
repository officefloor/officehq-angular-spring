import { Routes } from '@angular/router';

// The route table. A page is a new component file registered here with one entry that lazily
// imports it; an entry's `data: { section, label }` is read by the shell to build the nav bar.
// Everything happens on the one screen at /, so home is served there directly.
export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    data: { section: 'home', label: 'Home', order: 0 },
    loadComponent: () => import('./features/home/home').then((m) => m.Home),
  },
  { path: '**', redirectTo: '' },
];
