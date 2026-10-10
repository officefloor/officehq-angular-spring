import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MoneyPipe } from '../currencies/money.pipe';
import { DashboardService } from './dashboard.service';

// How the overdue total has changed over recent months: what was overdue at the close of each month (as at today for
// the current month), in the home currency, with the change from the month before and a bar sized against the
// biggest month so the trend can be seen at a glance.
@Component({
  selector: 'app-overdue-trend',
  imports: [MoneyPipe],
  styles: `
    .overdue-trend td.amount {
      text-align: right;
    }
    .overdue-trend-bar {
      display: inline-block;
      height: 0.75em;
      min-width: 1px;
      background: #8a1c1c;
    }
  `,
  template: `
    <section class="overdue-trend" aria-labelledby="overdue-trend-heading" data-testid="overdue-trend">
      <h2 id="overdue-trend-heading">Overdue trend</h2>
      @if (trend.error()) {
        <p role="alert" data-testid="overdue-trend-error">Could not load the overdue trend.</p>
      } @else if (trend.value(); as t) {
        <table data-testid="overdue-trend-table">
          <caption class="visually-hidden">What was overdue at the close of each month, in {{ t.homeCurrency }}</caption>
          <thead>
            <tr>
              <th scope="col">Month</th>
              <th scope="col">Overdue ({{ t.homeCurrency }})</th>
              <th scope="col">Change</th>
              <th scope="col"><span class="visually-hidden">Relative size</span></th>
            </tr>
          </thead>
          <tbody>
            @for (m of t.months; track m.month) {
              <tr [attr.data-testid]="'overdue-trend-row-' + m.month">
                <th scope="row" data-testid="overdue-trend-month">{{ m.month }}</th>
                <td class="amount" data-testid="overdue-trend-amount">{{ m.amount | money: t.homeCurrency }}</td>
                <td class="amount" data-testid="overdue-trend-change">
                  @if (m.change === null) {
                    —
                  } @else {
                    {{ m.change > 0 ? '+' : '' }}{{ m.change | money: t.homeCurrency }}
                  }
                </td>
                <td aria-hidden="true">
                  <span class="overdue-trend-bar" [style.width.px]="barWidth(m.amount)"></span>
                </td>
              </tr>
            }
          </tbody>
        </table>
        <p data-testid="overdue-trend-asof">As at {{ t.asOf }}</p>
      } @else {
        <p data-testid="overdue-trend-loading">Loading…</p>
      }
    </section>
  `,
})
export class OverdueTrendPanel {
  private readonly service = inject(DashboardService);

  protected readonly trend = rxResource({ stream: () => this.service.overdueTrend() });

  private readonly largest = computed(() =>
    Math.max(0, ...(this.trend.value()?.months.map((m) => m.amount) ?? [])),
  );

  protected barWidth(amount: number): number {
    const largest = this.largest();
    return largest > 0 ? Math.round((amount / largest) * 120) : 0;
  }
}
