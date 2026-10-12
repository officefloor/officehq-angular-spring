import { Component, effect, inject, input, output, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { HttpErrorResponse } from '@angular/common/http';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import {
  InvoiceDetail,
  InvoiceService,
  InvoiceSummary,
  NewPayment,
  PaymentMethod,
} from './invoice.service';
import { CURRENCIES, Currency, TWO_DECIMALS, cents, symbolOf } from './invoice-format';

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

// Whether an invoice number quoted instead of a reference (INV-, six digits, then a check character
// over them) passes its check. Numbers given before 2026-07-01 carry a Luhn check digit, later ones an
// ISO 7064 MOD 11-2 check character (0-9 or X); the server says whether it is the number of an invoice.
// Spaces and capitals are ignored by the caller.
function invoiceNumberChecks(typed: string): boolean {
  const m = /^INV-(\d{6})([0-9X])$/.exec(typed);
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
  let p = 0;
  for (const c of m[1]) {
    p = ((p + Number(c)) * 2) % 11;
  }
  const mod112 = (12 - p) % 11;
  return m[2] === String((10 - (sum % 10)) % 10) || m[2] === (mod112 === 10 ? 'X' : String(mod112));
}

// Whether a typed reference (an RF reference or an invoice number) passes its check.
function quotedReferenceChecks(reference: string): boolean {
  return reference.startsWith('INV') ? invoiceNumberChecks(reference) : referenceChecks(reference);
}

// What is wrong with a payment, or '' when it can be recorded. With `several` the reference box may
// hold several references separated by commas, each checked on its own, and each may state the amount
// it gets after an equals sign (INV-0000018=50): a number more than zero with at most two decimals.
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
  const mistyped = quoted.filter((r) => !quotedReferenceChecks(several ? r.split('=')[0] : r));
  if (mistyped.length > 0) {
    problems.push(
      quoted.length > 1
        ? `Mistyped payment reference: ${mistyped.join(', ')}. Check it against the invoice.`
        : 'The payment reference is mistyped: check it against the invoice.',
    );
  }
  const stated = several ? quoted.filter((r) => r.includes('=')) : [];
  for (const r of stated) {
    const amount = r.slice(r.indexOf('=') + 1).replace(/^\$/, '');
    if (!TWO_DECIMALS.test(amount)) {
      problems.push(`The amount for ${r.split('=')[0]} must be a number with at most two decimals.`);
    } else if (Number(amount) <= 0) {
      problems.push(`The amount for ${r.split('=')[0]} must be more than zero.`);
    }
  }
  return problems.join(' ');
}

// What is wrong with taking a payment in cash, or '' when nothing is: cash is paid against the open
// invoice, in the client's own currency, counted out in coins of 0.05 (whole yen for yen). The server
// applies the same rules.
function problemWithCash(payment: NewPayment, invoiceOpen: boolean, clientCurrency: Currency): string {
  if (!invoiceOpen) {
    return 'Open the invoice the cash is paid against.';
  }
  if (payment.currency !== clientCurrency) {
    return `Cash is paid only in the client's own currency (${clientCurrency}).`;
  }
  const step = clientCurrency === 'JPY' ? 100 : 5;
  if (TWO_DECIMALS.test(payment.amount) && cents(payment.amount) % step !== 0) {
    return clientCurrency === 'JPY'
      ? 'Cash is counted out in whole yen.'
      : 'Cash must be counted out in coins of 0.05.';
  }
  return '';
}

// Records a payment. It goes to the open invoice (`invoiceId`), or, when none is open, to the client's
// invoices whose references are quoted (several may be, separated by commas: RF references or invoice
// numbers, applied overdue first, then smallest due first; one may state the amount it gets after an
// equals sign, INV-0000018=50, and only the rest goes to those without one), or without one to the client's unpaid
// invoices in that order. What is paid beyond
// what is due is held on the client's account as credit. The server refuses a payment on a draft, or dated after today
// or before the issue date; a quoted reference must pass the ISO 11649 check and be the invoice's. The
// invoice number may be quoted instead: it must pass its check and stands for that invoice's reference.
// Ticking Deposit instead holds the whole amount on the client's account as credit, applied to no
// invoice even when one is open; the client's credit is used when one of its invoices is sent. A
// deposit quoting one of the client's drafts is held for that draft until it is sent.
@Component({
  selector: 'app-payment-form',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="record()" aria-label="Record a payment" novalidate>
      <label for="payment-currency">Paid in</label>
      <select id="payment-currency" data-testid="payment-form-currency" formControlName="currency">
        @for (c of currencies; track c) {
          <option [value]="c">{{ c }}</option>
        }
      </select>
      <label for="payment-method">Paid by</label>
      <select id="payment-method" data-testid="payment-form-method" formControlName="method">
        <option value="BANK">Bank transfer</option>
        <option value="CASH">Cash (rounded down to 0.05)</option>
        <option value="CARD">Card (the card surcharge, up to its cap, is taken out of it)</option>
      </select>
      <label for="payment-amount">Amount ({{ symbolOf(paidIn()) }})</label>
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
          References or invoice numbers quoted, separated by commas, each optionally with =amount (optional)
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
      <label for="payment-remittance">Remittance text (optional)</label>
      <input id="payment-remittance" data-testid="payment-form-remittance" formControlName="remittance" />
      <input
        id="payment-deposit"
        type="checkbox"
        data-testid="payment-form-deposit"
        formControlName="deposit"
      />
      <label for="payment-deposit"
        >Deposit (hold as credit on the client's account; quote a draft's reference to hold it for that invoice)</label
      >
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
  // The currency the client is billed in: the payment's by default. A payment in another currency is
  // converted into it by the server at the workspace rates in force on the payment date (whole yen
  // for yen; the server checks).
  readonly currency = input<Currency>('USD');
  protected readonly symbolOf = symbolOf;
  protected readonly currencies = CURRENCIES;
  // The open invoice, which takes the payment; null when none is open.
  readonly invoiceId = input<number | null>(null);
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
    remittance: new FormControl('', { nonNullable: true }),
    deposit: new FormControl(false, { nonNullable: true }),
    currency: new FormControl<Currency>('USD', { nonNullable: true }),
    method: new FormControl<PaymentMethod>('BANK', { nonNullable: true }),
  });
  // The currency picked to pay in, as a signal for the amount's label.
  protected readonly paidIn = toSignal(this.form.controls.currency.valueChanges, {
    initialValue: this.form.controls.currency.value,
  });

  constructor() {
    // Each new client (or invoice) starts with its own currency picked.
    effect(() => this.resetCurrency(this.currency()));
  }

  private resetCurrency(currency: Currency): void {
    this.form.controls.currency.setValue(currency);
  }

  protected record(): void {
    const raw = this.form.getRawValue();
    const payment: NewPayment = {
      amount: raw.amount.trim().replace(/^\$/, ''),
      currency: raw.currency,
      date: raw.date.trim(),
      reference: raw.reference.replace(/\s/g, '').toUpperCase(),
      method: raw.method,
      remittance: raw.remittance.trim(),
    };
    if (raw.deposit) {
      this.recordDeposit(payment);
      return;
    }
    const invoiceId = this.invoiceId();
    const problem =
      problemWithPayment(payment, invoiceId === null) ||
      (payment.method === 'CASH' ? problemWithCash(payment, invoiceId !== null, this.currency()) : '');
    if (problem) {
      this.form.markAllAsTouched();
      this.error.set(problem);
      return;
    }
    this.saving.set(true);
    this.error.set('');
    const done = () => {
      this.form.reset({ currency: this.currency() });
      this.saving.set(false);
    };
    const failed = (e: HttpErrorResponse) => {
      const detail: unknown = e.error?.detail;
      this.error.set(
        typeof detail === 'string' && detail ? detail : 'The payment could not be recorded.',
      );
      this.saving.set(false);
    };
    if (invoiceId === null) {
      this.service
        .recordSplitPayment(this.clientId(), payment)
        .subscribe({
          next: (list) => {
            done();
            this.split.emit(list);
          },
          error: failed,
        });
    } else {
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

  // Records the whole payment as a deposit, credit on the client's account. With a reference (an RF
  // reference or invoice number of one of the client's drafts; the server refuses any other) it is held
  // for that invoice: used first when it is sent, and by nothing else until then.
  private recordDeposit(payment: NewPayment): void {
    const problem =
      problemWithPayment(payment, false) ||
      (payment.method === 'CASH' ? 'Cash is taken only against the open invoice, not held as credit.' : '');
    if (problem) {
      this.form.markAllAsTouched();
      this.error.set(problem);
      return;
    }
    this.saving.set(true);
    this.error.set('');
    this.service
      .recordDeposit(this.clientId(), {
        amount: payment.amount,
        currency: payment.currency,
        date: payment.date,
        reference: payment.reference || undefined,
        method: payment.method,
      })
      .subscribe({
        next: (list) => {
          this.form.reset({ currency: this.currency() });
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
