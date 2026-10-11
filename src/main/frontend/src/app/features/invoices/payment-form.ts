import { Component, inject, input, output, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import {
  InvoiceDetail,
  InvoiceService,
  InvoiceSummary,
  NewAllocation,
  NewPayment,
} from './invoice.service';
import { TWO_DECIMALS, cents } from './invoice-format';

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

// Whether an invoice number quoted instead of a reference (INV-, six digits, then a Luhn check
// digit over them) passes its check. Spaces and capitals are ignored by the caller.
function invoiceNumberChecks(typed: string): boolean {
  const m = /^INV-(\d{6})(\d)$/.exec(typed);
  if (!m) {
    return false;
  }
  let sum = 0;
  [...m[1]].reverse().forEach((c, i) => {
    let d = Number(c);
    if (i % 2 === 0) {
      d *= 2;
      if (d > 9) {
        d -= 9;
      }
    }
    sum += d;
  });
  return (10 - (sum % 10)) % 10 === Number(m[2]);
}

// Whether a typed reference (an RF reference or an invoice number) passes its check.
function quotedReferenceChecks(reference: string): boolean {
  return reference.startsWith('INV') ? invoiceNumberChecks(reference) : referenceChecks(reference);
}

// What is wrong with a payment, or '' when it can be recorded. With `several` the reference box may
// hold several references separated by commas, each checked on its own.
function problemWithPayment(payment: NewPayment, several: boolean): string {
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
  const quoted = several
    ? payment.reference.split(',').filter((r) => r)
    : payment.reference
      ? [payment.reference]
      : [];
  const mistyped = quoted.filter((r) => !quotedReferenceChecks(r));
  if (mistyped.length > 0) {
    problems.push(
      quoted.length > 1
        ? `Mistyped payment reference: ${mistyped.join(', ')}. Check it against the invoice.`
        : 'The payment reference is mistyped: check it against the invoice.',
    );
  }
  return problems.join(' ');
}

// What is wrong with the parts of a split payment, or '' when they fit it. Whether each part is no
// more than its invoice has due is checked by the server.
function problemWithAllocations(payment: NewPayment, allocations: NewAllocation[]): string {
  if (allocations.some((a) => !TWO_DECIMALS.test(a.amount))) {
    return "Each invoice's part must be a number with at most two decimals.";
  }
  if (allocations.some((a) => cents(a.amount) <= 0)) {
    return "Each invoice's part must be more than zero.";
  }
  const sum = allocations.reduce((total, a) => total + cents(a.amount), 0);
  if (TWO_DECIMALS.test(payment.amount) && sum !== cents(payment.amount)) {
    return `The parts add up to $${(sum / 100).toFixed(2)}, not the $${(cents(payment.amount) / 100).toFixed(2)} paid.`;
  }
  return '';
}

// Records a payment. With no parts filled in it goes to the open invoice (`invoiceId`), or, when none
// is open, to the client's invoices whose references are quoted (several may be, separated by
// commas: RF references or invoice numbers, applied earliest due date first), or without one to the client's unpaid invoices earliest due date first; once any invoice in the list has a part, the
// payment is spread over those invoices instead, and the parts must add up to it. The server refuses a payment on a draft, over what is due, or dated after today
// or before the issue date; a quoted reference must pass the ISO 11649 check and be the invoice's. The
// invoice number may be quoted instead: it must pass its Luhn check and stands for that invoice's reference.
// Ticking Deposit instead holds the whole amount on the client's account as credit, applied to no
// invoice even when one is open; the client's credit is used when one of its invoices is sent.
@Component({
  selector: 'app-payment-form',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="record()" aria-label="Record a payment" novalidate>
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
      <label for="payment-reference">
        @if (invoiceId() === null) {
          References or invoice numbers quoted, separated by commas (optional)
        } @else {
          Reference or invoice number quoted (optional)
        }
      </label>
      <input
        id="payment-reference"
        data-testid="payment-form-reference"
        formControlName="reference"
        autocapitalize="characters"
        placeholder="RF16 0000 075"
      />
      <input
        id="payment-deposit"
        type="checkbox"
        data-testid="payment-form-deposit"
        formControlName="deposit"
      />
      <label for="payment-deposit">Deposit (hold as credit on the client's account)</label>
      <button type="submit" data-testid="payment-form-submit" [disabled]="saving()">
        Record payment
      </button>
      @if (error()) {
        <p role="alert" data-testid="payment-form-error">{{ error() }}</p>
      }
    </form>
  `,
})
export class PaymentForm {
  private readonly service = inject(InvoiceService);

  readonly clientId = input.required<number>();
  // The open invoice, which takes the payment when no parts are filled in; null when none is open.
  readonly invoiceId = input<number | null>(null);
  readonly allocations = input<NewAllocation[]>([]);
  // The open invoice after a payment to it alone.
  readonly paid = output<InvoiceDetail>();
  // The client's invoices after a payment spread over several of them.
  readonly split = output<InvoiceSummary[]>();
  // The client's invoices after a deposit held as credit.
  readonly deposited = output<InvoiceSummary[]>();

  protected readonly saving = signal(false);
  protected readonly error = signal('');

  protected readonly form = new FormGroup({
    amount: new FormControl('', { nonNullable: true }),
    date: new FormControl('', { nonNullable: true }),
    reference: new FormControl('', { nonNullable: true }),
    deposit: new FormControl(false, { nonNullable: true }),
  });

  protected record(): void {
    const raw = this.form.getRawValue();
    const payment: NewPayment = {
      amount: raw.amount.trim().replace(/^\$/, ''),
      date: raw.date.trim(),
      reference: raw.reference.replace(/\s/g, '').toUpperCase(),
    };
    if (raw.deposit) {
      this.recordDeposit(payment);
      return;
    }
    const allocations = this.allocations();
    const invoiceId = this.invoiceId();
    let problem = problemWithPayment(payment, allocations.length === 0 && invoiceId === null);
    if (allocations.length > 0) {
      problem = [problem, problemWithAllocations(payment, allocations)].filter((p) => p).join(' ');
    }
    if (problem) {
      this.form.markAllAsTouched();
      this.error.set(problem);
      return;
    }
    this.saving.set(true);
    this.error.set('');
    const done = () => {
      this.form.reset();
      this.saving.set(false);
    };
    const failed = (e: HttpErrorResponse) => {
      const detail: unknown = e.error?.detail;
      this.error.set(
        typeof detail === 'string' && detail ? detail : 'The payment could not be recorded.',
      );
      this.saving.set(false);
    };
    if (allocations.length > 0 || invoiceId === null) {
      this.service
        .recordSplitPayment(this.clientId(), { ...payment, allocations })
        .subscribe({
          next: (list) => {
            done();
            this.split.emit(list);
          },
          error: failed,
        });
    } else if (invoiceId !== null) {
      this.service
        .recordPayment(invoiceId, payment)
        .subscribe({
          next: (detail) => {
            done();
            this.paid.emit(detail);
          },
          error: failed,
        });
    }
  }

  // Records the whole payment as a deposit, credit on the client's account; any reference is not kept.
  private recordDeposit(payment: NewPayment): void {
    const problem = problemWithPayment({ ...payment, reference: '' }, false);
    if (problem) {
      this.form.markAllAsTouched();
      this.error.set(problem);
      return;
    }
    this.saving.set(true);
    this.error.set('');
    this.service.recordDeposit(this.clientId(), { amount: payment.amount, date: payment.date }).subscribe({
      next: (list) => {
        this.form.reset();
        this.saving.set(false);
        this.deposited.emit(list);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.error.set(
          typeof detail === 'string' && detail ? detail : 'The deposit could not be recorded.',
        );
        this.saving.set(false);
      },
    });
  }
}
