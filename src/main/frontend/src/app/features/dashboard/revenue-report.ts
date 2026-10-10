import { Component, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MoneyPipe } from '../currencies/money.pipe';
import { DashboardService } from './dashboard.service';

// The revenue billed over a chosen date range: the total of the invoices issued within it that were sent
// (drafts and cancelled invoices are left out), in the home currency.
@Component({
  selector: 'app-revenue-report',
  imports: [MoneyPipe],
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
        <dl class="revenue-report-figures" aria-live="polite">
          <dt>Invoices</dt>
          <dd data-testid="revenue-report-invoices">{{ r.invoices }}</dd>
          <dt>Total billed</dt>
          <dd data-testid="revenue-report-total">{{ r.total | money: r.homeCurrency }}</dd>
        </dl>
      }
    </section>
  `,
})
export class RevenueReport {
  private readonly service = inject(DashboardService);

  private readonly range = signal<{ from: string; to: string } | undefined>(undefined);
  protected readonly rangeError = signal<string | null>(null);

  protected readonly report = rxResource({
    params: () => this.range(),
    stream: ({ params }) => this.service.revenueReport(params.from, params.to),
  });

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
    this.range.set({ from, to });
  }
}
