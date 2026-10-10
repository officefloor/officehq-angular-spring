import { DatePipe } from '@angular/common';
import { MoneyPipe } from '../currencies/money.pipe';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { AdjustmentNoteService } from './adjustment-note.service';
import { CurrencyCode } from '../clients/client.service';

// A non-zero amount, optionally negative, with at most two decimal places.
const SIGNED_TWO_DECIMALS = /^-?\d+(\.\d{1,2})?$/;

// The adjustment notes issued against a sent invoice to correct it without editing it: the original
// amount stays as sent, each note adds to (or takes off) it with a reason, and the adjusted total follows.
@Component({
  selector: 'app-invoice-adjustment-notes',
  imports: [MoneyPipe, ReactiveFormsModule, DatePipe],
  template: `
    <section aria-labelledby="invoice-adjustment-notes-heading" data-testid="invoice-adjustment-notes">
      <h2 id="invoice-adjustment-notes-heading">Adjustment notes</h2>
      @if (adjustmentNotes.error()) {
        <p role="alert" data-testid="invoice-adjustment-notes-error">Could not load the adjustment notes.</p>
      } @else if (adjustmentNotes.hasValue()) {
        @if (adjustmentNotes.value().length === 0) {
          <p data-testid="invoice-adjustment-notes-empty">No adjustment notes issued yet.</p>
        } @else {
          <table data-testid="invoice-adjustment-notes-table">
            <caption>Corrections issued against this invoice</caption>
            <thead>
              <tr>
                <th scope="col">Issued</th>
                <th scope="col">Reason</th>
                <th scope="col">Amount</th>
              </tr>
            </thead>
            <tbody>
              @for (a of adjustmentNotes.value(); track a.id) {
                <tr [attr.data-testid]="'adjustment-note-row-' + a.id">
                  <td data-testid="adjustment-note-date">{{ a.issuedAt | date: 'yyyy-MM-dd' }}</td>
                  <td data-testid="adjustment-note-reason">{{ a.reason }}</td>
                  <td data-testid="adjustment-note-amount">{{ a.amount | money: currency() }}</td>
                </tr>
              }
            </tbody>
            <tfoot>
              <tr>
                <th scope="row" colspan="2">Original amount</th>
                <td data-testid="invoice-adjustment-original-amount">{{ invoiceAmount() | money: currency() }}</td>
              </tr>
              <tr>
                <th scope="row" colspan="2">Adjusted total</th>
                <td data-testid="invoice-adjusted-total">{{ adjustedCents() / 100 | money: currency() }}</td>
              </tr>
            </tfoot>
          </table>
        }
      }

      @if (canIssue()) {
        <form [formGroup]="form" (ngSubmit)="submit()" data-testid="adjustment-note-form" novalidate>
          <h3>Issue an adjustment note</h3>
          <div>
            <label for="adjustment-note-amount">Amount (negative to reduce)</label>
            <input
              id="adjustment-note-amount"
              type="number"
              inputmode="decimal"
              step="0.01"
              formControlName="amount"
              data-testid="adjustment-note-form-amount"
              [attr.aria-invalid]="invalid('amount')"
              [attr.aria-describedby]="invalid('amount') ? 'adjustment-note-amount-error' : null"
            />
            @if (invalid('amount')) {
              <p id="adjustment-note-amount-error" role="alert" data-testid="adjustment-note-form-amount-error">
                Enter a non-zero amount with at most two decimal places.
              </p>
            }
          </div>
          <div>
            <label for="adjustment-note-reason">Reason</label>
            <input
              id="adjustment-note-reason"
              type="text"
              maxlength="500"
              formControlName="reason"
              data-testid="adjustment-note-form-reason"
              [attr.aria-invalid]="invalid('reason')"
              [attr.aria-describedby]="invalid('reason') ? 'adjustment-note-reason-error' : null"
            />
            @if (invalid('reason')) {
              <p id="adjustment-note-reason-error" role="alert" data-testid="adjustment-note-form-reason-error">
                Give a reason for the adjustment.
              </p>
            }
          </div>
          <button type="submit" data-testid="adjustment-note-submit" [disabled]="saving()">Issue adjustment note</button>
          @if (saveError()) {
            <p role="alert" data-testid="adjustment-note-form-error">{{ saveError() }}</p>
          }
        </form>
      }
    </section>
  `,
})
export class InvoiceAdjustmentNotes {
  private readonly service = inject(AdjustmentNoteService);

  readonly projectId = input.required<number>();
  readonly invoiceId = input.required<number>();
  /** The invoice amount as sent; adjustment notes never change it. */
  readonly invoiceAmount = input.required<number>();
  /** The currency of the client the job is for; the money shown is in it. */
  readonly currency = input.required<CurrencyCode>();
  /** Adjustment notes can be issued once the invoice has been sent, unless it is cancelled or written off. */
  readonly canIssue = input.required<boolean>();

  protected readonly adjustmentNotes = rxResource({
    params: () => ({ projectId: this.projectId(), invoiceId: this.invoiceId() }),
    stream: ({ params }) => this.service.list(params.projectId, params.invoiceId),
  });

  // Work in whole cents so the total is exact.
  protected readonly adjustedCents = computed(() =>
    (this.adjustmentNotes.hasValue() ? this.adjustmentNotes.value() : []).reduce(
      (sum, a) => sum + Math.round(a.amount * 100),
      Math.round(this.invoiceAmount() * 100),
    ),
  );

  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    amount: ['', [Validators.required, Validators.pattern(SIGNED_TWO_DECIMALS), nonZero]],
    reason: ['', [Validators.required, Validators.pattern(/\S/), Validators.maxLength(500)]],
  });

  protected invalid(name: 'amount' | 'reason'): boolean {
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
    const { amount, reason } = this.form.getRawValue();
    this.service.issue(this.projectId(), this.invoiceId(), { amount: Number(amount), reason: reason.trim() }).subscribe({
      next: (created) => {
        this.adjustmentNotes.update((list) => [...(list ?? []), created]);
        this.form.reset();
        this.saving.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.saveError.set(
          err.status === 409
            ? 'That adjustment would take the invoice total below zero.'
            : 'Could not issue the adjustment note. Please try again.',
        );
        this.saving.set(false);
      },
    });
  }
}

function nonZero(control: { value: string | number }): { nonZero: true } | null {
  return control.value !== '' && Number(control.value) === 0 ? { nonZero: true } : null;
}
