import { CurrencyPipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, output, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { InvoiceService } from '../invoices/invoice.service';
import { PaymentService } from './payment.service';

const TWO_DECIMALS = /^\d+(\.\d{1,2})?$/;

/** Whole cents for an entered amount, or null when it is not a valid amount. */
function toCents(value: string | number): number | null {
  const trimmed = String(value).trim();
  return TWO_DECIMALS.test(trimmed) ? Math.round(Number(trimmed) * 100) : null;
}

// Records one lump payment from a client and splits it across their invoices that are still owed.
// The shares must account for the whole payment before it is recorded.
@Component({
  selector: 'app-client-payment',
  imports: [ReactiveFormsModule, CurrencyPipe],
  template: `
    <section aria-labelledby="client-payment-heading" data-testid="client-payment">
      <h2 id="client-payment-heading">Record a payment</h2>
      @if (statement.error()) {
        <p role="alert" data-testid="client-payment-load-error">Could not load the client's invoices.</p>
      } @else if (statement.hasValue()) {
        @if (owing().length === 0) {
          <p data-testid="client-payment-none-owing">This client has no invoices waiting to be paid.</p>
        } @else {
          <form [formGroup]="form" (ngSubmit)="submit()" data-testid="payment-form" novalidate>
            <div>
              <label for="client-payment-amount">Amount received</label>
              <input
                id="client-payment-amount"
                type="number"
                inputmode="decimal"
                min="0.01"
                step="0.01"
                formControlName="amount"
                data-testid="payment-form-amount"
                [attr.aria-invalid]="invalid('amount')"
                [attr.aria-describedby]="invalid('amount') ? 'client-payment-amount-error' : null"
              />
              @if (invalid('amount')) {
                <p id="client-payment-amount-error" role="alert" data-testid="payment-form-amount-error">
                  Enter an amount greater than zero with at most two decimal places.
                </p>
              }
            </div>
            <div>
              <label for="client-payment-date">Date paid</label>
              <input
                id="client-payment-date"
                type="date"
                formControlName="date"
                data-testid="payment-form-date"
                [attr.aria-invalid]="invalid('date')"
                [attr.aria-describedby]="invalid('date') ? 'client-payment-date-error' : null"
              />
              @if (invalid('date')) {
                <p id="client-payment-date-error" role="alert" data-testid="payment-form-date-error">
                  Enter the date the payment was made.
                </p>
              }
            </div>
            <table data-testid="payment-alloc-table">
              <caption>Split the payment across the invoices still owed</caption>
              <thead>
                <tr>
                  <th scope="col">Invoice</th>
                  <th scope="col">Job</th>
                  <th scope="col">Balance due</th>
                  <th scope="col">Pay</th>
                </tr>
              </thead>
              <tbody>
                @for (i of owing(); track i.id) {
                  <tr [attr.data-testid]="'payment-alloc-row-' + i.id">
                    <td>#{{ i.id }}</td>
                    <td>{{ i.projectName }}</td>
                    <td data-testid="payment-alloc-due">{{ i.amountDue | currency: 'USD' : 'symbol' : '1.2-2' : 'en-US' }}</td>
                    <td>
                      <input
                        type="number"
                        inputmode="decimal"
                        min="0"
                        step="0.01"
                        [attr.aria-label]="'Amount to pay on invoice ' + i.id"
                        [attr.data-testid]="'payment-alloc-' + i.id"
                        [value]="allocations()[i.id] ?? ''"
                        (input)="allocate(i.id, $any($event.target).value)"
                      />
                    </td>
                  </tr>
                }
              </tbody>
              <tfoot>
                <tr>
                  <th scope="row" colspan="3">Allocated</th>
                  <td data-testid="payment-alloc-total">{{ allocatedCents() / 100 | currency: 'USD' : 'symbol' : '1.2-2' : 'en-US' }}</td>
                </tr>
              </tfoot>
            </table>
            <button type="submit" data-testid="payment-form-submit" [disabled]="saving()">Record payment</button>
            @if (saveError()) {
              <p role="alert" data-testid="payment-form-error">{{ saveError() }}</p>
            }
          </form>
        }
      }
    </section>
  `,
})
export class ClientPaymentForm {
  private readonly invoices = inject(InvoiceService);
  private readonly payments = inject(PaymentService);

  readonly clientId = input.required<number>();
  /** Emits once the payment has been recorded. */
  readonly recorded = output<void>();

  protected readonly statement = rxResource({
    params: () => this.clientId(),
    stream: ({ params }) => this.invoices.statementForClient(params),
  });

  /** The client's sent invoices that still have something left to pay. */
  protected readonly owing = computed(() =>
    (this.statement.hasValue() ? this.statement.value().invoices : []).filter(
      (i) => (i.status === 'SENT' || i.status === 'PARTIAL') && i.amountDue > 0,
    ),
  );

  /** What has been typed against each invoice, keyed by invoice id. */
  protected readonly allocations = signal<Record<number, string>>({});

  protected readonly allocatedCents = computed(() =>
    Object.values(this.allocations()).reduce((sum, v) => sum + (toCents(v) ?? 0), 0),
  );

  protected readonly saving = signal(false);
  /** Settles once the payment being saved has been recorded or refused; null when nothing is being saved. */
  private inFlight: Promise<void> | null = null;
  protected readonly saveError = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    amount: ['', [Validators.required, Validators.min(0.01), Validators.pattern(TWO_DECIMALS)]],
    date: ['', [Validators.required]],
  });

  protected invalid(name: 'amount' | 'date'): boolean {
    const control = this.form.controls[name];
    return control.invalid && (control.touched || control.dirty);
  }

  protected allocate(invoiceId: number, value: string): void {
    this.allocations.update((a) => ({ ...a, [invoiceId]: value }));
  }

  protected submit(): void {
    this.saveError.set(null);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const shares: { invoiceId: number; amount: number }[] = [];
    for (const invoice of this.owing()) {
      const entered = (this.allocations()[invoice.id] ?? '').trim();
      if (entered === '') {
        continue;
      }
      const cents = toCents(entered);
      if (cents === null) {
        this.saveError.set(`Enter the amount for invoice #${invoice.id} with at most two decimal places.`);
        return;
      }
      if (cents > Math.round(invoice.amountDue * 100)) {
        this.saveError.set(`The amount for invoice #${invoice.id} is more than its balance due.`);
        return;
      }
      if (cents > 0) {
        shares.push({ invoiceId: invoice.id, amount: cents / 100 });
      }
    }
    const { amount, date } = this.form.getRawValue();
    const totalCents = toCents(amount) ?? 0;
    if (shares.length === 0) {
      this.saveError.set('Allocate the payment to at least one invoice.');
      return;
    }
    if (this.allocatedCents() !== totalCents) {
      this.saveError.set(
        `The amounts allocated add up to ${this.format(this.allocatedCents())}, ` +
          `but the payment is ${this.format(totalCents)}. Allocate the whole payment.`,
      );
      return;
    }
    this.saving.set(true);
    let settle!: () => void;
    this.inFlight = new Promise((resolve) => (settle = resolve));
    const done = () => {
      this.inFlight = null;
      settle();
    };
    this.payments.recordForClient(this.clientId(), { amount: totalCents / 100, date, allocations: shares }).subscribe({
      next: () => {
        this.saving.set(false);
        done();
        this.form.reset();
        this.allocations.set({});
        this.statement.reload();
        this.recorded.emit();
      },
      error: (err: HttpErrorResponse) => {
        this.saveError.set(
          err.status === 409
            ? 'One of the amounts is more than that invoice’s balance due.'
            : err.status === 400
              ? 'The amounts allocated must add up to the whole payment.'
              : 'Could not record the payment. Please try again.',
        );
        this.saving.set(false);
        done();
      },
    });
  }

  /** Resolves once any payment being saved has been recorded or refused, so leaving the page does not race it. */
  settled(): Promise<void> {
    return this.inFlight ?? Promise.resolve();
  }

  private format(cents: number): string {
    return `$${(cents / 100).toFixed(2)}`;
  }
}
