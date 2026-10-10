import { HttpErrorResponse } from '@angular/common/http';
import { MoneyPipe } from '../currencies/money.pipe';
import { Component, computed, inject, input, output, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { PaymentService } from './payment.service';
import { CurrencyCode } from '../clients/client.service';
import { CurrencyService } from '../currencies/currency.service';

const TWO_DECIMALS = /^\d+(\.\d{1,2})?$/;

// The payments a client has made against an invoice, what is still owed, and a form to record
// another payment while the invoice is sent and not yet fully paid.
@Component({
  selector: 'app-invoice-payments',
  imports: [MoneyPipe, ReactiveFormsModule],
  template: `
    <section aria-labelledby="invoice-payments-heading" data-testid="invoice-payments">
      <h2 id="invoice-payments-heading">Payments</h2>
      @if (payments.error()) {
        <p role="alert" data-testid="invoice-payments-error">Could not load the payments.</p>
      } @else if (payments.hasValue()) {
        @if (payments.value().length === 0) {
          <p data-testid="invoice-payments-empty">No payments recorded yet.</p>
        }
        <table data-testid="invoice-payments-table">
          <caption>What the client has paid on this invoice</caption>
          <thead>
            <tr>
              <th scope="col">Date</th>
              <th scope="col">Amount</th>
              <th scope="col">Balance after</th>
            </tr>
          </thead>
          <tbody>
            @for (row of rows(); track row.payment.id) {
              <tr [attr.data-testid]="'payment-row-' + row.payment.id">
                <td data-testid="payment-date">{{ row.payment.date }}</td>
                <td data-testid="payment-amount">
                  {{ row.payment.amount | money: currency() }}
                  @if (row.payment.paidCurrency && row.payment.paidAmount != null) {
                    <span data-testid="payment-paid-in">
                      (paid {{ row.payment.paidAmount | money: row.payment.paidCurrency }})
                    </span>
                  }
                </td>
                <td data-testid="payment-running-balance">{{ row.balanceCents / 100 | money: currency() }}</td>
              </tr>
            }
          </tbody>
          <tfoot>
            <tr>
              <th scope="row" colspan="2">Paid</th>
              <td data-testid="invoice-paid-total">{{ paidCents() / 100 | money: currency() }}</td>
            </tr>
            <tr>
              <th scope="row" colspan="2">Balance due</th>
              <td data-testid="invoice-balance-due">{{ balanceCents() / 100 | money: currency() }}</td>
            </tr>
          </tfoot>
        </table>
      }

      @if (canRecord()) {
        <form [formGroup]="form" (ngSubmit)="submit()" data-testid="payment-form" novalidate>
          <h3>Record a payment</h3>
          <div>
            <label for="payment-amount">Amount</label>
            <input
              id="payment-amount"
              type="number"
              inputmode="decimal"
              min="0.01"
              step="0.01"
              formControlName="amount"
              data-testid="payment-form-amount"
              [attr.aria-invalid]="invalid('amount')"
              [attr.aria-describedby]="invalid('amount') ? 'payment-amount-error' : null"
            />
            @if (invalid('amount')) {
              <p id="payment-amount-error" role="alert" data-testid="payment-form-amount-error">
                Enter an amount greater than zero with at most two decimal places.
              </p>
            }
          </div>
          <div>
            <label for="payment-currency">Currency</label>
            <select id="payment-currency" formControlName="currency" data-testid="payment-form-currency">
              <option value="">{{ currency() }} (invoice currency)</option>
              @for (c of otherCurrencies(); track c.code) {
                <option [value]="c.code">{{ c.code }}</option>
              }
            </select>
            @if (form.controls.currency.value) {
              <p data-testid="payment-form-currency-hint">
                Converted into {{ currency() }} at the exchange rate on the date paid.
              </p>
            }
          </div>
          <div>
            <label for="payment-date">Date paid</label>
            <input
              id="payment-date"
              type="date"
              formControlName="date"
              data-testid="payment-form-date"
              [attr.aria-invalid]="invalid('date')"
              [attr.aria-describedby]="invalid('date') ? 'payment-date-error' : null"
            />
            @if (invalid('date')) {
              <p id="payment-date-error" role="alert" data-testid="payment-form-date-error">
                Enter the date the payment was made.
              </p>
            }
          </div>
          <button type="submit" data-testid="payment-form-submit" [disabled]="saving()">Record payment</button>
          @if (saveError()) {
            <p role="alert" data-testid="payment-form-error">{{ saveError() }}</p>
          }
        </form>
      }
    </section>
  `,
})
export class InvoicePayments {
  private readonly service = inject(PaymentService);
  private readonly currencies = inject(CurrencyService);

  readonly projectId = input.required<number>();
  readonly invoiceId = input.required<number>();
  /** The invoice total, which the payments count down. */
  readonly invoiceAmount = input.required<number>();
  /** The total of the credit notes raised against the invoice, which also count it down. */
  readonly credited = input(0);
  /** The currency of the client the job is for; the money shown is in it. */
  readonly currency = input.required<CurrencyCode>();
  /** Payments can be recorded once the invoice has been sent, until it is fully paid. */
  readonly canRecord = input.required<boolean>();
  /** Emits once a payment has been recorded, as it may change the invoice's status. */
  readonly recorded = output<void>();

  protected readonly payments = rxResource({
    params: () => ({ projectId: this.projectId(), invoiceId: this.invoiceId() }),
    stream: ({ params }) => this.service.list(params.projectId, params.invoiceId),
  });

  // Work in whole cents so the totals are exact.
  protected readonly paidCents = computed(() =>
    (this.payments.hasValue() ? this.payments.value() : []).reduce((sum, p) => sum + Math.round(p.amount * 100), 0),
  );
  protected readonly balanceCents = computed(() => this.openingCents() - this.paidCents());

  /** What is owed before any payment: the invoice total less what has been credited. */
  private readonly openingCents = computed(
    () => Math.round(this.invoiceAmount() * 100) - Math.round(this.credited() * 100),
  );

  /** The payments in date order, each with the balance still due once it was paid. */
  protected readonly rows = computed(() => {
    const sorted = [...(this.payments.hasValue() ? this.payments.value() : [])].sort(
      (a, b) => a.date.localeCompare(b.date) || a.id - b.id,
    );
    let balanceCents = this.openingCents();
    return sorted.map((payment) => {
      balanceCents -= Math.round(payment.amount * 100);
      return { payment, balanceCents };
    });
  });

  /** The currencies a payment can be received in besides the invoice's own. */
  protected readonly otherCurrencies = computed(() => this.currencies.list().filter((c) => c.code !== this.currency()));

  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    amount: ['', [Validators.required, Validators.min(0.01), Validators.pattern(TWO_DECIMALS)]],
    date: ['', [Validators.required]],
    /** Empty when paid in the invoice's currency. */
    currency: [''],
  });

  protected invalid(name: 'amount' | 'date'): boolean {
    const control = this.form.controls[name];
    return control.invalid && (control.touched || control.dirty);
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    const { amount, date, currency } = this.form.getRawValue();
    const payment = { amount: Number(amount), date, ...(currency ? { currency } : {}) };
    this.service.record(this.projectId(), this.invoiceId(), payment).subscribe({
      next: (created) => {
        this.payments.update((list) =>
          [...(list ?? []), created].sort((a, b) => a.date.localeCompare(b.date) || a.id - b.id),
        );
        this.form.reset();
        this.saving.set(false);
        this.recorded.emit();
      },
      error: (err: HttpErrorResponse) => {
        this.saveError.set(
          err.status === 409
            ? 'That payment is more than the balance due.'
            : err.status === 400 && this.form.controls.currency.value
              ? 'Could not convert the payment: there is no exchange rate for that currency on that date.'
              : 'Could not record the payment. Please try again.',
        );
        this.saving.set(false);
      },
    });
  }
}
