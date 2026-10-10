import { Component, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MoneyPipe } from '../currencies/money.pipe';
import { DashboardService } from './dashboard.service';

// The tax charged over a chosen date range: the main (sales) tax and the levy, each totalled separately, on invoices
// issued within it that were sent (drafts and cancelled invoices are left out), in the home currency.
@Component({
  selector: 'app-tax-summary',
  imports: [MoneyPipe],
  styles: `
    .tax-summary-range {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.5rem;
    }
    .tax-summary-figures {
      display: grid;
      grid-template-columns: max-content max-content;
      gap: 0.25rem 1.5rem;
    }
    .tax-summary-figures dd {
      margin: 0;
      text-align: right;
    }
  `,
  template: `
    <section aria-labelledby="tax-summary-heading" data-testid="tax-summary">
      <h2 id="tax-summary-heading">Tax summary</h2>
      <form class="tax-summary-range" (submit)="apply($event, fromInput.value, toInput.value)">
        <label for="tax-summary-from">From</label>
        <input #fromInput id="tax-summary-from" type="date" required data-testid="tax-summary-from" />
        <label for="tax-summary-to">To</label>
        <input #toInput id="tax-summary-to" type="date" required data-testid="tax-summary-to" />
        <button type="submit" data-testid="tax-summary-apply">Show tax</button>
      </form>
      @if (rangeError()) {
        <p role="alert" data-testid="tax-summary-range-error">{{ rangeError() }}</p>
      } @else if (summary.error()) {
        <p role="alert" data-testid="tax-summary-error">Could not load the tax summary.</p>
      } @else if (summary.value(); as t) {
        <dl class="tax-summary-figures" aria-live="polite">
          <dt>Invoices</dt>
          <dd data-testid="tax-summary-invoices">{{ t.invoices }}</dd>
          <dt>Main tax</dt>
          <dd data-testid="tax-summary-tax">
            <span data-testid="tax-summary-primary">{{ t.tax | money: t.homeCurrency }}</span>
          </dd>
          <dt>Levy</dt>
          <dd data-testid="tax-summary-levy">{{ t.levy | money: t.homeCurrency }}</dd>
          <dt>Total tax charged</dt>
          <dd data-testid="tax-summary-total">{{ t.total | money: t.homeCurrency }}</dd>
        </dl>
      }
    </section>
  `,
})
export class TaxSummaryReport {
  private readonly service = inject(DashboardService);

  private readonly range = signal<{ from: string; to: string } | undefined>(undefined);
  protected readonly rangeError = signal<string | null>(null);

  protected readonly summary = rxResource({
    params: () => this.range(),
    stream: ({ params }) => this.service.taxSummary(params.from, params.to),
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
