import { Component, effect, inject, input, signal } from '@angular/core';
import { InvoiceService, InvoiceSummary } from './invoice.service';
import { InvoicePanel } from './invoice-panel';
import { money, statusLabel } from './invoice-format';

// One client's invoices, each with its status, total, issue and due dates. Clicking an invoice
// opens it in the invoice panel beside the list; New invoice starts an empty draft and opens it.
@Component({
  selector: 'app-client-invoices',
  imports: [InvoicePanel],
  styles: `
    :host {
      display: flex;
      flex-wrap: wrap;
      gap: 2rem;
      align-items: flex-start;
    }
    tr[aria-selected='true'] {
      font-weight: bold;
    }
    .open {
      font: inherit;
      color: inherit;
      background: none;
      border: none;
      padding: 0;
      cursor: pointer;
      text-decoration: underline;
    }
  `,
  template: `
    <section aria-labelledby="invoices-heading">
      <h2 id="invoices-heading">Invoices for {{ clientName() }}</h2>
      <button type="button" data-testid="invoice-new" [disabled]="creating()" (click)="newDraft()">
        New invoice
      </button>
      <table data-testid="invoice-list">
        <thead>
          <tr>
            <th scope="col">Invoice</th>
            <th scope="col">Status</th>
            <th scope="col">Total</th>
            <th scope="col">Issued</th>
            <th scope="col">Due</th>
          </tr>
        </thead>
        <tbody>
          @for (i of invoices(); track i.id) {
            <tr
              [attr.data-testid]="'invoice-row-' + i.id"
              [attr.aria-selected]="selectedId() === i.id"
              (click)="selectedId.set(i.id)"
            >
              <td>
                <button
                  type="button"
                  class="open"
                  (click)="$event.stopPropagation(); selectedId.set(i.id)"
                >
                  #{{ i.id }}
                </button>
              </td>
              <td data-testid="invoice-status">{{ statusLabel(i.status) }}</td>
              <td data-testid="invoice-total">{{ money(i.total) }}</td>
              <td data-testid="invoice-issue">{{ i.issueDate }}</td>
              <td data-testid="invoice-due">{{ i.dueDate }}</td>
            </tr>
          } @empty {
            <tr>
              <td colspan="5" data-testid="invoice-empty">No invoices yet.</td>
            </tr>
          }
        </tbody>
      </table>
    </section>

    @if (selectedId(); as id) {
      <app-invoice-panel [invoiceId]="id" />
    }
  `,
})
export class ClientInvoices {
  private readonly service = inject(InvoiceService);

  readonly clientId = input.required<number>();
  readonly clientName = input('');

  protected readonly invoices = signal<InvoiceSummary[]>([]);
  protected readonly selectedId = signal<number | null>(null);
  protected readonly creating = signal(false);
  protected readonly statusLabel = statusLabel;
  protected readonly money = money;

  constructor() {
    effect((onCleanup) => {
      const id = this.clientId();
      this.invoices.set([]);
      this.selectedId.set(null);
      const sub = this.service.forClient(id).subscribe((list) => this.invoices.set(list));
      onCleanup(() => sub.unsubscribe());
    });
  }

  protected newDraft(): void {
    this.creating.set(true);
    this.service.newDraft(this.clientId()).subscribe({
      next: (created) => {
        this.invoices.update((list) => [...list, created]);
        this.selectedId.set(created.id);
        this.creating.set(false);
      },
      error: () => this.creating.set(false),
    });
  }
}
