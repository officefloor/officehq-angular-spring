import { CurrencyPipe, DatePipe } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { CreditNoteService } from './credit-note.service';
import { CurrencyCode } from '../clients/client.service';

const TWO_DECIMALS = /^\d+(\.\d{1,2})?$/;

// The credit notes raised against an invoice to give money back to the client, and a form to raise
// another once the invoice has been sent and while it is not cancelled.
@Component({
  selector: 'app-invoice-credit-notes',
  imports: [ReactiveFormsModule, CurrencyPipe, DatePipe],
  template: `
    <section aria-labelledby="invoice-credit-notes-heading" data-testid="invoice-credit-notes">
      <h2 id="invoice-credit-notes-heading">Credit notes</h2>
      @if (creditNotes.error()) {
        <p role="alert" data-testid="invoice-credit-notes-error">Could not load the credit notes.</p>
      } @else if (creditNotes.hasValue()) {
        @if (creditNotes.value().length === 0) {
          <p data-testid="invoice-credit-notes-empty">No credit notes raised yet.</p>
        } @else {
          <table data-testid="invoice-credit-notes-table">
            <caption>Money given back to the client on this invoice</caption>
            <thead>
              <tr>
                <th scope="col">Raised</th>
                <th scope="col">Amount</th>
              </tr>
            </thead>
            <tbody>
              @for (c of creditNotes.value(); track c.id) {
                <tr [attr.data-testid]="'credit-note-row-' + c.id">
                  <td data-testid="credit-note-date">{{ c.issuedAt | date: 'yyyy-MM-dd' }}</td>
                  <td data-testid="credit-note-amount">{{ c.amount | currency: currency() : 'symbol' : '1.2-2' : 'en-US' }}</td>
                </tr>
              }
            </tbody>
            <tfoot>
              <tr>
                <th scope="row">Credited</th>
                <td data-testid="invoice-credited-total">{{ creditedCents() / 100 | currency: currency() : 'symbol' : '1.2-2' : 'en-US' }}</td>
              </tr>
            </tfoot>
          </table>
        }
      }

      @if (canIssue()) {
        <form [formGroup]="form" (ngSubmit)="submit()" data-testid="credit-note-form" novalidate>
          <h3>Raise a credit note</h3>
          <div>
            <label for="credit-note-amount">Amount</label>
            <input
              id="credit-note-amount"
              type="number"
              inputmode="decimal"
              min="0.01"
              step="0.01"
              formControlName="amount"
              data-testid="credit-note-form-amount"
              [attr.aria-invalid]="invalid()"
              [attr.aria-describedby]="invalid() ? 'credit-note-amount-error' : null"
            />
            @if (invalid()) {
              <p id="credit-note-amount-error" role="alert" data-testid="credit-note-form-amount-error">
                Enter an amount greater than zero with at most two decimal places.
              </p>
            }
          </div>
          <button type="submit" data-testid="credit-note-form-submit" [disabled]="saving()">Raise credit note</button>
          @if (saveError()) {
            <p role="alert" data-testid="credit-note-form-error">{{ saveError() }}</p>
          }
        </form>
      }
    </section>
  `,
})
export class InvoiceCreditNotes {
  private readonly service = inject(CreditNoteService);

  readonly projectId = input.required<number>();
  readonly invoiceId = input.required<number>();
  /** The currency of the client the job is for; the money shown is in it. */
  readonly currency = input.required<CurrencyCode>();
  /** Credit notes can be raised once the invoice has been sent, unless it has been cancelled. */
  readonly canIssue = input.required<boolean>();

  protected readonly creditNotes = rxResource({
    params: () => ({ projectId: this.projectId(), invoiceId: this.invoiceId() }),
    stream: ({ params }) => this.service.list(params.projectId, params.invoiceId),
  });

  // Work in whole cents so the total is exact.
  protected readonly creditedCents = computed(() =>
    (this.creditNotes.hasValue() ? this.creditNotes.value() : []).reduce((sum, c) => sum + Math.round(c.amount * 100), 0),
  );

  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    amount: ['', [Validators.required, Validators.min(0.01), Validators.pattern(TWO_DECIMALS)]],
  });

  protected invalid(): boolean {
    const control = this.form.controls.amount;
    return control.invalid && (control.touched || control.dirty);
  }

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.saveError.set(null);
    const { amount } = this.form.getRawValue();
    this.service.issue(this.projectId(), this.invoiceId(), { amount: Number(amount) }).subscribe({
      next: (created) => {
        this.creditNotes.update((list) => [...(list ?? []), created]);
        this.form.reset();
        this.saving.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.saveError.set(
          err.status === 409
            ? 'That credit is more than is left on the invoice.'
            : 'Could not raise the credit note. Please try again.',
        );
        this.saving.set(false);
      },
    });
  }
}
