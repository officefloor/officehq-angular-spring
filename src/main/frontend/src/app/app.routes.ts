import { Routes } from '@angular/router';

// The route table. A page is a new component file registered here with one entry that lazily
// imports it; an entry's `data: { section, label }` is read by the shell to build the nav bar.
export const routes: Routes = [
  { path: '', redirectTo: 'people', pathMatch: 'full' },
  {
    path: 'home',
    data: { section: 'home', label: 'Home', order: 0 },
    loadComponent: () => import('./features/home/home').then((m) => m.Home),
  },
  {
    path: 'people',
    data: { section: 'people', label: 'People', order: 1 },
    loadComponent: () => import('./features/people/people').then((m) => m.People),
  },
];
