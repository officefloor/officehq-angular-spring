import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { InvoiceDetail, InvoiceService, InvoiceSummary } from './invoice.service';
import { PaymentForm } from './payment-form';
import { RefundForm } from './refund-form';
import { RateForm } from './rate-form';
import { InvoicePanel } from './invoice-panel';
import { Currency, money, statusLabel } from './invoice-format';

// One client's invoices, each with its status, total, issue and due dates. Clicking an invoice
// opens it in the invoice panel beside the list; New invoice starts an empty draft and opens it.
// An invoice looked up by its number is opened as soon as its client's invoices are shown.
// A payment recorded in the panel goes to the open invoice; one recorded below the list when no invoice
// is open goes to the invoices its references name, or is spread automatically.
// The client's unused credit can be refunded from the refund form below the list, or applied now to
// what they owe with Apply credit.
@Component({
  selector: 'app-client-invoices',
  imports: [InvoicePanel, PaymentForm, RefundForm, RateForm],
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
      <button type="button" data-testid="invoice-send-all" [disabled]="sendingAll()" (click)="sendAllDrafts()">
        Send all drafts
      </button>
      <button type="button" data-testid="client-apply-credit" [disabled]="applying()" (click)="applyCredit()">
        Apply credit
      </button>
      @if (applyError()) {
        <p role="alert" data-testid="client-apply-credit-error">{{ applyError() }}</p>
      }
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
              <td data-testid="invoice-total">{{ money(i.total, currency()) }}</td>
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
      <app-refund-form [clientId]="clientId()" [currency]="currency()" (refunded)="splitRecorded($event)" />
      <app-rate-form [clientId]="clientId()" (saved)="rateSaved($event)" />
    </section>

    @if (selectedId(); as id) {
      <app-invoice-panel
        [invoiceId]="id"
        [reload]="reloadPanel()"
        (changed)="totalChanged($event)"
        (split)="splitRecorded($event)"
        (duplicated)="duplicated($event)"
        (credited)="duplicated($event)"
      />
    } @else if (invoices().length > 0) {
      <app-payment-form
        [clientId]="clientId()"
        [currency]="currency()"
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
  // The currency the client is billed in, which its invoices' amounts are in.
  readonly currency = input<Currency>('USD');
  // An invoice to open, looked up by number; a new object for each lookup, so the same invoice
  // can be opened again.
  readonly openInvoice = input<{ id: number; clientId: number } | null>(null);
  // Bumped when the client is picked again, to close the open invoice so a payment is spread
  // automatically.
  readonly picked = input(0);

  // An invoice of the client was changed or paid, so what the client owes may have changed.
  readonly changed = output<void>();

  protected readonly invoices = signal<InvoiceSummary[]>([]);
  protected readonly selectedId = signal<number | null>(null);
  protected readonly creating = signal(false);
  protected readonly sendingAll = signal(false);
  protected readonly applying = signal(false);
  protected readonly applyError = signal('');
  // Bumped to make the invoice panel load its invoice again.
  protected readonly reloadPanel = signal(0);
  protected readonly statusLabel = statusLabel;
  protected readonly money = money;

  constructor() {
    effect((onCleanup) => {
      const id = this.clientId();
      this.invoices.set([]);
      this.applyError.set('');
      const open = untracked(this.openInvoice);
      this.selectedId.set(open?.clientId === id ? open.id : null);
      const sub = this.service.forClient(id).subscribe((list) => this.invoices.set(list));
      onCleanup(() => sub.unsubscribe());
    });
    let lastPicked: number | undefined;
    effect(() => {
      const picked = this.picked();
      if (lastPicked !== undefined && picked !== lastPicked) {
        this.selectedId.set(null);
      }
      lastPicked = picked;
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

  // Applies the client's unused credit to what they owe: show the invoices' new statuses, and load
  // the open invoice again so the panel shows its payments.
  protected applyCredit(): void {
    this.applying.set(true);
    this.applyError.set('');
    this.service.applyCredit(this.clientId()).subscribe({
      next: (list) => {
        this.invoices.set(list);
        this.reloadPanel.update((n) => n + 1);
        this.applying.set(false);
        this.changed.emit();
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.applyError.set(typeof detail === 'string' && detail ? detail : 'The credit could not be applied.');
        this.applying.set(false);
      },
    });
  }

  // An invoice was duplicated or credited: list the new draft (or show the draft credit note's new
  // total) and open it.
  protected duplicated(detail: InvoiceDetail): void {
    const { id, number, status, listedTotal, issueDate, listedDueDate, creditOf } = detail;
    const row = { id, number, status, total: listedTotal, issueDate, dueDate: listedDueDate, creditOf };
    this.invoices.update((list) =>
      list.some((i) => i.id === id) ? list.map((i) => (i.id === id ? { ...i, ...row } : i)) : [...list, row],
    );
    this.selectedId.set(id);
  }

  // A rate on the client's card changed: show the drafts' new totals, and load the open invoice again
  // so the panel shows its repriced lines.
  protected rateSaved(list: InvoiceSummary[]): void {
    this.invoices.set(list);
    this.reloadPanel.update((n) => n + 1);
    this.changed.emit();
  }

  // A payment was spread over several invoices: show their new statuses.
  protected splitRecorded(list: InvoiceSummary[]): void {
    this.invoices.set(list);
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
