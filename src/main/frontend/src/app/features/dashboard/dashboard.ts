import { Component, inject, signal } from '@angular/core';
import { MoneyPipe } from '../currencies/money.pipe';
import { DashboardService, DashboardSummary } from './dashboard.service';
import { AgingReportPanel } from './aging-report';
import { CashFlowForecast } from './cash-flow-forecast';
import { RevenueReport } from './revenue-report';
import { TaxReport } from './tax-report';
import { TaxSummaryReport } from './tax-summary';
import { UpcomingRecurringInvoices } from './upcoming-recurring-invoices';

// Dashboard page: how many clients and projects there are (and how many clients were taken on this month), and the total still owed in each currency
// (what is left to pay on invoices that have been sent but not yet fully paid; drafts are not
// counted), plus one grand total of it in the home currency with each invoice converted at the
// exchange rate from its own issue date (leaving out disputed and written-off invoices), and how many of those sent
// invoices (not disputed — a disputed invoice is kept out of the overdue chase) are past their due date, with what is overdue on them (left to pay plus accrued late fees and
// instalment interest, in the home currency), also split by how many days overdue each invoice is. Also lists the top five clients ranked by what they still owe
// converted into the home currency, each shown in their own currency and in the home currency, and the recurring invoices coming up with when each falls, and a forecast of the money expected in from scheduled instalments. Also shows how many tasks not yet done are past their due date, and the average number of days clients take to pay (issue date to final payment on paid invoices). An aging report across all clients, a tax summary, a tax report by rate and a revenue report for a chosen date range can be opened from here.
@Component({
  selector: 'app-dashboard',
  imports: [MoneyPipe, AgingReportPanel, CashFlowForecast, RevenueReport, TaxReport, TaxSummaryReport, UpcomingRecurringInvoices],
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
          <dt>New clients this month</dt>
          <dd data-testid="dashboard-new-clients">{{ s.newClientsThisMonth }}</dd>
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
          <dt>Outstanding in {{ s.homeCurrency }} (converted at each invoice's date, excluding disputed and written-off)</dt>
          <dd data-testid="dashboard-outstanding-home"><span data-testid="kpi-outstanding">{{ s.outstandingHome | money: s.homeCurrency }}</span></dd>
        </div>
        <div>
          <dt>Overdue invoices</dt>
          <dd data-testid="dashboard-overdue-count">{{ s.overdue }}</dd>
        </div>
        <div>
          <dt>Overdue amount (including late fees and interest)</dt>
          <dd data-testid="dashboard-overdue-amount">{{ s.overdueAmount | money: s.homeCurrency }}</dd>
        </div>
        <div>
          <dt>Overdue up to 30 days</dt>
          <dd data-testid="dashboard-overdue-0-30">{{ s.overdueBuckets.days0To30 | money: s.homeCurrency }}</dd>
        </div>
        <div>
          <dt>Overdue 31 to 60 days</dt>
          <dd data-testid="dashboard-overdue-31-60">{{ s.overdueBuckets.days31To60 | money: s.homeCurrency }}</dd>
        </div>
        <div>
          <dt>Overdue more than 60 days</dt>
          <dd data-testid="dashboard-overdue-60-plus">{{ s.overdueBuckets.days60Plus | money: s.homeCurrency }}</dd>
        </div>
        <div>
          <dt>Overdue tasks</dt>
          <dd data-testid="dashboard-overdue-tasks-count">{{ s.overdueTasks }}</dd>
        </div>
        <div>
          <dt>Average days to pay</dt>
          @if (s.averageDaysToPay !== null) {
            <dd data-testid="kpi-days-to-pay">{{ s.averageDaysToPay }}</dd>
          } @else {
            <dd data-testid="kpi-days-to-pay-none">No paid invoices yet</dd>
          }
        </div>
      </dl>

      <section data-testid="dashboard-top-clients" aria-labelledby="dashboard-top-clients-heading">
        <h2 id="dashboard-top-clients-heading">Top clients by amount owed (in {{ s.homeCurrency }})</h2>
        @if (s.topClients.length) {
          <ol>
            @for (c of s.topClients; track c.id) {
              <li [attr.data-testid]="'top-client-row-' + c.id">
                <span data-testid="top-client-name">{{ c.name }}</span>:
                <span data-testid="top-client-amount">{{ c.outstanding | money: c.currency }}</span>
                @if (c.currency !== s.homeCurrency) {
                  (<span data-testid="top-client-amount-home">{{ c.outstandingHome | money: s.homeCurrency }}</span>)
                }
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

    <app-upcoming-recurring-invoices />

    <app-cash-flow-forecast />

    <button
      type="button"
      data-testid="aging-report-open"
      aria-controls="aging-report"
      [attr.aria-expanded]="agingReportOpen()"
      (click)="agingReportOpen.set(!agingReportOpen())"
    >
      {{ agingReportOpen() ? 'Hide aging report' : 'Aging report' }}
    </button>
    @if (agingReportOpen()) {
      <app-aging-report id="aging-report" />
    }

    <button
      type="button"
      data-testid="tax-summary-open"
      aria-controls="tax-summary"
      [attr.aria-expanded]="taxSummaryOpen()"
      (click)="taxSummaryOpen.set(!taxSummaryOpen())"
    >
      {{ taxSummaryOpen() ? 'Hide tax summary' : 'Tax summary' }}
    </button>
    @if (taxSummaryOpen()) {
      <app-tax-summary id="tax-summary" />
    }

    <button
      type="button"
      data-testid="tax-report-open"
      aria-controls="tax-report"
      [attr.aria-expanded]="taxReportOpen()"
      (click)="taxReportOpen.set(!taxReportOpen())"
    >
      {{ taxReportOpen() ? 'Hide tax report' : 'Tax report by rate' }}
    </button>
    @if (taxReportOpen()) {
      <app-tax-report id="tax-report" />
    }

    <button
      type="button"
      data-testid="revenue-report-open"
      aria-controls="revenue-report"
      [attr.aria-expanded]="revenueReportOpen()"
      (click)="revenueReportOpen.set(!revenueReportOpen())"
    >
      {{ revenueReportOpen() ? 'Hide revenue report' : 'Revenue report' }}
    </button>
    @if (revenueReportOpen()) {
      <app-revenue-report id="revenue-report" />
    }
  `,
})
export class Dashboard {
  protected readonly summary = signal<DashboardSummary | null>(null);
  protected readonly loadError = signal<string | null>(null);
  protected readonly agingReportOpen = signal(false);
  protected readonly taxSummaryOpen = signal(false);
  protected readonly taxReportOpen = signal(false);
  protected readonly revenueReportOpen = signal(false);

  constructor() {
    inject(DashboardService)
      .summary()
      .subscribe({
        next: (s) => this.summary.set(s),
        error: () => this.loadError.set('Could not load the dashboard. Please try again.'),
      });
  }
}
