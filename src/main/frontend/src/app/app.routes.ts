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
    // Wait for a payment being recorded to be saved before leaving, so other pages see it.
    canDeactivate: [(page: { canLeave(): Promise<boolean> }) => page.canLeave()],
    loadComponent: () => import('./features/clients/client-detail').then((m) => m.ClientDetail),
  },
  {
    path: 'clients/:id/statement',
    loadComponent: () => import('./features/invoices/client-statement').then((m) => m.ClientStatement),
  },
  {
    path: 'projects',
    data: { section: 'projects', label: 'Jobs', order: 2 },
    loadComponent: () => import('./features/projects/projects').then((m) => m.Projects),
  },
  {
    path: 'projects/tasks-by-job',
    loadComponent: () => import('./features/tasks/tasks-by-job').then((m) => m.TasksByJob),
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
  {
    path: 'projects/:projectId/invoices/:invoiceId',
    loadComponent: () => import('./features/invoices/invoice-detail').then((m) => m.InvoiceDetailPage),
  },
  {
    path: 'settings',
    data: { section: 'settings', label: 'Settings', order: 4 },
    loadComponent: () => import('./features/settings/settings').then((m) => m.SettingsPage),
  },
];
