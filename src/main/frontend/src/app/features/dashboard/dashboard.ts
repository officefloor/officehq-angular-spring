import { Component, inject, signal } from '@angular/core';
import { MoneyPipe } from '../currencies/money.pipe';
import { DashboardService, DashboardSummary } from './dashboard.service';

// Dashboard page: how many clients and projects there are, and the total still owed in each currency
// (what is left to pay on invoices that have been sent but not yet fully paid; drafts are not
// counted; amounts in different currencies are never added together), and how many of those sent
// invoices are past their due date. Also lists the top five clients ranked by what they still owe,
// each in their own currency.
@Component({
  selector: 'app-dashboard',
  imports: [MoneyPipe],
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
          <dt>Jobs</dt>
          <dd data-testid="dashboard-projects-count">{{ s.projects }}</dd>
        </div>
        <div>
          <dt>Outstanding</dt>
          @for (t of s.outstanding; track t.currency) {
            <dd [attr.data-testid]="'dashboard-outstanding-' + t.currency">{{ t.amount | money: t.currency }}</dd>
          } @empty {
            <dd data-testid="dashboard-outstanding-none">Nothing owed</dd>
          }
        </div>
        <div>
          <dt>Overdue invoices</dt>
          <dd data-testid="dashboard-overdue-count">{{ s.overdue }}</dd>
        </div>
      </dl>

      <section data-testid="dashboard-top-clients" aria-labelledby="dashboard-top-clients-heading">
        <h2 id="dashboard-top-clients-heading">Top clients by amount owed</h2>
        @if (s.topClients.length) {
          <ol>
            @for (c of s.topClients; track c.id) {
              <li [attr.data-testid]="'top-client-row-' + c.id">
                <span data-testid="top-client-name">{{ c.name }}</span>:
                <span data-testid="top-client-amount">{{ c.outstanding | money: c.currency }}</span>
              </li>
            }
          </ol>
        } @else {
          <p data-testid="dashboard-top-clients-empty">No clients owe anything.</p>
        }
      </section>
    } @else {
      <p data-testid="dashboard-loading">Loading…</p>
    }
  `,
})
export class Dashboard {
  protected readonly summary = signal<DashboardSummary | null>(null);
  protected readonly loadError = signal<string | null>(null);

  constructor() {
    inject(DashboardService)
      .summary()
      .subscribe({
        next: (s) => this.summary.set(s),
        error: () => this.loadError.set('Could not load the dashboard. Please try again.'),
      });
  }
}
