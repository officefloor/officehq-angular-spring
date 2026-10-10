import { Component, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MoneyPipe } from '../currencies/money.pipe';
import { DashboardService } from './dashboard.service';

// The sales tax charged over a chosen date range broken down by tax rate: for each rate, the taxable base and the tax
// on invoices issued within it that were sent (drafts and cancelled invoices are left out), in the home currency.
@Component({
  selector: 'app-tax-report',
  imports: [MoneyPipe],
  styles: `
    .tax-report-range {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.5rem;
    }
    .tax-report-table td.num,
    .tax-report-table th.num {
      text-align: right;
    }
  `,
  template: `
    <section aria-labelledby="tax-report-heading" data-testid="tax-report">
      <h2 id="tax-report-heading">Tax report by rate</h2>
      <form class="tax-report-range" (submit)="apply($event, fromInput.value, toInput.value)">
        <label for="tax-report-from">From</label>
        <input #fromInput id="tax-report-from" type="date" required data-testid="tax-report-from" />
        <label for="tax-report-to">To</label>
        <input #toInput id="tax-report-to" type="date" required data-testid="tax-report-to" />
        <button type="submit" data-testid="tax-report-apply">Show report</button>
      </form>
      @if (rangeError()) {
        <p role="alert" data-testid="tax-report-range-error">{{ rangeError() }}</p>
      } @else if (report.error()) {
        <p role="alert" data-testid="tax-report-error">Could not load the tax report.</p>
      } @else if (report.value(); as r) {
        <div aria-live="polite">
          @if (r.rates.length === 0) {
            <p data-testid="tax-report-empty">No tax was charged in this period.</p>
          } @else {
            <table class="tax-report-table" data-testid="tax-report-table">
              <caption>Tax by rate, {{ r.from }} to {{ r.to }}</caption>
              <thead>
                <tr>
                  <th scope="col">Rate</th>
                  <th scope="col" class="num">Invoices</th>
                  <th scope="col" class="num">Taxable base</th>
                  <th scope="col" class="num">Tax</th>
                </tr>
              </thead>
              <tbody>
                @for (rate of r.rates; track rate.rate) {
                  <tr [attr.data-testid]="'tax-report-row-' + rate.rate">
                    <th scope="row" data-testid="tax-report-rate">{{ rate.rate }}%</th>
                    <td class="num" data-testid="tax-report-invoices">{{ rate.invoices }}</td>
                    <td class="num" data-testid="tax-report-base">{{ rate.taxableBase | money: r.homeCurrency }}</td>
                    <td class="num" data-testid="tax-report-tax">{{ rate.tax | money: r.homeCurrency }}</td>
                  </tr>
                }
              </tbody>
              <tfoot>
                <tr>
                  <th scope="row">Total</th>
                  <td class="num" data-testid="tax-report-total-invoices">{{ r.invoices }}</td>
                  <td class="num" data-testid="tax-report-total-base">{{ r.taxableBase | money: r.homeCurrency }}</td>
                  <td class="num" data-testid="tax-report-total-tax">{{ r.tax | money: r.homeCurrency }}</td>
                </tr>
              </tfoot>
            </table>
          }
        </div>
      }
    </section>
  `,
})
export class TaxReport {
  private readonly service = inject(DashboardService);

  private readonly range = signal<{ from: string; to: string } | undefined>(undefined);
  protected readonly rangeError = signal<string | null>(null);

  protected readonly report = rxResource({
    params: () => this.range(),
    stream: ({ params }) => this.service.taxReport(params.from, params.to),
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
