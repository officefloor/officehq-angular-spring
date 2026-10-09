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
    path: 'dashboard',
    data: { section: 'dashboard', label: 'Dashboard', order: 0 },
    loadComponent: () => import('./features/dashboard/dashboard').then((m) => m.Dashboard),
  },
  {
    path: 'clients',
    data: { section: 'clients', label: 'Clients', order: 1 },
    loadComponent: () => import('./features/clients/clients').then((m) => m.Clients),
  },
  {
    path: 'clients/:id',
    loadComponent: () => import('./features/clients/client-detail').then((m) => m.ClientDetail),
  },
  {
    path: 'projects',
    data: { section: 'projects', label: 'Projects', order: 2 },
    loadComponent: () => import('./features/projects/projects').then((m) => m.Projects),
  },
  {
    path: 'invoices',
    data: { section: 'invoices', label: 'Invoices', order: 3 },
    loadComponent: () => import('./features/invoices/invoices').then((m) => m.Invoices),
  },
  {
    path: 'projects/:id',
    loadComponent: () => import('./features/projects/project-detail').then((m) => m.ProjectDetail),
  },
];
