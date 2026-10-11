import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { InvoiceDetail, InvoiceService, InvoiceSummary, NewAllocation } from './invoice.service';
import { PaymentForm } from './payment-form';
import { RefundForm } from './refund-form';
import { InvoicePanel } from './invoice-panel';
import { money, statusLabel } from './invoice-format';

// One client's invoices, each with its status, total, issue and due dates. Clicking an invoice
// opens it in the invoice panel beside the list; New invoice starts an empty draft and opens it.
// An invoice looked up by its number is opened as soon as its client's invoices are shown.
// Every sent invoice that is not void has a box for its part of a payment; once any is filled, the payment recorded
// (in the panel, or below the list when no invoice is open) is spread over those invoices.
// The client's unused credit can be refunded from the refund form below the list.
@Component({
  selector: 'app-client-invoices',
  imports: [InvoicePanel, PaymentForm, RefundForm],
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
    .allocate {
      width: 6rem;
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
      <button type="button" data-testid="invoice-send-all" [disabled]="sendingAll()" (click)="sendAllDrafts()">
        Send all drafts
      </button>
      <table data-testid="invoice-list">
        <thead>
          <tr>
            <th scope="col">Invoice</th>
            <th scope="col">Status</th>
            <th scope="col">Total</th>
            <th scope="col">Issued</th>
            <th scope="col">Due</th>
            <th scope="col">Part of payment ($)</th>
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
              <td>
                @if (i.status !== 'DRAFT' && i.status !== 'VOID' && i.status !== 'CREDIT_NOTE') {
                  <input
                    class="allocate"
                    data-testid="invoice-allocate"
                    inputmode="decimal"
                    [attr.aria-label]="'Part of payment for ' + i.number"
                    [value]="allocated()[i.id] ?? ''"
                    (click)="$event.stopPropagation()"
                    (input)="allocate(i.id, $any($event.target).value)"
                  />
                }
              </td>
            </tr>
          } @empty {
            <tr>
              <td colspan="6" data-testid="invoice-empty">No invoices yet.</td>
            </tr>
          }
        </tbody>
      </table>
      <app-refund-form [clientId]="clientId()" (refunded)="splitRecorded($event)" />
    </section>

    @if (selectedId(); as id) {
      <app-invoice-panel
        [invoiceId]="id"
        [reload]="reloadPanel()"
        [allocations]="allocations()"
        (changed)="totalChanged($event)"
        (split)="splitRecorded($event)"
        (duplicated)="duplicated($event)"
        (credited)="duplicated($event)"
      />
    } @else if (invoices().length > 0) {
      <app-payment-form
        [clientId]="clientId()"
        [allocations]="allocations()"
        (split)="splitRecorded($event)"
        (deposited)="splitRecorded($event)"
      />
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

  // An invoice of the client was changed or paid, so what the client owes may have changed.
  readonly changed = output<void>();

  protected readonly invoices = signal<InvoiceSummary[]>([]);
  protected readonly selectedId = signal<number | null>(null);
  protected readonly creating = signal(false);
  protected readonly sendingAll = signal(false);
  // Bumped to make the invoice panel load its invoice again.
  protected readonly reloadPanel = signal(0);
  // The part of a payment typed for each invoice, by id, as entered.
  protected readonly allocated = signal<Record<number, string>>({});
  // The parts that have been filled in, for the payment form.
  protected readonly allocations = computed<NewAllocation[]>(() =>
    Object.entries(this.allocated())
      .map(([id, amount]) => ({ invoiceId: Number(id), amount: amount.trim().replace(/^\$/, '') }))
      .filter((a) => a.amount !== ''),
  );
  protected readonly statusLabel = statusLabel;
  protected readonly money = money;

  constructor() {
    effect((onCleanup) => {
      const id = this.clientId();
      this.invoices.set([]);
      this.allocated.set({});
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

  // Sends every draft with lines: show their new statuses and dates, and load the open invoice
  // again so the panel shows it as sent.
  protected sendAllDrafts(): void {
    this.sendingAll.set(true);
    this.service.sendAllDrafts(this.clientId()).subscribe({
      next: (list) => {
        this.invoices.set(list);
        this.reloadPanel.update((n) => n + 1);
        this.sendingAll.set(false);
        this.changed.emit();
      },
      error: () => this.sendingAll.set(false),
    });
  }

  // An invoice was duplicated or credited: list the new draft and open it.
  protected duplicated(detail: InvoiceDetail): void {
    const { id, number, status, listedTotal, issueDate, listedDueDate, creditOf } = detail;
    this.invoices.update((list) => [
      ...list,
      { id, number, status, total: listedTotal, issueDate, dueDate: listedDueDate, creditOf },
    ]);
    this.selectedId.set(id);
  }

  protected allocate(invoiceId: number, amount: string): void {
    this.allocated.update((parts) => ({ ...parts, [invoiceId]: amount }));
  }

  // A payment was spread over several invoices: show their new statuses and clear the parts.
  protected splitRecorded(list: InvoiceSummary[]): void {
    this.invoices.set(list);
    this.allocated.set({});
    this.changed.emit();
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
              dueDate: detail.listedDueDate,
              total: detail.listedTotal,
            }
          : i,
      ),
    );
    this.changed.emit();
  }
}
