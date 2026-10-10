import { Component, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MoneyPipe } from '../currencies/money.pipe';
import { RecurringInvoiceService } from './recurring-invoice.service';

const TWO_DECIMALS = /^\d+(\.\d{1,2})?$/;

// The invoices on a project that repeat every month for a fixed amount, and a form to set up another.
@Component({
  selector: 'app-project-recurring-invoices',
  imports: [MoneyPipe, ReactiveFormsModule],
  template: `
    <section aria-labelledby="recurring-heading" data-testid="project-recurring">
      <h2 id="recurring-heading">Recurring invoices</h2>
      @if (recurring.error()) {
        <p role="alert" data-testid="recurring-error">Could not load the recurring invoices.</p>
      } @else if (recurring.hasValue()) {
        @if (recurring.value().length === 0) {
          <p data-testid="recurring-empty">No recurring invoices.</p>
        } @else {
          <table data-testid="recurring-list">
            <thead>
              <tr>
                <th scope="col">Amount</th>
                <th scope="col">Repeats</th>
                <th scope="col">Next invoice</th>
              </tr>
            </thead>
            <tbody>
              @for (r of recurring.value(); track r.id) {
                <tr [attr.data-testid]="'recurring-row-' + r.id">
                  <td data-testid="recurring-amount">{{ r.amount | money: currency() }}</td>
                  <td data-testid="recurring-frequency">{{ r.frequency }}</td>
                  <td data-testid="recurring-next-date">{{ r.nextDate }}</td>
                </tr>
              }
            </tbody>
          </table>
        }
      }
      <form [formGroup]="form" (ngSubmit)="submit()" data-testid="recurring-form" novalidate>
        <div>
          <label for="recurring-amount">Amount each month</label>
          <input
            id="recurring-amount"
            type="number"
            inputmode="decimal"
            min="0.01"
            step="0.01"
            formControlName="amount"
            data-testid="recurring-form-amount"
            [attr.aria-invalid]="invalid('amount')"
            [attr.aria-describedby]="invalid('amount') ? 'recurring-amount-error' : null"
          />
          @if (invalid('amount')) {
            <p id="recurring-amount-error" role="alert" data-testid="recurring-form-amount-error">
              Enter an amount greater than zero with at most two decimal places.
            </p>
          }
        </div>
        <div>
          <label for="recurring-next-date">First invoice date</label>
          <input
            id="recurring-next-date"
            type="date"
            formControlName="nextDate"
            data-testid="recurring-form-next-date"
            [attr.aria-invalid]="invalid('nextDate')"
            [attr.aria-describedby]="invalid('nextDate') ? 'recurring-next-date-error' : null"
          />
          @if (invalid('nextDate')) {
            <p id="recurring-next-date-error" role="alert" data-testid="recurring-form-next-date-error">
              Enter the date of the first invoice.
            </p>
          }
        </div>
        <button type="submit" data-testid="recurring-form-submit" [disabled]="saving()">
          Set up monthly invoice
        </button>
        @if (saveError()) {
          <p role="alert" data-testid="recurring-form-error">{{ saveError() }}</p>
        }
        @if (saved()) {
          <p role="status" data-testid="recurring-form-saved">Recurring invoice set up.</p>
        }
      </form>
    </section>
  `,
})
export class ProjectRecurringInvoices {
  private readonly service = inject(RecurringInvoiceService);

  readonly projectId = input.required<number>();
  /** The project's currency; its recurring invoices are billed in it. */
  readonly currency = input.required<string>();

  protected readonly recurring = rxResource({
    params: () => this.projectId(),
    stream: ({ params }) => this.service.list(params),
  });

  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    amount: ['', [Validators.required, Validators.min(0.01), Validators.pattern(TWO_DECIMALS)]],
    nextDate: ['', [Validators.required]],
  });

  protected invalid(name: 'amount' | 'nextDate'): boolean {
    const control = this.form.controls[name];
    return control.invalid && (control.touched || control.dirty);
  }

  protected submit(): void {
    this.saveError.set(null);
    this.saved.set(false);
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { amount, nextDate } = this.form.getRawValue();
    this.saving.set(true);
    this.service.create(this.projectId(), { amount: Number(amount), frequency: 'MONTHLY', nextDate }).subscribe({
      next: () => {
        this.saving.set(false);
        this.saved.set(true);
        this.form.reset();
        this.recurring.reload();
      },
      error: () => {
        this.saving.set(false);
        this.saveError.set('Could not set up the recurring invoice. Please try again.');
      },
    });
  }
}
