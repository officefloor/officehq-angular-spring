import { CurrencyPipe } from '@angular/common';
import { Component, inject, input, output, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { CurrencyCode } from '../clients/client.service';
import { CreditService } from './credit.service';

const TWO_DECIMALS = /^\d+(\.\d{1,2})?$/;

// How much credit a client has to spend: their unused deposits and credit notes added up, and a form to refund some of it back to them.
@Component({
  selector: 'app-client-credit',
  imports: [CurrencyPipe, ReactiveFormsModule],
  template: `
    @if (credit.error()) {
      <p role="alert" data-testid="client-available-credit-error">Could not load the client's available credit.</p>
    } @else if (credit.hasValue()) {
      <p>
        Available credit:
        <span data-testid="client-available-credit">{{ credit.value().total | currency: currency() : 'symbol' : '1.2-2' : 'en-US' }}</span>
      </p>
    }
    <form [formGroup]="form" (ngSubmit)="submit()" data-testid="refund-form" aria-label="Refund credit" novalidate>
      <div>
        <label for="refund-amount">Refund amount</label>
        <input
          id="refund-amount"
          type="number"
          inputmode="decimal"
          min="0.01"
          step="0.01"
          formControlName="amount"
          data-testid="refund-form-amount"
          [attr.aria-invalid]="invalid('amount')"
          [attr.aria-describedby]="invalid('amount') ? 'refund-amount-error' : null"
        />
        @if (invalid('amount')) {
          <p id="refund-amount-error" role="alert" data-testid="refund-form-amount-error">
            Enter an amount greater than zero with at most two decimal places.
          </p>
        }
      </div>
      <div>
        <label for="refund-note">Note (optional)</label>
        <input id="refund-note" type="text" maxlength="500" formControlName="note" data-testid="refund-form-note" />
      </div>
      <button type="submit" data-testid="refund-form-submit" [disabled]="saving()">Refund credit</button>
      @if (saveError()) {
        <p role="alert" data-testid="refund-form-error">{{ saveError() }}</p>
      }
      @if (saved()) {
        <p role="status" data-testid="refund-form-recorded">Refund recorded.</p>
      }
    </form>
  `,
})
export class ClientCredit {
  private readonly service = inject(CreditService);

  readonly clientId = input.required<number>();
  /** The client's currency; their credit is held in it. */
  readonly currency = input.required<CurrencyCode>();
  /** Emits once credit has been refunded, so other panels showing it can refresh. */
  readonly refunded = output<void>();

  protected readonly credit = rxResource({
    params: () => this.clientId(),
    stream: ({ params }) => this.service.available(params),
  });

  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    amount: ['', [Validators.required, Validators.min(0.01), Validators.pattern(TWO_DECIMALS)]],
    note: ['', [Validators.maxLength(500)]],
  });

  protected invalid(name: 'amount'): boolean {
    const control = this.form.controls[name];
    return control.invalid && (control.touched || control.dirty);
  }

  /** Loads the credit again, e.g. after deposits were put toward invoices elsewhere on the page. */
  reload(): void {
    this.credit.reload();
  }

  protected submit(): void {
    this.saveError.set(null);
    this.saved.set(false);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { amount, note } = this.form.getRawValue();
    this.saving.set(true);
    this.service.refund(this.clientId(), { amount: Number(amount), note: note.trim() || null }).subscribe({
      next: () => {
        this.saving.set(false);
        this.saved.set(true);
        this.form.reset();
        this.credit.reload();
        this.refunded.emit();
      },
      error: (err: { status?: number }) => {
        this.saving.set(false);
        this.saveError.set(
          err.status === 400
            ? 'The refund is more than the credit the client has.'
            : 'Could not record the refund. Please try again.',
        );
      },
    });
  }
}
