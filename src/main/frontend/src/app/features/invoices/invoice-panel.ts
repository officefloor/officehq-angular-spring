import { Component, effect, inject, input, output, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import {
  InvoiceDetail,
  InvoiceLineView,
  InvoiceService,
  InvoiceSummary,
  NewAllocation,
  NewDiscount,
  NewLine,
} from './invoice.service';
import { TWO_DECIMALS, money, statusLabel } from './invoice-format';
import { PaymentForm } from './payment-form';

// A percentage from 0 to 100 with at most two decimals, e.g. 15 or 12.5; the server checks it again.
function problemWithDiscount(pct: string): string {
  if (!/^-?\d+(\.\d+)?$/.test(pct)) {
    return 'The discount must be a number, like 15 or 12.5.';
  }
  if (Number(pct) < 0 || Number(pct) > 100) {
    return 'The discount must be from 0 to 100 percent.';
  }
  if (!TWO_DECIMALS.test(pct)) {
    return 'The discount can have at most two decimals.';
  }
  return '';
}

// A fixed amount of money, not negative and to the cent, e.g. 25 or 12.50; the server checks it is
// no more than the subtotal.
function problemWithDiscountAmount(amount: string): string {
  if (!/^-?\d+(\.\d+)?$/.test(amount)) {
    return 'The discount amount must be a number, like 25 or 12.50.';
  }
  if (Number(amount) < 0) {
    return 'The discount amount cannot be negative.';
  }
  if (!TWO_DECIMALS.test(amount)) {
    return 'The discount amount can have at most two decimals.';
  }
  return '';
}

// A cap on everything taken off, line discounts included: money, not negative and to the cent, e.g. 25 or 12.50.
function problemWithDiscountCap(cap: string): string {
  if (!/^-?\d+(\.\d+)?$/.test(cap)) {
    return 'The discount cap must be a number, like 25 or 12.50.';
  }
  if (Number(cap) < 0) {
    return 'The discount cap cannot be negative.';
  }
  if (!TWO_DECIMALS.test(cap)) {
    return 'The discount cap can have at most two decimals.';
  }
  return '';
}

// Either a percentage or a fixed amount, not both: the one given replaces the other. A cap is optional.
function problemWithDiscountForm(pct: string, amount: string, cap: string): string {
  if (cap && problemWithDiscountCap(cap)) {
    return problemWithDiscountCap(cap);
  }
  if (!pct && !amount) {
    return 'Enter a discount percentage or a discount amount.';
  }
  if (pct && amount) {
    return 'Enter either a discount percentage or a discount amount, not both.';
  }
  return pct ? problemWithDiscount(pct) : problemWithDiscountAmount(amount);
}

// What is wrong with a line, or '' when it can be added or saved.
function problemWith(line: NewLine): string {
  const problems: string[] = [];
  if (!line.description) {
    problems.push('Enter a description.');
  }
  if (!line.qty) {
    problems.push('Enter a quantity.');
  } else if (!TWO_DECIMALS.test(line.qty)) {
    problems.push('The quantity must be a number with at most two decimals.');
  } else if (Number(line.qty) <= 0) {
    problems.push('The quantity must be more than zero.');
  }
  if (!line.unitPrice) {
    problems.push('Enter a unit price.');
  } else if (!TWO_DECIMALS.test(line.unitPrice)) {
    problems.push('The unit price must be a number with at most two decimals.');
  } else if (Number(line.unitPrice) < 0.01) {
    problems.push('The unit price must be at least $0.01.');
  }
  if (line.discountPct !== undefined) {
    if (!/^-?\d+(\.\d+)?$/.test(line.discountPct)) {
      problems.push('The line discount must be a number, like 15 or 12.5.');
    } else if (Number(line.discountPct) < 0 || Number(line.discountPct) > 100) {
      problems.push('The line discount must be from 0 to 100 percent.');
    } else if (!TWO_DECIMALS.test(line.discountPct)) {
      problems.push('The line discount can have at most two decimals.');
    }
  }
  if (line.taxRate !== undefined) {
    if (!/^-?\d+(\.\d+)?$/.test(line.taxRate)) {
      problems.push('The line tax rate must be a number, like 5 or 12.5.');
    } else if (Number(line.taxRate) < 0 || Number(line.taxRate) > 100) {
      problems.push('The line tax rate must be from 0 to 100 percent.');
    } else if (!TWO_DECIMALS.test(line.taxRate)) {
      problems.push('The line tax rate can have at most two decimals.');
    }
  }
  return problems.join(' ');
}

// One opened invoice: its status, its lines and its totals. A line may carry its own percentage
// discount, shown on the line, with its amount after it, and its own tax. A line's edit button loads it
// into the line form, which then saves the changes to that line (with the same checks, and refused when
// the subtotal would be less than a fixed discount) instead of adding one. A draft takes new lines and a
// percentage or fixed discount, can have lines removed (refused when what is left would be less than a
// fixed discount), and can be sent; a change to a sent invoice with no payments puts it back to
// draft, and once paid anything the server refuses further changes. A sent invoice takes payments
// until nothing is due, less than $1.00 left being written off; the panel lists them with what has
// been paid, what is still due and what was written off. The
// server refuses a payment on a draft, over what is due, or dated after today or before the issue date.
// The panel shows the invoice's RF payment reference; a payment may quote one, which must pass the
// ISO 11649 check and be this invoice's. When parts of a payment are filled in on the list, the
// payment is spread over those invoices instead; each payment shows how much of it went here.
// A sent invoice with no payments can be voided; the server refuses to void a draft or one with
// payments, and refuses any payment or change to a void invoice. A payment can be reversed (a bounced
// cheque): it is undone on every invoice it was applied to and never counts again. Any invoice can
// be duplicated into a new draft for the same client, which then opens.
@Component({
  selector: 'app-invoice-panel',
  imports: [ReactiveFormsModule, PaymentForm],
  template: `
    <section data-testid="invoice-panel" aria-labelledby="invoice-panel-heading">
      <h2 id="invoice-panel-heading">
        Invoice <span data-testid="invoice-panel-number">{{ invoice()?.number }}</span>
      </h2>
      @if (invoice(); as inv) {
        <p>Status: <span data-testid="invoice-panel-status">{{ statusLabel(inv.status) }}</span></p>
        @if (inv.paidDate) {
          <p>Paid on: <span data-testid="invoice-paid-date">{{ inv.paidDate }}</span></p>
        }
        <p>Payment reference: <span data-testid="invoice-reference">{{ inv.reference }}</span></p>
        <table>
          <thead>
            <tr>
              <th scope="col">Description</th>
              <th scope="col">Qty</th>
              <th scope="col">Unit price</th>
              <th scope="col">Line discount</th>
              <th scope="col">Amount</th>
              <th scope="col">Discount share</th>
              <th scope="col">Tax status</th>
              <th scope="col">Tax rate</th>
              <th scope="col">Tax</th>
              <th scope="col">Actions</th>
            </tr>
          </thead>
          <tbody>
            @for (l of inv.lines; track l.id) {
              <tr [attr.data-testid]="'line-row-' + l.id">
                <td data-testid="line-description">{{ l.description }}</td>
                <td data-testid="line-qty">{{ l.qty }}</td>
                <td data-testid="line-unit-price">{{ money(l.unitPrice) }}</td>
                <td data-testid="line-discount">{{ money(l.discount) }}</td>
                <td data-testid="line-amount">{{ money(l.amount) }}</td>
                <td data-testid="line-discount-share">{{ money(l.discountShare) }}</td>
                <td data-testid="line-tax-exempt">{{ l.taxExempt ? 'Exempt' : 'Taxable' }}</td>
                <td data-testid="line-tax-rate">{{ (l.taxRate ?? inv.taxRate) + '%' }}</td>
                <td data-testid="line-tax">{{ money(l.tax) }}</td>
                <td>
                  <button
                    type="button"
                    data-testid="line-edit"
                    [disabled]="saving()"
                    [attr.aria-label]="'Edit line ' + l.description"
                    (click)="editLine(l)"
                  >
                    Edit
                  </button>
                  <button
                    type="button"
                    data-testid="line-remove"
                    [disabled]="saving()"
                    [attr.aria-label]="'Remove line ' + l.description"
                    (click)="removeLine(inv.id, l.id)"
                  >
                    Remove
                  </button>
                </td>
              </tr>
            } @empty {
              <tr>
                <td colspan="10" data-testid="line-empty">No lines yet.</td>
              </tr>
            }
          </tbody>
          <tfoot>
            <tr>
              <th scope="row" colspan="8">Subtotal</th>
              <td data-testid="invoice-subtotal">{{ money(inv.subtotal) }}</td>
            </tr>
            <tr>
              <th scope="row" colspan="8">
                @if (+inv.discountAmount > 0) {
                  Discount (fixed@if (inv.discountCap !== null) {, max {{ money(inv.discountCap) }} in all})
                } @else {
                  Discount ({{ inv.discountPct }}%@if (inv.discountCap !== null) {, max
                    {{ money(inv.discountCap) }} in all})
                }
              </th>
              <td data-testid="invoice-discount">{{ money(inv.discount) }}</td>
            </tr>
            @if (inv.discountReason) {
              <tr>
                <th scope="row" colspan="8">Discount reason</th>
                <td data-testid="invoice-discount-reason">{{ inv.discountReason }}</td>
              </tr>
            }
            <tr>
              <th scope="row" colspan="8">Tax (lines at their own rate, otherwise {{ inv.taxRate }}%)</th>
              <td data-testid="invoice-tax">{{ money(inv.tax) }}</td>
            </tr>
            <tr>
              <th scope="row" colspan="8">Total</th>
              <td data-testid="invoice-grand-total">{{ money(inv.total) }}</td>
            </tr>
          </tfoot>
        </table>

        <table aria-label="Payments">
          <thead>
            <tr>
              <th scope="col">Paid on</th>
              <th scope="col">Amount</th>
              <th scope="col">To this invoice</th>
              <th scope="col">Reference</th>
              <th scope="col">Actions</th>
            </tr>
          </thead>
          <tbody>
            @for (p of inv.payments; track p.id) {
              <tr [attr.data-testid]="'payment-row-' + p.id">
                <td data-testid="payment-date">{{ p.date }}</td>
                <td data-testid="payment-amount">{{ money(p.amount) }}</td>
                <td data-testid="payment-allocated">{{ money(p.allocated) }}</td>
                <td data-testid="payment-reference">{{ p.reference ?? '' }}</td>
                <td>
                  <button
                    type="button"
                    data-testid="payment-reverse"
                    [disabled]="reversing()"
                    [attr.aria-label]="'Reverse payment of ' + money(p.amount) + ' on ' + p.date"
                    (click)="reversePayment(inv.id, inv.clientId, p.id)"
                  >
                    Reverse
                  </button>
                </td>
              </tr>
            } @empty {
              <tr>
                <td colspan="5" data-testid="payment-empty">No payments yet.</td>
              </tr>
            }
          </tbody>
          <tfoot>
            <tr>
              <th scope="row" colspan="4">Paid</th>
              <td data-testid="invoice-paid">{{ money(inv.paid) }}</td>
            </tr>
            <tr>
              <th scope="row" colspan="4">Amount due</th>
              <td data-testid="invoice-amount-due">{{ money(inv.amountDue) }}</td>
            </tr>
            <tr>
              <th scope="row" colspan="4">Written off (under $1.00 left)</th>
              <td data-testid="invoice-written-off">{{ money(inv.writtenOff) }}</td>
            </tr>
          </tfoot>
        </table>
        @if (reverseError()) {
          <p role="alert" data-testid="payment-reverse-error">{{ reverseError() }}</p>
        }

        @if (inv.status !== 'PAID' && inv.status !== 'PAID_LATE') {
          <app-payment-form
            [clientId]="inv.clientId"
            [invoiceId]="inv.id"
            [allocations]="allocations()"
            (paid)="paymentRecorded($event)"
            (split)="splitRecorded(inv.id, $event)"
          />
        }

        @if (inv.status === 'DRAFT') {
          <button type="button" data-testid="invoice-send" [disabled]="sending()" (click)="send(inv.id)">
            Send invoice
          </button>
          @if (sendError()) {
            <p role="alert" data-testid="invoice-send-error">{{ sendError() }}</p>
          }
        }

        <button
          type="button"
          data-testid="invoice-duplicate"
          [disabled]="duplicating()"
          (click)="duplicate(inv.id)"
        >
          Duplicate invoice
        </button>
        @if (duplicateError()) {
          <p role="alert" data-testid="invoice-duplicate-error">{{ duplicateError() }}</p>
        }

        @if (inv.status !== 'VOID') {
          <button type="button" data-testid="invoice-void" [disabled]="voiding()" (click)="voidInvoice(inv.id)">
            Void invoice
          </button>
        }
        @if (voidError()) {
          <p role="alert" data-testid="invoice-void-error">{{ voidError() }}</p>
        }

        <form [formGroup]="form" (ngSubmit)="saveLine(inv.id)" [attr.aria-label]="editingLineId() === null ? 'Add a line' : 'Edit a line'" novalidate>
          <label for="line-description">Description</label>
          <input
            id="line-description"
            data-testid="line-form-description"
            formControlName="description"
            required
          />
          <label for="line-qty">Quantity</label>
          <input
            id="line-qty"
            data-testid="line-form-qty"
            inputmode="decimal"
            formControlName="qty"
            required
          />
          <label for="line-unit-price">Unit price ($)</label>
          <input
            id="line-unit-price"
            data-testid="line-form-unit-price"
            inputmode="decimal"
            formControlName="unitPrice"
            required
          />
          <label for="line-discount-pct">Line discount (%, optional)</label>
          <input
            id="line-discount-pct"
            data-testid="line-form-discount-pct"
            inputmode="decimal"
            formControlName="discountPct"
          />
          <label for="line-tax-rate">Tax rate (%, optional)</label>
          <input
            id="line-tax-rate"
            data-testid="line-form-tax-rate"
            inputmode="decimal"
            formControlName="taxRate"
          />
          <input
            id="line-exempt"
            type="checkbox"
            data-testid="line-form-exempt"
            formControlName="taxExempt"
          />
          <label for="line-exempt">Tax exempt</label>
          <button type="submit" data-testid="line-form-submit" [disabled]="saving()">
            {{ editingLineId() === null ? 'Add line' : 'Save line' }}
          </button>
          @if (editingLineId() !== null) {
            <button type="button" (click)="cancelEdit()">Cancel</button>
          }
          @if (error()) {
            <p role="alert" data-testid="line-form-error">{{ error() }}</p>
          }
        </form>

        <form
          [formGroup]="discountForm"
          (ngSubmit)="setDiscount(inv.id)"
          aria-label="Discount"
          novalidate
        >
          <label for="discount-pct">Discount (%)</label>
          <input
            id="discount-pct"
            data-testid="discount-form-pct"
            inputmode="decimal"
            formControlName="pct"
          />
          <label for="discount-amount">or amount ($)</label>
          <input
            id="discount-amount"
            data-testid="discount-form-amount"
            inputmode="decimal"
            formControlName="amount"
          />
          <label for="discount-cap">capped at ($, optional)</label>
          <input
            id="discount-cap"
            data-testid="discount-form-cap"
            inputmode="decimal"
            formControlName="cap"
          />
          <label for="discount-reason">reason (needed over 20% of the subtotal)</label>
          <input
            id="discount-reason"
            data-testid="discount-form-reason"
            maxlength="500"
            formControlName="reason"
          />
          <button type="submit" data-testid="discount-form-submit" [disabled]="savingDiscount()">
            Apply discount
          </button>
          @if (discountError()) {
            <p role="alert" data-testid="discount-form-error">{{ discountError() }}</p>
          }
        </form>
      }
    </section>
  `,
})
export class InvoicePanel {
  private readonly service = inject(InvoiceService);

  readonly invoiceId = input.required<number>();
  // Bumped when the invoice was changed elsewhere (sent with the client's other drafts), to load it again.
  readonly reload = input(0);
  // The invoice after a change made here, so the list can show its new total.
  readonly changed = output<InvoiceDetail>();
  // The parts of a payment filled in on the list; when any is, a payment is spread over them.
  readonly allocations = input<NewAllocation[]>([]);
  // The client's invoices after a payment spread over several of them.
  readonly split = output<InvoiceSummary[]>();
  // A new draft copied from this invoice, to be listed and opened.
  readonly duplicated = output<InvoiceDetail>();

  protected readonly invoice = signal<InvoiceDetail | null>(null);
  protected readonly saving = signal(false);
  protected readonly error = signal('');
  // The line loaded into the line form to be changed, or null when the form adds a new line.
  protected readonly editingLineId = signal<number | null>(null);
  protected readonly savingDiscount = signal(false);
  protected readonly discountError = signal('');
  protected readonly sending = signal(false);
  protected readonly sendError = signal('');
  protected readonly duplicating = signal(false);
  protected readonly duplicateError = signal('');
  protected readonly voiding = signal(false);
  protected readonly voidError = signal('');
  protected readonly reversing = signal(false);
  protected readonly reverseError = signal('');
  protected readonly statusLabel = statusLabel;
  protected readonly money = money;

  protected readonly form = new FormGroup({
    description: new FormControl('', { nonNullable: true }),
    qty: new FormControl('', { nonNullable: true }),
    unitPrice: new FormControl('', { nonNullable: true }),
    taxExempt: new FormControl(false, { nonNullable: true }),
    discountPct: new FormControl('', { nonNullable: true }),
    taxRate: new FormControl('', { nonNullable: true }),
  });

  protected readonly discountForm = new FormGroup({
    pct: new FormControl('', { nonNullable: true }),
    amount: new FormControl('', { nonNullable: true }),
    cap: new FormControl('', { nonNullable: true }),
    reason: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    effect((onCleanup) => {
      const id = this.invoiceId();
      this.reload();
      this.invoice.set(null);
      this.form.reset();
      this.editingLineId.set(null);
      this.error.set('');
      this.discountForm.reset();
      this.discountError.set('');
      this.sendError.set('');
      this.voidError.set('');
      this.duplicateError.set('');
      this.reverseError.set('');
      const sub = this.service.open(id).subscribe((detail) => this.invoice.set(detail));
      onCleanup(() => sub.unsubscribe());
    });
  }

  // Loads a line into the line form, so submitting it changes that line instead of adding one.
  protected editLine(line: InvoiceLineView): void {
    this.form.setValue({
      description: line.description,
      qty: line.qty,
      unitPrice: line.unitPrice,
      taxExempt: line.taxExempt,
      discountPct: Number(line.discountPct) === 0 ? '' : line.discountPct,
      taxRate: line.taxRate ?? '',
    });
    this.editingLineId.set(line.id);
    this.error.set('');
  }

  protected cancelEdit(): void {
    this.form.reset();
    this.editingLineId.set(null);
    this.error.set('');
  }

  protected saveLine(invoiceId: number): void {
    const raw = this.form.getRawValue();
    const line: NewLine = {
      description: raw.description.trim(),
      qty: raw.qty.trim(),
      unitPrice: raw.unitPrice.trim().replace(/^\$/, ''),
      taxExempt: raw.taxExempt,
    };
    const discountPct = raw.discountPct.trim().replace(/%$/, '').trim();
    if (discountPct) {
      line.discountPct = discountPct;
    }
    const taxRate = raw.taxRate.trim().replace(/%$/, '').trim();
    if (taxRate) {
      line.taxRate = taxRate;
    }
    const problem = problemWith(line);
    if (problem) {
      this.form.markAllAsTouched();
      this.error.set(problem);
      return;
    }
    this.saving.set(true);
    this.error.set('');
    const lineId = this.editingLineId();
    const request =
      lineId === null
        ? this.service.addLine(invoiceId, line)
        : this.service.updateLine(invoiceId, lineId, line);
    request.subscribe({
      next: (detail) => {
        this.invoice.set(detail);
        this.changed.emit(detail);
        this.form.reset();
        this.editingLineId.set(null);
        this.saving.set(false);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        const fallback = lineId === null ? 'The line could not be added.' : 'The line could not be changed.';
        this.error.set(typeof detail === 'string' && detail ? detail : fallback);
        this.saving.set(false);
      },
    });
  }

  protected removeLine(invoiceId: number, lineId: number): void {
    this.saving.set(true);
    this.error.set('');
    this.service.removeLine(invoiceId, lineId).subscribe({
      next: (detail) => {
        this.invoice.set(detail);
        if (this.editingLineId() === lineId) {
          this.form.reset();
          this.editingLineId.set(null);
        }
        this.changed.emit(detail);
        this.saving.set(false);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.error.set(
          typeof detail === 'string' && detail ? detail : 'The line could not be removed.',
        );
        this.saving.set(false);
      },
    });
  }

  protected setDiscount(invoiceId: number): void {
    const raw = this.discountForm.getRawValue();
    const pct = raw.pct.trim().replace(/%$/, '').trim();
    const amount = raw.amount.trim().replace(/^\$/, '').trim();
    const cap = raw.cap.trim().replace(/^\$/, '').trim();
    const problem = problemWithDiscountForm(pct, amount, cap);
    if (problem) {
      this.discountForm.markAllAsTouched();
      this.discountError.set(problem);
      return;
    }
    this.savingDiscount.set(true);
    this.discountError.set('');
    const discount: NewDiscount = pct ? { discountPct: pct } : { discountAmount: amount };
    if (cap) {
      discount.discountCap = cap;
    }
    const reason = raw.reason.trim();
    if (reason) {
      discount.discountReason = reason;
    }
    this.service.setDiscount(invoiceId, discount).subscribe({
      next: (detail) => {
        this.invoice.set(detail);
        this.changed.emit(detail);
        this.discountForm.reset();
        this.savingDiscount.set(false);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.discountError.set(
          typeof detail === 'string' && detail ? detail : 'The discount could not be applied.',
        );
        this.savingDiscount.set(false);
      },
    });
  }

  protected paymentRecorded(detail: InvoiceDetail): void {
    this.invoice.set(detail);
    this.changed.emit(detail);
  }

  // A payment was spread over several invoices: show this one again with its share.
  protected splitRecorded(invoiceId: number, list: InvoiceSummary[]): void {
    this.split.emit(list);
    this.service.open(invoiceId).subscribe((detail) => this.invoice.set(detail));
  }

  protected send(invoiceId: number): void {
    this.sending.set(true);
    this.sendError.set('');
    this.service.send(invoiceId).subscribe({
      next: (detail) => {
        this.invoice.set(detail);
        this.changed.emit(detail);
        this.sending.set(false);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.sendError.set(
          typeof detail === 'string' && detail ? detail : 'The invoice could not be sent.',
        );
        this.sending.set(false);
      },
    });
  }

  protected duplicate(invoiceId: number): void {
    this.duplicating.set(true);
    this.duplicateError.set('');
    this.service.duplicate(invoiceId).subscribe({
      next: (detail) => {
        this.duplicating.set(false);
        this.duplicated.emit(detail);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.duplicateError.set(
          typeof detail === 'string' && detail ? detail : 'The invoice could not be duplicated.',
        );
        this.duplicating.set(false);
      },
    });
  }

  protected voidInvoice(invoiceId: number): void {
    this.voiding.set(true);
    this.voidError.set('');
    this.service.void(invoiceId).subscribe({
      next: (detail) => {
        this.invoice.set(detail);
        this.changed.emit(detail);
        this.voiding.set(false);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.voidError.set(
          typeof detail === 'string' && detail ? detail : 'The invoice could not be voided.',
        );
        this.voiding.set(false);
      },
    });
  }

  // A payment was reversed: show this invoice again, and the client's other invoices it was applied to.
  protected reversePayment(invoiceId: number, clientId: number, paymentId: number): void {
    this.reversing.set(true);
    this.reverseError.set('');
    this.service.reversePayment(invoiceId, paymentId).subscribe({
      next: (detail) => {
        this.invoice.set(detail);
        this.changed.emit(detail);
        this.service.forClient(clientId).subscribe((list) => this.split.emit(list));
        this.reversing.set(false);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.reverseError.set(
          typeof detail === 'string' && detail ? detail : 'The payment could not be reversed.',
        );
        this.reversing.set(false);
      },
    });
  }
}
