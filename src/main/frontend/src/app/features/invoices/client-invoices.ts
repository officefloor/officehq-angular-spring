import { Component, effect, inject, input, signal } from '@angular/core';
import { InvoiceService, InvoiceStatus, InvoiceSummary } from './invoice.service';

const STATUS_LABEL: Record<InvoiceStatus, string> = { DRAFT: 'Draft', SENT: 'Sent' };
const MONEY = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });

// One client's invoices, each with its status, total and due date.
@Component({
  selector: 'app-client-invoices',
  template: `
    <section aria-labelledby="invoices-heading">
      <h2 id="invoices-heading">Invoices for {{ clientName() }}</h2>
      <table data-testid="invoice-list">
        <thead>
          <tr>
            <th scope="col">Invoice</th>
            <th scope="col">Status</th>
            <th scope="col">Total</th>
            <th scope="col">Due</th>
          </tr>
        </thead>
        <tbody>
          @for (i of invoices(); track i.id) {
            <tr [attr.data-testid]="'invoice-row-' + i.id">
              <td>#{{ i.id }}</td>
              <td data-testid="invoice-status">{{ statusLabel(i.status) }}</td>
              <td data-testid="invoice-total">{{ money(i.total) }}</td>
              <td data-testid="invoice-due">{{ i.dueDate }}</td>
            </tr>
          } @empty {
            <tr>
              <td colspan="4" data-testid="invoice-empty">No invoices yet.</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class ClientInvoices {
  private readonly service = inject(InvoiceService);

  readonly clientId = input.required<number>();
  readonly clientName = input('');

  protected readonly invoices = signal<InvoiceSummary[]>([]);

  constructor() {
    effect((onCleanup) => {
      const id = this.clientId();
      this.invoices.set([]);
      const sub = this.service.forClient(id).subscribe((list) => this.invoices.set(list));
      onCleanup(() => sub.unsubscribe());
    });
  }

  protected statusLabel(status: InvoiceStatus): string {
    return STATUS_LABEL[status];
  }

  // The total is already to the cent, so the number shown is exact.
  protected money(total: string): string {
    return MONEY.format(Number(total));
  }
}
