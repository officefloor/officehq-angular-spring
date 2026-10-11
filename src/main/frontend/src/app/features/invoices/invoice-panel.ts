import { Component, effect, inject, input, output, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { InvoiceDetail, InvoiceService, NewLine, NewPayment } from './invoice.service';
import { money, statusLabel } from './invoice-format';

// A positive decimal with at most two decimals, e.g. 1, 1.5 or 12.35. The server applies the same rules.
const TWO_DECIMALS = /^\d+(\.\d{1,2})?$/;

// A percentage from 0 to 100 with at most two decimals, e.g. 15 or 12.5; the server checks it again.
function problemWithDiscount(pct: string): string {
  if (!pct) {
    return 'Enter a discount percentage.';
  }
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

// Whether a reference passes the ISO 11649 check: its first four characters moved to the end,
// letters as numbers (A=10 up to Z=35), mod 97 is 1. Spaces and capitals are ignored by the caller.
function referenceChecks(reference: string): boolean {
  if (!/^RF\d{2}[A-Z0-9]{1,21}$/.test(reference)) {
    return false;
  }
  const moved = reference.slice(4) + reference.slice(0, 4);
  let rest = 0;
  for (const c of moved) {
    for (const d of String(parseInt(c, 36))) {
      rest = (rest * 10 + Number(d)) % 97;
    }
  }
  return rest === 1;
}

// What is wrong with a payment, or '' when it can be recorded.
function problemWithPayment(payment: NewPayment): string {
  const problems: string[] = [];
  if (!payment.amount) {
    problems.push('Enter an amount.');
  } else if (!TWO_DECIMALS.test(payment.amount)) {
    problems.push('The amount must be a number with at most two decimals.');
  } else if (Number(payment.amount) <= 0) {
    problems.push('The amount must be more than zero.');
  }
  if (!/^\d{4}-\d{2}-\d{2}$/.test(payment.date)) {
    problems.push('Enter the date it was paid.');
  }
  if (payment.reference && !referenceChecks(payment.reference)) {
    problems.push('The payment reference is mistyped: check it against the invoice.');
  }
  return problems.join(' ');
}

// What is wrong with a line, or '' when it can be added.
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
  return problems.join(' ');
}

// One opened invoice: its status, its lines and its totals. A draft takes new lines and a
// percentage discount, and can be sent; a change to a sent invoice with no payments puts it back to
// draft, and once paid anything the server refuses further changes. A sent invoice takes payments
// until nothing is due; the panel lists them with what has been paid and what is still due. The
// server refuses a payment on a draft, over what is due, or dated after today or before the issue date.
// The panel shows the invoice's RF payment reference; a payment may quote one, which must pass the
// ISO 11649 check and be this invoice's.
@Component({
  selector: 'app-invoice-panel',
  imports: [ReactiveFormsModule],
  template: `
    <section data-testid="invoice-panel" aria-labelledby="invoice-panel-heading">
      <h2 id="invoice-panel-heading">
        Invoice <span data-testid="invoice-panel-number">{{ invoice()?.number }}</span>
      </h2>
      @if (invoice(); as inv) {
        <p>Status: <span data-testid="invoice-panel-status">{{ statusLabel(inv.status) }}</span></p>
        <p>Payment reference: <span data-testid="invoice-reference">{{ inv.reference }}</span></p>
        <table>
          <thead>
            <tr>
              <th scope="col">Description</th>
              <th scope="col">Qty</th>
              <th scope="col">Unit price</th>
              <th scope="col">Amount</th>
              <th scope="col">Discount share</th>
              <th scope="col">Tax</th>
            </tr>
          </thead>
          <tbody>
            @for (l of inv.lines; track l.id) {
              <tr [attr.data-testid]="'line-row-' + l.id">
                <td data-testid="line-description">{{ l.description }}</td>
                <td data-testid="line-qty">{{ l.qty }}</td>
                <td data-testid="line-unit-price">{{ money(l.unitPrice) }}</td>
                <td data-testid="line-amount">{{ money(l.amount) }}</td>
                <td data-testid="line-discount-share">{{ money(l.discountShare) }}</td>
                <td data-testid="line-tax-exempt">{{ l.taxExempt ? 'Exempt' : 'Taxable' }}</td>
              </tr>
            } @empty {
              <tr>
                <td colspan="6" data-testid="line-empty">No lines yet.</td>
              </tr>
            }
          </tbody>
          <tfoot>
            <tr>
              <th scope="row" colspan="5">Subtotal</th>
              <td data-testid="invoice-subtotal">{{ money(inv.subtotal) }}</td>
            </tr>
            <tr>
              <th scope="row" colspan="5">Discount ({{ inv.discountPct }}%)</th>
              <td data-testid="invoice-discount">{{ money(inv.discount) }}</td>
            </tr>
            <tr>
              <th scope="row" colspan="5">Tax ({{ inv.taxRate }}%)</th>
              <td data-testid="invoice-tax">{{ money(inv.tax) }}</td>
            </tr>
            <tr>
              <th scope="row" colspan="5">Total</th>
              <td data-testid="invoice-grand-total">{{ money(inv.total) }}</td>
            </tr>
          </tfoot>
        </table>

        @if (inv.status !== 'DRAFT') {
          <table aria-label="Payments">
            <thead>
              <tr>
                <th scope="col">Paid on</th>
                <th scope="col">Amount</th>
                <th scope="col">Reference</th>
              </tr>
            </thead>
            <tbody>
              @for (p of inv.payments; track p.id) {
                <tr [attr.data-testid]="'payment-row-' + p.id">
                  <td data-testid="payment-date">{{ p.date }}</td>
                  <td data-testid="payment-amount">{{ money(p.amount) }}</td>
                  <td data-testid="payment-reference">{{ p.reference ?? '' }}</td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="3" data-testid="payment-empty">No payments yet.</td>
                </tr>
              }
            </tbody>
            <tfoot>
              <tr>
                <th scope="row" colspan="2">Paid</th>
                <td data-testid="invoice-paid">{{ money(inv.paid) }}</td>
              </tr>
              <tr>
                <th scope="row" colspan="2">Amount due</th>
                <td data-testid="invoice-amount-due">{{ money(inv.amountDue) }}</td>
              </tr>
            </tfoot>
          </table>
        }

        @if (inv.status !== 'PAID') {
          <form
            [formGroup]="paymentForm"
            (ngSubmit)="recordPayment(inv.id)"
            aria-label="Record a payment"
            novalidate
          >
            <label for="payment-amount">Amount ($)</label>
            <input
              id="payment-amount"
              data-testid="payment-form-amount"
              inputmode="decimal"
              formControlName="amount"
              required
            />
            <label for="payment-date">Paid on</label>
            <input
              id="payment-date"
              type="date"
              data-testid="payment-form-date"
              formControlName="date"
              required
            />
            <label for="payment-reference">Reference quoted (optional)</label>
            <input
              id="payment-reference"
              data-testid="payment-form-reference"
              formControlName="reference"
              autocapitalize="characters"
              placeholder="RF16 0000 075"
            />
            <button type="submit" data-testid="payment-form-submit" [disabled]="savingPayment()">
              Record payment
            </button>
            @if (paymentError()) {
              <p role="alert" data-testid="payment-form-error">{{ paymentError() }}</p>
            }
          </form>
        }

        @if (inv.status === 'DRAFT') {
          <button type="button" data-testid="invoice-send" [disabled]="sending()" (click)="send(inv.id)">
            Send invoice
          </button>
          @if (sendError()) {
            <p role="alert" data-testid="invoice-send-error">{{ sendError() }}</p>
          }
        }

        <form [formGroup]="form" (ngSubmit)="addLine(inv.id)" aria-label="Add a line" novalidate>
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
          <input
            id="line-exempt"
            type="checkbox"
            data-testid="line-form-exempt"
            formControlName="taxExempt"
          />
          <label for="line-exempt">Tax exempt</label>
          <button type="submit" data-testid="line-form-submit" [disabled]="saving()">
            Add line
          </button>
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
            required
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
  // The invoice after a change made here, so the list can show its new total.
  readonly changed = output<InvoiceDetail>();

  protected readonly invoice = signal<InvoiceDetail | null>(null);
  protected readonly saving = signal(false);
  protected readonly error = signal('');
  protected readonly savingDiscount = signal(false);
  protected readonly discountError = signal('');
  protected readonly sending = signal(false);
  protected readonly sendError = signal('');
  protected readonly savingPayment = signal(false);
  protected readonly paymentError = signal('');
  protected readonly statusLabel = statusLabel;
  protected readonly money = money;

  protected readonly form = new FormGroup({
    description: new FormControl('', { nonNullable: true }),
    qty: new FormControl('', { nonNullable: true }),
    unitPrice: new FormControl('', { nonNullable: true }),
    taxExempt: new FormControl(false, { nonNullable: true }),
  });

  protected readonly paymentForm = new FormGroup({
    amount: new FormControl('', { nonNullable: true }),
    date: new FormControl('', { nonNullable: true }),
    reference: new FormControl('', { nonNullable: true }),
  });

  protected readonly discountForm = new FormGroup({
    pct: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    effect((onCleanup) => {
      const id = this.invoiceId();
      this.invoice.set(null);
      this.form.reset();
      this.error.set('');
      this.discountForm.reset();
      this.discountError.set('');
      this.sendError.set('');
      this.paymentForm.reset();
      this.paymentError.set('');
      const sub = this.service.open(id).subscribe((detail) => this.invoice.set(detail));
      onCleanup(() => sub.unsubscribe());
    });
  }

  protected addLine(invoiceId: number): void {
    const raw = this.form.getRawValue();
    const line: NewLine = {
      description: raw.description.trim(),
      qty: raw.qty.trim(),
      unitPrice: raw.unitPrice.trim().replace(/^\$/, ''),
      taxExempt: raw.taxExempt,
    };
    const problem = problemWith(line);
    if (problem) {
      this.form.markAllAsTouched();
      this.error.set(problem);
      return;
    }
    this.saving.set(true);
    this.error.set('');
    this.service.addLine(invoiceId, line).subscribe({
      next: (detail) => {
        this.invoice.set(detail);
        this.changed.emit(detail);
        this.form.reset();
        this.saving.set(false);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.error.set(
          typeof detail === 'string' && detail ? detail : 'The line could not be added.',
        );
        this.saving.set(false);
      },
    });
  }

  protected setDiscount(invoiceId: number): void {
    const pct = this.discountForm.getRawValue().pct.trim().replace(/%$/, '').trim();
    const problem = problemWithDiscount(pct);
    if (problem) {
      this.discountForm.markAllAsTouched();
      this.discountError.set(problem);
      return;
    }
    this.savingDiscount.set(true);
    this.discountError.set('');
    this.service.setDiscount(invoiceId, pct).subscribe({
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

  protected recordPayment(invoiceId: number): void {
    const raw = this.paymentForm.getRawValue();
    const payment: NewPayment = {
      amount: raw.amount.trim().replace(/^\$/, ''),
      date: raw.date.trim(),
      reference: raw.reference.replace(/\s/g, '').toUpperCase(),
    };
    const problem = problemWithPayment(payment);
    if (problem) {
      this.paymentForm.markAllAsTouched();
      this.paymentError.set(problem);
      return;
    }
    this.savingPayment.set(true);
    this.paymentError.set('');
    this.service.recordPayment(invoiceId, payment).subscribe({
      next: (detail) => {
        this.invoice.set(detail);
        this.changed.emit(detail);
        this.paymentForm.reset();
        this.savingPayment.set(false);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.paymentError.set(
          typeof detail === 'string' && detail ? detail : 'The payment could not be recorded.',
        );
        this.savingPayment.set(false);
      },
    });
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
}
