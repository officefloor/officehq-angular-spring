import { Component, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { CurrencyCode } from '../clients/client.service';
import { MoneyPipe } from '../currencies/money.pipe';
import { PaymentService } from './payment.service';

// The lump payments a client has made, newest first, each with a remittance note listing which invoices it covered
// and how much of the payment went to each.
@Component({
  selector: 'app-client-remittances',
  imports: [MoneyPipe],
  template: `
    <section aria-labelledby="client-remittances-heading" data-testid="client-remittances">
      <h2 id="client-remittances-heading">Payments received</h2>
      @if (payments.error()) {
        <p role="alert" data-testid="client-remittances-error">Could not load the client's payments.</p>
      } @else if (payments.hasValue()) {
        @if (payments.value().length === 0) {
          <p data-testid="client-remittances-none">No payments received yet.</p>
        } @else {
          <ul data-testid="client-remittance-list">
            @for (p of payments.value(); track p.id) {
              <li [attr.data-testid]="'client-remittance-' + p.id">
                <span data-testid="client-remittance-date">{{ p.date }}</span>:
                <span data-testid="client-remittance-amount">{{ p.amount | money: currency() }}</span>
                <button
                  type="button"
                  [attr.data-testid]="'remittance-open-' + p.id"
                  aria-controls="client-remittance-note"
                  [attr.aria-expanded]="openId() === p.id"
                  (click)="toggle(p.id)"
                >
                  {{ openId() === p.id ? 'Hide remittance note' : 'View remittance note' }}
                </button>
              </li>
            }
          </ul>
        }
      }
      <div id="client-remittance-note" aria-live="polite">
        @if (openId() !== null) {
          @if (remittance.error()) {
            <p role="alert" data-testid="remittance-error">Could not load the remittance note.</p>
          } @else if (remittance.hasValue()) {
            @let r = remittance.value();
            <table data-testid="remittance-note">
              <caption>
                Remittance for the payment of {{ r.amount | money: r.currency }} received on {{ r.date }}
              </caption>
              <thead>
                <tr>
                  <th scope="col">Invoice</th>
                  <th scope="col">Job</th>
                  <th scope="col">Paid ({{ r.currency }})</th>
                </tr>
              </thead>
              <tbody>
                @for (line of r.lines; track line.invoiceId) {
                  <tr [attr.data-testid]="'remittance-row-' + line.invoiceId">
                    <th scope="row" data-testid="remittance-invoice">#{{ line.invoiceId }}</th>
                    <td data-testid="remittance-project">{{ line.projectName }}</td>
                    <td data-testid="remittance-amount">{{ line.amount | money: r.currency }}</td>
                  </tr>
                }
              </tbody>
              <tfoot>
                @if (r.fromDeposits + r.fromCreditNotes > 0) {
                  <tr>
                    <th scope="row" colspan="2">From credit</th>
                    <td data-testid="remittance-from-credit">{{ r.fromDeposits + r.fromCreditNotes | money: r.currency }}</td>
                  </tr>
                }
                @if (r.toCredit > 0) {
                  <tr>
                    <th scope="row" colspan="2">Kept as credit</th>
                    <td data-testid="remittance-to-credit">{{ r.toCredit | money: r.currency }}</td>
                  </tr>
                }
                <tr>
                  <th scope="row" colspan="2">Payment received</th>
                  <td data-testid="remittance-total">{{ r.amount | money: r.currency }}</td>
                </tr>
              </tfoot>
            </table>
          }
        }
      </div>
    </section>
  `,
})
export class ClientRemittances {
  private readonly service = inject(PaymentService);

  readonly clientId = input.required<number>();
  /** The client's currency; their payments are received in it. */
  readonly currency = input.required<CurrencyCode>();

  protected readonly payments = rxResource({
    params: () => this.clientId(),
    stream: ({ params }) => this.service.listForClient(params),
  });

  /** The payment whose remittance note is shown, if any. */
  protected readonly openId = signal<number | null>(null);

  protected readonly remittance = rxResource({
    params: () => {
      const id = this.openId();
      return id === null ? undefined : { clientId: this.clientId(), paymentId: id };
    },
    stream: ({ params }) => this.service.remittance(params.clientId, params.paymentId),
  });

  protected toggle(id: number): void {
    this.openId.update((open) => (open === id ? null : id));
  }

  /** Loads the payments again, e.g. after one was recorded elsewhere on the page. */
  reload(): void {
    this.payments.reload();
  }
}
