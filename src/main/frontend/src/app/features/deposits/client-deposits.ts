import { CurrencyPipe } from '@angular/common';
import { Component, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { CurrencyCode } from '../clients/client.service';
import { DepositService } from './deposit.service';

const TWO_DECIMALS = /^\d+(\.\d{1,2})?$/;

// The deposits a client has paid up front, before any invoice, with what is still held, how much has been put toward invoices or refunded, and a form to record another.
@Component({
  selector: 'app-client-deposits',
  imports: [ReactiveFormsModule, CurrencyPipe],
  template: `
    <section aria-labelledby="client-deposits-heading" data-testid="client-deposits">
      <h2 id="client-deposits-heading">Deposits</h2>
      @if (deposits.error()) {
        <p role="alert" data-testid="client-deposits-error">Could not load the client's deposits.</p>
      } @else if (deposits.hasValue()) {
        <p>
          Held: <span data-testid="client-deposit-total">{{ deposits.value().total | currency: currency() : 'symbol' : '1.2-2' : 'en-US' }}</span>
        </p>
        @if (deposits.value().applied > 0) {
          <p>
            Put toward invoices:
            <span data-testid="client-deposit-applied">{{ deposits.value().applied | currency: currency() : 'symbol' : '1.2-2' : 'en-US' }}</span>
          </p>
        }
        @if (deposits.value().refunded > 0) {
          <p>
            Refunded:
            <span data-testid="client-deposit-refunded">{{ deposits.value().refunded | currency: currency() : 'symbol' : '1.2-2' : 'en-US' }}</span>
          </p>
        }
        @if (deposits.value().deposits.length > 0) {
          <ul data-testid="client-deposit-list">
            @for (d of deposits.value().deposits; track d.id) {
              <li [attr.data-testid]="'client-deposit-' + d.id">
                <span data-testid="client-deposit-date">{{ d.date }}</span>:
                <span data-testid="client-deposit-amount">{{ d.amount | currency: currency() : 'symbol' : '1.2-2' : 'en-US' }}</span>
              </li>
            }
          </ul>
        }
      }
      <form [formGroup]="form" (ngSubmit)="submit()" data-testid="deposit-form" novalidate>
        <div>
          <label for="deposit-amount">Deposit amount</label>
          <input
            id="deposit-amount"
            type="number"
            inputmode="decimal"
            min="0.01"
            step="0.01"
            formControlName="amount"
            data-testid="deposit-form-amount"
            [attr.aria-invalid]="invalid('amount')"
            [attr.aria-describedby]="invalid('amount') ? 'deposit-amount-error' : null"
          />
          @if (invalid('amount')) {
            <p id="deposit-amount-error" role="alert" data-testid="deposit-form-amount-error">
              Enter an amount greater than zero with at most two decimal places.
            </p>
          }
        </div>
        <div>
          <label for="deposit-date">Date paid</label>
          <input
            id="deposit-date"
            type="date"
            formControlName="date"
            data-testid="deposit-form-date"
            [attr.aria-invalid]="invalid('date')"
            [attr.aria-describedby]="invalid('date') ? 'deposit-date-error' : null"
          />
          @if (invalid('date')) {
            <p id="deposit-date-error" role="alert" data-testid="deposit-form-date-error">
              Enter the date the deposit was paid.
            </p>
          }
        </div>
        <button type="submit" data-testid="deposit-form-submit" [disabled]="saving()">Record deposit</button>
        @if (saveError()) {
          <p role="alert" data-testid="deposit-form-error">{{ saveError() }}</p>
        }
        @if (saved()) {
          <p role="status" data-testid="deposit-form-recorded">Deposit recorded.</p>
        }
      </form>
    </section>
  `,
})
export class ClientDeposits {
  private readonly service = inject(DepositService);

  readonly clientId = input.required<number>();
  /** The client's currency; their deposits are held in it. */
  readonly currency = input.required<CurrencyCode>();

  protected readonly deposits = rxResource({
    params: () => this.clientId(),
    stream: ({ params }) => this.service.list(params),
  });

  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    amount: ['', [Validators.required, Validators.min(0.01), Validators.pattern(TWO_DECIMALS)]],
    date: ['', [Validators.required]],
  });

  protected invalid(name: 'amount' | 'date'): boolean {
    const control = this.form.controls[name];
    return control.invalid && (control.touched || control.dirty);
  }

  /** Loads the deposits again, e.g. after some were put toward invoices elsewhere on the page. */
  reload(): void {
    this.deposits.reload();
  }

  protected submit(): void {
    this.saveError.set(null);
    this.saved.set(false);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { amount, date } = this.form.getRawValue();
    this.saving.set(true);
    this.service.record(this.clientId(), { amount: Number(amount), date }).subscribe({
      next: () => {
        this.saving.set(false);
        this.saved.set(true);
        this.form.reset();
        this.deposits.reload();
      },
      error: () => {
        this.saving.set(false);
        this.saveError.set('Could not record the deposit. Please try again.');
      },
    });
  }
}
