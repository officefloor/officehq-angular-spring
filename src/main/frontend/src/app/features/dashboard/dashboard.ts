import { Component, computed, inject, signal } from '@angular/core';
import { DashboardService, DashboardSummary } from './dashboard.service';

// Dashboard page: how many clients and projects there are, and the total still owed (the sum of
// all unpaid invoices).
@Component({
  selector: 'app-dashboard',
  template: `
    <h1>Dashboard</h1>

    @if (loadError()) {
      <p role="alert" data-testid="dashboard-error">{{ loadError() }}</p>
    } @else if (summary(); as s) {
      <dl data-testid="dashboard-summary">
        <div>
          <dt>Clients</dt>
          <dd data-testid="dashboard-clients-count">{{ s.clients }}</dd>
        </div>
        <div>
          <dt>Projects</dt>
          <dd data-testid="dashboard-projects-count">{{ s.projects }}</dd>
        </div>
        <div>
          <dt>Outstanding</dt>
          <dd data-testid="dashboard-outstanding-total">{{ outstanding() }}</dd>
        </div>
      </dl>
    } @else {
      <p data-testid="dashboard-loading">Loading…</p>
    }
  `,
})
export class Dashboard {
  protected readonly summary = signal<DashboardSummary | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly outstanding = computed(() => (this.summary()?.outstanding ?? 0).toFixed(2));

  constructor() {
    inject(DashboardService)
      .summary()
      .subscribe({
        next: (s) => this.summary.set(s),
        error: () => this.loadError.set('Could not load the dashboard. Please try again.'),
      });
  }
}
