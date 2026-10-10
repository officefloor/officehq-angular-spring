import { Component, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MoneyPipe } from '../currencies/money.pipe';
import { InvoiceService } from './invoice.service';

// A client's money on one screen: what has been billed to them, how much of it is paid, what is still outstanding, and how much of that is overdue.
@Component({
  selector: 'app-client-financial-summary',
  imports: [MoneyPipe],
  styles: `
    .client-summary {
      display: grid;
      grid-template-columns: max-content max-content;
      gap: 0.25rem 1.5rem;
    }
    .client-summary dd {
      margin: 0;
      text-align: right;
    }
  `,
  template: `
    <section aria-labelledby="client-summary-heading" data-testid="client-summary">
      <h2 id="client-summary-heading">Financial summary</h2>
      @if (summary.error()) {
        <p role="alert" data-testid="client-summary-error">Could not load the financial summary.</p>
      } @else if (summary.value(); as s) {
        <dl class="client-summary">
          <dt>Billed</dt>
          <dd data-testid="client-summary-billed">{{ s.billed | money: s.currency }}</dd>
          <dt>Paid</dt>
          <dd data-testid="client-summary-paid">{{ s.paid | money: s.currency }}</dd>
          <dt>Outstanding</dt>
          <dd data-testid="client-summary-outstanding">{{ s.outstanding | money: s.currency }}</dd>
          <dt>Overdue</dt>
          <dd data-testid="client-summary-overdue">{{ s.overdue | money: s.currency }}</dd>
        </dl>
      }
    </section>
  `,
})
export class ClientFinancialSummaryPanel {
  private readonly service = inject(InvoiceService);

  readonly clientId = input.required<number>();

  protected readonly summary = rxResource({
    params: () => this.clientId(),
    stream: ({ params }) => this.service.financialSummaryForClient(params),
  });

  reload(): void {
    this.summary.reload();
  }
}
