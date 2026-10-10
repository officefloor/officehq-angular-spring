import { Component, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { MoneyPipe } from '../currencies/money.pipe';
import { DashboardService } from './dashboard.service';

// A simple cash-flow forecast: the money expected in from instalments still to be paid on sent invoices,
// earliest due first, with the total in the home currency.
@Component({
  selector: 'app-cash-flow-forecast',
  imports: [MoneyPipe, RouterLink],
  template: `
    <section aria-labelledby="forecast-heading" data-testid="forecast">
      <h2 id="forecast-heading">Cash-flow forecast</h2>
      @if (forecast.error()) {
        <p role="alert" data-testid="forecast-error">Could not load the forecast.</p>
      } @else if (forecast.value(); as f) {
        @if (f.entries.length === 0) {
          <p data-testid="forecast-empty">No instalments expected in.</p>
        } @else {
          <table>
            <thead>
              <tr>
                <th scope="col">Due</th>
                <th scope="col">Job</th>
                <th scope="col">Client</th>
                <th scope="col">Amount</th>
              </tr>
            </thead>
            <tbody>
              @for (e of f.entries; track e.id) {
                <tr [attr.data-testid]="'forecast-row-' + e.id">
                  <td data-testid="forecast-date">{{ e.date }}</td>
                  <td data-testid="forecast-project">
                    <a [routerLink]="['/projects', e.projectId]">{{ e.projectName }}</a>
                  </td>
                  <td data-testid="forecast-client">{{ e.clientName }}</td>
                  <td data-testid="forecast-amount">{{ e.amount | money: e.currency }}</td>
                </tr>
              }
            </tbody>
            <tfoot>
              <tr>
                <th scope="row" colspan="3">Total expected (in {{ f.homeCurrency }})</th>
                <td data-testid="forecast-total">{{ f.total | money: f.homeCurrency }}</td>
              </tr>
            </tfoot>
          </table>
        }
      } @else {
        <p data-testid="forecast-loading">Loading…</p>
      }
    </section>
  `,
})
export class CashFlowForecast {
  private readonly service = inject(DashboardService);

  protected readonly forecast = rxResource({ stream: () => this.service.forecast() });
}
