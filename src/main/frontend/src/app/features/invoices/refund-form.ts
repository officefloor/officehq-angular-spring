import { Component, inject, input, output, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { InvoiceService, InvoiceSummary } from './invoice.service';
import { Currency, TWO_DECIMALS, symbolOf } from './invoice-format';

// Refunds the client's unused credit: an amount more than zero with at most two decimals, and the
// date it was refunded. It comes out of the oldest payment's credit first; the server refuses a
// date after today or before a payment it would come from, or more than the client's credit.
@Component({
  selector: 'app-refund-form',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="refund()" aria-label="Refund unused credit" novalidate>
      <label for="refund-amount">Refund ({{ symbolOf(currency()) }})</label>
      <input
        id="refund-amount"
        data-testid="refund-form-amount"
        inputmode="decimal"
        formControlName="amount"
        required
      />
      <label for="refund-date">Refunded on</label>
      <input id="refund-date" type="date" data-testid="refund-form-date" formControlName="date" required />
      <button type="submit" data-testid="refund-form-submit" [disabled]="saving()">Refund credit</button>
      @if (error()) {
        <p role="alert" data-testid="refund-form-error">{{ error() }}</p>
      }
    </form>
  `,
})
export class RefundForm {
  private readonly service = inject(InvoiceService);

  readonly clientId = input.required<number>();
  // The currency the client is billed in, which the refund is in.
  readonly currency = input<Currency>('USD');
  protected readonly symbolOf = symbolOf;
  // The client's invoices after a refund.
  readonly refunded = output<InvoiceSummary[]>();

  protected readonly saving = signal(false);
  protected readonly error = signal('');

  protected readonly form = new FormGroup({
    amount: new FormControl('', { nonNullable: true }),
    date: new FormControl('', { nonNullable: true }),
  });

  protected refund(): void {
    const raw = this.form.getRawValue();
    const amount = raw.amount.trim().replace(/^\$/, '');
    const date = raw.date.trim();
    const problems: string[] = [];
    if (!amount) {
      problems.push('Enter an amount.');
    } else if (!TWO_DECIMALS.test(amount)) {
      problems.push('The amount must be a number with at most two decimals.');
    } else if (Number(amount) <= 0) {
      problems.push('The amount must be more than zero.');
    }
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      problems.push('Enter the date it was refunded.');
    }
    if (problems.length > 0) {
      this.form.markAllAsTouched();
      this.error.set(problems.join(' '));
      return;
    }
    this.saving.set(true);
    this.error.set('');
    this.service.refundCredit(this.clientId(), { amount, date }).subscribe({
      next: (list) => {
        this.form.reset();
        this.saving.set(false);
        this.refunded.emit(list);
      },
      error: (e: HttpErrorResponse) => {
        const detail: unknown = e.error?.detail;
        this.error.set(typeof detail === 'string' && detail ? detail : 'The refund could not be made.');
        this.saving.set(false);
      },
    });
  }
}
