import { Component, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MoneyPipe } from '../currencies/money.pipe';
import { DashboardService } from './dashboard.service';

interface Range {
  from: string;
  to: string;
}

// The revenue billed in two chosen periods side by side (worked out as the revenue report is), in the home currency,
// with how much the second period's revenue changed from the first's.
@Component({
  selector: 'app-revenue-compare',
  imports: [MoneyPipe],
  styles: `
    .revenue-compare-periods {
      display: flex;
      flex-wrap: wrap;
      gap: 1rem;
    }
    .revenue-compare-periods fieldset {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.5rem;
    }
    .revenue-compare-table th,
    .revenue-compare-table td {
      padding: 0.25rem 0.75rem;
      text-align: left;
    }
    .revenue-compare-table .amount {
      text-align: right;
    }
  `,
  template: `
    <section aria-labelledby="revenue-compare-heading" data-testid="revenue-compare">
      <h3 id="revenue-compare-heading">Compare periods</h3>
      <form (submit)="apply($event, aFrom.value, aTo.value, bFrom.value, bTo.value)">
        <div class="revenue-compare-periods">
          <fieldset>
            <legend>Period A</legend>
            <label for="revenue-compare-a-from">From</label>
            <input #aFrom id="revenue-compare-a-from" type="date" required data-testid="revenue-compare-a-from" />
            <label for="revenue-compare-a-to">To</label>
            <input #aTo id="revenue-compare-a-to" type="date" required data-testid="revenue-compare-a-to" />
          </fieldset>
          <fieldset>
            <legend>Period B</legend>
            <label for="revenue-compare-b-from">From</label>
            <input #bFrom id="revenue-compare-b-from" type="date" required data-testid="revenue-compare-b-from" />
            <label for="revenue-compare-b-to">To</label>
            <input #bTo id="revenue-compare-b-to" type="date" required data-testid="revenue-compare-b-to" />
          </fieldset>
        </div>
        <button type="submit" data-testid="revenue-compare-apply">Compare</button>
      </form>
      @if (rangeError()) {
        <p role="alert" data-testid="revenue-compare-range-error">{{ rangeError() }}</p>
      } @else if (comparison.error()) {
        <p role="alert" data-testid="revenue-compare-error">Could not compare the periods.</p>
      } @else if (comparison.value(); as c) {
        <table class="revenue-compare-table" aria-labelledby="revenue-compare-heading" aria-live="polite"
          data-testid="revenue-compare-result">
          <thead>
            <tr>
              <th scope="col">Period</th>
              <th scope="col">Dates</th>
              <th scope="col" class="amount">Invoices</th>
              <th scope="col" class="amount">Revenue</th>
            </tr>
          </thead>
          <tbody>
            <tr data-testid="revenue-compare-a">
              <th scope="row">A</th>
              <td data-testid="revenue-compare-a-period">{{ c.a.from }} to {{ c.a.to }}</td>
              <td class="amount" data-testid="revenue-compare-a-invoices">{{ c.a.invoices }}</td>
              <td class="amount" data-testid="revenue-compare-a-total">{{ c.a.total | money: c.homeCurrency }}</td>
            </tr>
            <tr data-testid="revenue-compare-b">
              <th scope="row">B</th>
              <td data-testid="revenue-compare-b-period">{{ c.b.from }} to {{ c.b.to }}</td>
              <td class="amount" data-testid="revenue-compare-b-invoices">{{ c.b.invoices }}</td>
              <td class="amount" data-testid="revenue-compare-b-total">{{ c.b.total | money: c.homeCurrency }}</td>
            </tr>
          </tbody>
          <tfoot>
            <tr>
              <th scope="row" colspan="3">Change (B against A)</th>
              <td class="amount">
                <span data-testid="revenue-compare-change">{{ c.change | money: c.homeCurrency }}</span>
                @if (c.changePercent !== null) {
                  <span data-testid="revenue-compare-change-percent"> ({{ c.changePercent > 0 ? '+' : '' }}{{ c.changePercent }}%)</span>
                }
              </td>
            </tr>
          </tfoot>
        </table>
      }
    </section>
  `,
})
export class RevenueCompare {
  private readonly service = inject(DashboardService);

  /** The two chosen periods; nothing is compared until both are chosen. */
  private readonly periods = signal<{ a: Range; b: Range } | undefined>(undefined);
  protected readonly rangeError = signal<string | null>(null);

  protected readonly comparison = rxResource({
    params: () => this.periods(),
    stream: ({ params }) => this.service.revenueComparison(params.a, params.b),
  });

  protected apply(event: Event, aFrom: string, aTo: string, bFrom: string, bTo: string): void {
    event.preventDefault();
    if (!aFrom || !aTo || !bFrom || !bTo) {
      return;
    }
    if (aFrom > aTo || bFrom > bTo) {
      this.rangeError.set('The start date of each period must not be after its end date.');
      return;
    }
    this.rangeError.set(null);
    this.periods.set({ a: { from: aFrom, to: aTo }, b: { from: bFrom, to: bTo } });
  }
}
