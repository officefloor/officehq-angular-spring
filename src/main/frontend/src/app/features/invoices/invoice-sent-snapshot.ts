import { Component, input, signal } from '@angular/core';
import { MoneyPipe } from '../currencies/money.pipe';
import { CurrencyCode } from '../clients/client.service';
import { InvoiceSnapshot } from './invoice.service';

// A sent invoice as it read when it was sent, kept as it was even if the rules have changed since.
@Component({
  selector: 'app-invoice-sent-snapshot',
  imports: [MoneyPipe],
  template: `
    <section aria-labelledby="invoice-snapshot-heading" data-testid="invoice-snapshot-section">
      <h2 id="invoice-snapshot-heading">As sent</h2>
      <button
        type="button"
        data-testid="invoice-snapshot-view"
        aria-controls="invoice-snapshot-panel"
        [attr.aria-expanded]="open()"
        (click)="open.set(!open())"
      >
        {{ open() ? 'Hide invoice as sent' : 'View invoice as sent' }}
      </button>
      @if (open()) {
        <dl id="invoice-snapshot-panel" data-testid="invoice-snapshot">
          <dt>Sent on</dt>
          <dd data-testid="invoice-snapshot-date">{{ snapshot().date }}</dd>
          <dt>Total as sent</dt>
          <dd data-testid="invoice-snapshot-total">{{ snapshot().total | money: currency() }}</dd>
        </dl>
      }
    </section>
  `,
})
export class InvoiceSentSnapshot {
  readonly snapshot = input.required<InvoiceSnapshot>();
  readonly currency = input.required<CurrencyCode>();

  protected readonly open = signal(false);
}
