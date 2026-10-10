import { Component, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MoneyPipe } from '../currencies/money.pipe';
import { DashboardService } from './dashboard.service';

// An aging report across all clients: what is left to pay on sent invoices, in the home currency, split by how many
// days past due each invoice is — current (not yet due, or up to 30 days overdue), 31 to 60 days, and more than 60.
@Component({
  selector: 'app-aging-report',
  imports: [MoneyPipe],
  styles: `
    .aging-report-figures {
      display: grid;
      grid-template-columns: max-content max-content;
      gap: 0.25rem 1.5rem;
    }
    .aging-report-figures dd {
      margin: 0;
      text-align: right;
    }
  `,
  template: `
    <section aria-labelledby="aging-report-heading" data-testid="aging-report">
      <h2 id="aging-report-heading">Aging report</h2>
      @if (report.error()) {
        <p role="alert" data-testid="aging-report-error">Could not load the aging report.</p>
      } @else if (report.value(); as r) {
        <dl class="aging-report-figures">
          <dt>Current (up to 30 days overdue)</dt>
          <dd data-testid="aging-report-current">{{ r.current | money: r.homeCurrency }}</dd>
          <dt>31 to 60 days overdue</dt>
          <dd data-testid="aging-report-30-60">{{ r.days30To60 | money: r.homeCurrency }}</dd>
          <dt>More than 60 days overdue</dt>
          <dd data-testid="aging-report-60-plus">{{ r.days60Plus | money: r.homeCurrency }}</dd>
          <dt>Total outstanding</dt>
          <dd data-testid="aging-report-total">{{ r.total | money: r.homeCurrency }}</dd>
        </dl>
        <p data-testid="aging-report-asof">As at {{ r.asOf }}</p>
      } @else {
        <p data-testid="aging-report-loading">Loading…</p>
      }
    </section>
  `,
})
export class AgingReportPanel {
  private readonly service = inject(DashboardService);

  protected readonly report = rxResource({ stream: () => this.service.agingReport() });
}
