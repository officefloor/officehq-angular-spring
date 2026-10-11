import { Component, effect, inject, input, signal } from '@angular/core';
import { InvoiceDetail, InvoiceService } from './invoice.service';
import { money, statusLabel } from './invoice-format';

// One opened invoice: its status, its lines and its totals.
@Component({
  selector: 'app-invoice-panel',
  template: `
    <section data-testid="invoice-panel" aria-labelledby="invoice-panel-heading">
      <h2 id="invoice-panel-heading">Invoice #{{ invoiceId() }}</h2>
      @if (invoice(); as inv) {
        <p>Status: <span data-testid="invoice-panel-status">{{ statusLabel(inv.status) }}</span></p>
        <table>
          <thead>
            <tr>
              <th scope="col">Description</th>
              <th scope="col">Qty</th>
              <th scope="col">Unit price</th>
              <th scope="col">Amount</th>
            </tr>
          </thead>
          <tbody>
            @for (l of inv.lines; track l.id) {
              <tr [attr.data-testid]="'line-row-' + l.id">
                <td data-testid="line-description">{{ l.description }}</td>
                <td data-testid="line-qty">{{ l.qty }}</td>
                <td data-testid="line-unit-price">{{ money(l.unitPrice) }}</td>
                <td data-testid="line-amount">{{ money(l.amount) }}</td>
              </tr>
            } @empty {
              <tr>
                <td colspan="4" data-testid="line-empty">No lines yet.</td>
              </tr>
            }
          </tbody>
          <tfoot>
            <tr>
              <th scope="row" colspan="3">Subtotal</th>
              <td data-testid="invoice-subtotal">{{ money(inv.subtotal) }}</td>
            </tr>
            <tr>
              <th scope="row" colspan="3">Total</th>
              <td data-testid="invoice-grand-total">{{ money(inv.total) }}</td>
            </tr>
          </tfoot>
        </table>
      }
    </section>
  `,
})
export class InvoicePanel {
  private readonly service = inject(InvoiceService);

  readonly invoiceId = input.required<number>();

  protected readonly invoice = signal<InvoiceDetail | null>(null);
  protected readonly statusLabel = statusLabel;
  protected readonly money = money;

  constructor() {
    effect((onCleanup) => {
      const id = this.invoiceId();
      this.invoice.set(null);
      const sub = this.service.open(id).subscribe((detail) => this.invoice.set(detail));
      onCleanup(() => sub.unsubscribe());
    });
  }
}
