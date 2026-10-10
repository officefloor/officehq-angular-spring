import { Component, inject, output, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MoneyPipe } from '../currencies/money.pipe';
import { TaxAdjustmentService } from './tax-adjustment.service';

// Manual adjustments to the tax owed: record a signed amount against a date in the period it belongs to, and
// list those recorded.
@Component({
  selector: 'app-tax-adjustments',
  imports: [ReactiveFormsModule, MoneyPipe],
  template: `
    <section aria-labelledby="tax-adjustments-heading" data-testid="tax-adjustments">
      <h3 id="tax-adjustments-heading">Tax adjustments</h3>
      <p id="tax-adjustments-hint">A positive amount adds to the tax owed; a negative amount reduces it.</p>
      <form [formGroup]="form" (ngSubmit)="save()" data-testid="tax-adjustment-form" novalidate>
        <div>
          <label for="tax-adjustment-amount">Amount</label>
          <input
            id="tax-adjustment-amount"
            type="number"
            inputmode="decimal"
            step="0.01"
            formControlName="amount"
            data-testid="tax-adjustment-amount"
            [attr.aria-invalid]="invalid('amount')"
            [attr.aria-describedby]="
              invalid('amount') ? 'tax-adjustments-hint tax-adjustment-amount-error' : 'tax-adjustments-hint'
            "
          />
          @if (invalid('amount')) {
            <p id="tax-adjustment-amount-error" role="alert" data-testid="tax-adjustment-amount-error">
              Enter a non-zero amount with at most two decimal places.
            </p>
          }
        </div>
        <div>
          <label for="tax-adjustment-date">Date</label>
          <input
            id="tax-adjustment-date"
            type="date"
            formControlName="date"
            data-testid="tax-adjustment-date"
            [attr.aria-invalid]="invalid('date')"
            [attr.aria-describedby]="invalid('date') ? 'tax-adjustment-date-error' : null"
          />
          @if (invalid('date')) {
            <p id="tax-adjustment-date-error" role="alert" data-testid="tax-adjustment-date-error">Enter a date.</p>
          }
        </div>
        <div>
          <label for="tax-adjustment-reason">Reason (optional)</label>
          <input
            id="tax-adjustment-reason"
            type="text"
            maxlength="200"
            formControlName="reason"
            data-testid="tax-adjustment-reason"
          />
        </div>
        <button type="submit" data-testid="tax-adjustment-submit" [disabled]="saving()">Record adjustment</button>
        @if (saved()) {
          <p role="status" data-testid="tax-adjustment-saved">Adjustment recorded.</p>
        }
        @if (saveError()) {
          <p role="alert" data-testid="tax-adjustment-error">{{ saveError() }}</p>
        }
      </form>
      @if (adjustments.error()) {
        <p role="alert" data-testid="tax-adjustments-load-error">Could not load the tax adjustments.</p>
      } @else if (adjustments.hasValue() && adjustments.value().length === 0) {
        <p data-testid="tax-adjustments-empty">No tax adjustments recorded yet.</p>
      } @else if (adjustments.hasValue()) {
        <table data-testid="tax-adjustments-table">
          <caption class="visually-hidden">Recorded tax adjustments</caption>
          <thead>
            <tr>
              <th scope="col">Date</th>
              <th scope="col">Amount</th>
              <th scope="col">Reason</th>
            </tr>
          </thead>
          <tbody>
            @for (a of adjustments.value(); track a.id) {
              <tr [attr.data-testid]="'tax-adjustment-row-' + a.id">
                <th scope="row" data-testid="tax-adjustment-row-date">{{ a.date }}</th>
                <td data-testid="tax-adjustment-row-amount">{{ a.amount | money: a.currency }}</td>
                <td data-testid="tax-adjustment-row-reason">{{ a.reason }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
    </section>
  `,
})
export class TaxAdjustments {
  private readonly service = inject(TaxAdjustmentService);

  /** Emits once an adjustment has been recorded, so what it counts towards can refresh. */
  readonly recorded = output();

  protected readonly adjustments = rxResource({ stream: () => this.service.list() });

  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    amount: ['', [Validators.required, Validators.pattern(/^\s*-?\d+(\.\d{1,2})?\s*$/), nonZero]],
    date: ['', Validators.required],
    reason: ['', Validators.maxLength(200)],
  });

  protected invalid(name: 'amount' | 'date'): boolean {
    const control = this.form.controls[name];
    return control.invalid && (control.touched || control.dirty);
  }

  protected save(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    this.saving.set(true);
    this.saved.set(false);
    this.saveError.set(null);
    const { amount, date, reason } = this.form.getRawValue();
    this.service.record({ amount: Number(amount), date, reason: reason.trim() || null }).subscribe({
      next: () => {
        this.adjustments.reload();
        this.form.reset();
        this.saving.set(false);
        this.saved.set(true);
        this.recorded.emit();
      },
      error: () => {
        this.saveError.set('Could not record the adjustment. Please try again.');
        this.saving.set(false);
      },
    });
  }
}

function nonZero(control: { value: unknown }): { zero: true } | null {
  return control.value !== '' && control.value !== null && Number(control.value) === 0 ? { zero: true } : null;
}
