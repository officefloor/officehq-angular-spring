import { Component, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MoneyPipe } from '../currencies/money.pipe';
import { DashboardService } from './dashboard.service';
import { RevenueCompare } from './revenue-compare';

// The revenue billed over a chosen date range (over all time until a range is chosen): the total of the invoices
// issued within it that were sent (drafts and cancelled invoices are left out), in the home currency, broken down
// by job so the highest-earning jobs show first, and month by month so the trend shows. The report on show can be
// exported to a CSV file. Two periods can also be compared side by side.
@Component({
  selector: 'app-revenue-report',
  imports: [MoneyPipe, RevenueCompare],
  styles: `
    .revenue-report-range {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.5rem;
    }
    .revenue-report-figures {
      display: grid;
      grid-template-columns: max-content max-content;
      gap: 0.25rem 1.5rem;
    }
    .revenue-report-figures dd {
      margin: 0;
      text-align: right;
    }
    .revenue-report-jobs th,
    .revenue-report-jobs td,
    .revenue-report-months th,
    .revenue-report-months td {
      padding: 0.25rem 0.75rem;
      text-align: left;
    }
    .revenue-report-jobs .amount,
    .revenue-report-months .amount {
      text-align: right;
    }
  `,
  template: `
    <section aria-labelledby="revenue-report-heading" data-testid="revenue-report">
      <h2 id="revenue-report-heading">Revenue report</h2>
      <form class="revenue-report-range" (submit)="apply($event, fromInput.value, toInput.value)">
        <label for="revenue-report-from">From</label>
        <input #fromInput id="revenue-report-from" type="date" required data-testid="revenue-report-from" />
        <label for="revenue-report-to">To</label>
        <input #toInput id="revenue-report-to" type="date" required data-testid="revenue-report-to" />
        <button type="submit" data-testid="revenue-report-apply">Show revenue</button>
      </form>
      @if (rangeError()) {
        <p role="alert" data-testid="revenue-report-range-error">{{ rangeError() }}</p>
      } @else if (report.error()) {
        <p role="alert" data-testid="revenue-report-error">Could not load the revenue report.</p>
      } @else if (report.value(); as r) {
        <p data-testid="revenue-report-period">
          @if (r.from && r.to) {
            From {{ r.from }} to {{ r.to }}
          } @else {
            All time
          }
        </p>
        <p>
          <button type="button" data-testid="revenue-export" [disabled]="exporting()" (click)="export()">
            Export revenue report
          </button>
        </p>
        <div role="status">
          @if (exportError()) {
            <p data-testid="revenue-export-error">Could not export the revenue report.</p>
          } @else if (exported()) {
            <p data-testid="export-confirm">Exported the revenue report to {{ exportFilename }}.</p>
          }
        </div>
        <dl class="revenue-report-figures" aria-live="polite">
          <dt>Invoices</dt>
          <dd data-testid="revenue-report-invoices">{{ r.invoices }}</dd>
          <dt>Total billed</dt>
          <dd data-testid="revenue-report-total">{{ r.total | money: r.homeCurrency }}</dd>
        </dl>
        <h3 id="revenue-by-job-heading">Revenue by job</h3>
        @if (r.jobs.length) {
          <table class="revenue-report-jobs" aria-labelledby="revenue-by-job-heading" data-testid="revenue-jobs">
            <thead>
              <tr>
                <th scope="col">Job</th>
                <th scope="col">Client</th>
                <th scope="col" class="amount">Invoices</th>
                <th scope="col" class="amount">Revenue</th>
              </tr>
            </thead>
            <tbody>
              @for (job of r.jobs; track job.projectId) {
                <tr [attr.data-testid]="'revenue-job-row-' + job.projectId">
                  <th scope="row" data-testid="revenue-job-name">{{ job.projectName }}</th>
                  <td data-testid="revenue-job-client">{{ job.clientName }}</td>
                  <td class="amount" data-testid="revenue-job-invoices">{{ job.invoices }}</td>
                  <td class="amount" data-testid="revenue-job-amount">{{ job.amount | money: r.homeCurrency }}</td>
                </tr>
              }
            </tbody>
          </table>
        } @else {
          <p data-testid="revenue-jobs-empty">No revenue billed in this period.</p>
        }
        <h3 id="revenue-by-month-heading">Revenue by month</h3>
        @if (r.months.length) {
          <table class="revenue-report-months" aria-labelledby="revenue-by-month-heading" data-testid="revenue-months">
            <thead>
              <tr>
                <th scope="col">Month</th>
                <th scope="col" class="amount">Invoices</th>
                <th scope="col" class="amount">Revenue</th>
              </tr>
            </thead>
            <tbody>
              @for (m of r.months; track m.month) {
                <tr [attr.data-testid]="'revenue-month-row-' + m.month">
                  <th scope="row" data-testid="revenue-month-label">{{ monthLabel(m.month) }}</th>
                  <td class="amount" data-testid="revenue-month-invoices">{{ m.invoices }}</td>
                  <td class="amount" data-testid="revenue-month-amount">{{ m.amount | money: r.homeCurrency }}</td>
                </tr>
              }
            </tbody>
          </table>
        } @else {
          <p data-testid="revenue-months-empty">No revenue billed in this period.</p>
        }
      }
      <app-revenue-compare />
    </section>
  `,
})
export class RevenueReport {
  private readonly service = inject(DashboardService);

  /** The chosen date range; until one is chosen the report covers all time. */
  private readonly range = signal<{ from: string; to: string } | null>(null);
  protected readonly rangeError = signal<string | null>(null);

  protected readonly report = rxResource({
    params: () => ({ range: this.range() }),
    stream: ({ params }) => this.service.revenueReport(params.range ?? undefined),
  });

  private static readonly MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August',
    'September', 'October', 'November', 'December'];

  /** A yyyy-MM month as, say, "February 2026". */
  protected monthLabel(month: string): string {
    const [year, m] = month.split('-');
    return `${RevenueReport.MONTHS[Number(m) - 1]} ${year}`;
  }

  protected readonly exportFilename = 'revenue-report.csv';
  protected readonly exporting = signal(false);
  protected readonly exported = signal(false);
  protected readonly exportError = signal(false);

  /** Downloads the report on show (over the chosen range, or all time) as a CSV file and confirms it. */
  protected export(): void {
    this.exporting.set(true);
    this.exported.set(false);
    this.exportError.set(false);
    this.service.exportRevenueReport(this.range() ?? undefined).subscribe({
      next: (csv) => {
        download(csv, this.exportFilename);
        this.exported.set(true);
        this.exporting.set(false);
      },
      error: () => {
        this.exportError.set(true);
        this.exporting.set(false);
      },
    });
  }

  protected apply(event: Event, from: string, to: string): void {
    event.preventDefault();
    if (!from || !to) {
      return;
    }
    if (from > to) {
      this.rangeError.set('The start date must not be after the end date.');
      return;
    }
    this.rangeError.set(null);
    this.exported.set(false);
    this.range.set({ from, to });
  }
}

/** Saves the text as a file through the browser. */
function download(text: string, filename: string): void {
  const url = URL.createObjectURL(new Blob([text], { type: 'text/csv;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url));
}
