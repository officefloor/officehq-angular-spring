import { Component, effect, inject, input, signal, untracked } from '@angular/core';
import { InvoiceDetail, InvoiceService, InvoiceSummary } from './invoice.service';
import { InvoicePanel } from './invoice-panel';
import { money, statusLabel } from './invoice-format';

// One client's invoices, each with its status, total, issue and due dates. Clicking an invoice
// opens it in the invoice panel beside the list; New invoice starts an empty draft and opens it.
// An invoice looked up by its number is opened as soon as its client's invoices are shown.
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
                  <span data-testid="invoice-number">{{ i.number }}</span>
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
      <app-invoice-panel [invoiceId]="id" (changed)="totalChanged($event)" />
    }
  `,
})
export class ClientInvoices {
  private readonly service = inject(InvoiceService);

  readonly clientId = input.required<number>();
  readonly clientName = input('');
  // An invoice to open, looked up by number; a new object for each lookup, so the same invoice
  // can be opened again.
  readonly openInvoice = input<{ id: number; clientId: number } | null>(null);

  protected readonly invoices = signal<InvoiceSummary[]>([]);
  protected readonly selectedId = signal<number | null>(null);
  protected readonly creating = signal(false);
  protected readonly statusLabel = statusLabel;
  protected readonly money = money;

  constructor() {
    effect((onCleanup) => {
      const id = this.clientId();
      this.invoices.set([]);
      const open = untracked(this.openInvoice);
      this.selectedId.set(open?.clientId === id ? open.id : null);
      const sub = this.service.forClient(id).subscribe((list) => this.invoices.set(list));
      onCleanup(() => sub.unsubscribe());
    });
    effect(() => {
      const open = this.openInvoice();
      if (open && open.clientId === untracked(this.clientId)) {
        this.selectedId.set(open.id);
      }
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

  // An invoice changed in the panel: show its new status, dates and total in the list.
  protected totalChanged(detail: InvoiceDetail): void {
    this.invoices.update((list) =>
      list.map((i) =>
        i.id === detail.id
          ? {
              ...i,
              status: detail.status,
              issueDate: detail.issueDate,
              dueDate: detail.dueDate,
              total: detail.total,
            }
          : i,
      ),
    );
  }
}
