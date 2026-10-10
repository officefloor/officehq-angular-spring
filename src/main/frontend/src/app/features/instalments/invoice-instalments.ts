import { MoneyPipe } from '../currencies/money.pipe';
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Instalment, InstalmentService } from './instalment.service';
import { CurrencyCode } from '../clients/client.service';

const TWO_DECIMALS = /^\d+(\.\d{1,2})?$/;

// The scheduled instalments an invoice is split into, each an amount due on a date, and a form to
// schedule another while the invoice is still to be paid.
@Component({
  selector: 'app-invoice-instalments',
  imports: [MoneyPipe, ReactiveFormsModule],
  template: `
    <section aria-labelledby="invoice-instalments-heading" data-testid="invoice-instalments">
      <h2 id="invoice-instalments-heading">Instalment plan</h2>
      @if (instalments.error()) {
        <p role="alert" data-testid="invoice-instalments-error">Could not load the instalments.</p>
      } @else if (instalments.hasValue()) {
        @if (instalments.value().length === 0) {
          <p data-testid="invoice-instalments-empty">This invoice is not split into instalments.</p>
        } @else {
          @if (next.hasValue() && next.value(); as due) {
            <p data-testid="instalment-next">
              Next instalment due:
              <strong data-testid="instalment-next-amount">{{ due.amount | money: currency() }}</strong>
              on <span data-testid="instalment-next-date">{{ due.date }}</span>
              @if (due.overdue) {
                <span data-testid="instalment-next-overdue">(overdue)</span>
              }
            </p>
          } @else if (next.hasValue()) {
            <p data-testid="instalment-next-none">No further instalments are due.</p>
          }
          @if (interest.hasValue() && interest.value().interestPerDay > 0) {
            <p data-testid="instalment-interest-rate">
              Interest on late instalments:
              <strong data-testid="instalment-interest-per-day">{{ interest.value().interestPerDay | money: currency() }}</strong>
              per day
            </p>
          }
          <table data-testid="invoice-instalments-table">
            <caption>When each part of this invoice is due</caption>
            <thead>
              <tr>
                <th scope="col">Due</th>
                <th scope="col">Amount</th>
                <th scope="col">Status</th>
                <th scope="col">Days late</th>
                <th scope="col">Interest</th>
                @if (canEdit()) {
                  <th scope="col"><span class="visually-hidden">Actions</span></th>
                }
              </tr>
            </thead>
            <tbody>
              @for (n of instalments.value(); track n.id) {
                <tr [attr.data-testid]="'instalment-row-' + n.id">
                  <td data-testid="instalment-date">{{ n.date }}</td>
                  <td data-testid="instalment-amount">{{ n.amount | money: currency() }}</td>
                  <td data-testid="instalment-status">{{ n.paid ? 'Paid' : 'Unpaid' }}</td>
                  <td data-testid="instalment-days-late">{{ n.daysLate }}</td>
                  <td data-testid="instalment-interest">{{ n.interest | money: currency() }}</td>
                  @if (canEdit()) {
                    <td>
                      <button
                        type="button"
                        [attr.data-testid]="'instalment-remove-' + n.id"
                        [attr.aria-label]="'Remove instalment due ' + n.date"
                        [disabled]="removing() === n.id"
                        (click)="remove(n)"
                      >
                        Remove
                      </button>
                    </td>
                  }
                </tr>
              }
            </tbody>
            <tfoot>
              <tr>
                <th scope="row">Scheduled</th>
                <td data-testid="invoice-instalments-total">{{ scheduledCents() / 100 | money: currency() }}</td>
              </tr>
              <tr>
                <th scope="row">Interest</th>
                <td data-testid="invoice-instalments-interest">{{ interestCents() / 100 | money: currency() }}</td>
              </tr>
              <tr>
                <th scope="row">Unscheduled</th>
                <td data-testid="invoice-instalments-unscheduled">
                  {{ (invoiceCents() - scheduledCents()) / 100 | money: currency() }}
                </td>
              </tr>
            </tfoot>
          </table>
        }
      }
      @if (removeError()) {
        <p role="alert" data-testid="instalment-remove-error">{{ removeError() }}</p>
      }

      @if (canEdit()) {
        <form [formGroup]="interestForm" (ngSubmit)="applyInterest()" data-testid="instalment-interest-form" novalidate>
          <h3>Interest on late instalments</h3>
          <div>
            <label for="instalment-interest-per-day">Interest per day late</label>
            <input
              id="instalment-interest-per-day"
              type="number"
              inputmode="decimal"
              min="0"
              step="0.01"
              formControlName="interestPerDay"
              data-testid="instalment-interest-form-per-day"
              [attr.aria-invalid]="interestInvalid()"
              [attr.aria-describedby]="interestInvalid() ? 'instalment-interest-per-day-error' : null"
            />
            @if (interestInvalid()) {
              <p id="instalment-interest-per-day-error" role="alert" data-testid="instalment-interest-form-per-day-error">
                Enter an amount of zero or more with at most two decimal places.
              </p>
            }
          </div>
          <button type="submit" data-testid="instalment-interest-form-submit" [disabled]="interestSaving()">Apply interest</button>
          @if (interestError()) {
            <p role="alert" data-testid="instalment-interest-form-error">{{ interestError() }}</p>
          }
        </form>

        <form [formGroup]="form" (ngSubmit)="submit()" data-testid="instalment-form" novalidate>
          <h3>Schedule an instalment</h3>
          <div>
            <label for="instalment-amount">Amount</label>
            <input
              id="instalment-amount"
              type="number"
              inputmode="decimal"
              min="0.01"
              step="0.01"
              formControlName="amount"
              data-testid="instalment-form-amount"
              [attr.aria-invalid]="invalid('amount')"
              [attr.aria-describedby]="invalid('amount') ? 'instalment-amount-error' : null"
            />
            @if (invalid('amount')) {
              <p id="instalment-amount-error" role="alert" data-testid="instalment-form-amount-error">
                Enter an amount greater than zero with at most two decimal places.
              </p>
            }
          </div>
          <div>
            <label for="instalment-date">Due date</label>
            <input
              id="instalment-date"
              type="date"
              formControlName="date"
              data-testid="instalment-form-date"
              [attr.aria-invalid]="invalid('date')"
              [attr.aria-describedby]="invalid('date') ? 'instalment-date-error' : null"
            />
            @if (invalid('date')) {
              <p id="instalment-date-error" role="alert" data-testid="instalment-form-date-error">Enter the date it is due.</p>
            }
          </div>
          <button type="submit" data-testid="instalment-form-submit" [disabled]="saving()">Schedule instalment</button>
          @if (saveError()) {
            <p role="alert" data-testid="instalment-form-error">{{ saveError() }}</p>
          }
        </form>
      }
    </section>
  `,
})
export class InvoiceInstalments {
  private readonly service = inject(InstalmentService);

  readonly projectId = input.required<number>();
  readonly invoiceId = input.required<number>();
  /** The invoice amount the instalments may add up to at most. */
  readonly invoiceAmount = input.required<number>();
  /** The currency of the client the job is for; the money shown is in it. */
  readonly currency = input.required<CurrencyCode>();
  /** Instalments can be scheduled or removed while the invoice is still to be paid. */
  readonly canEdit = input.required<boolean>();

  protected readonly instalments = rxResource({
    params: () => ({ projectId: this.projectId(), invoiceId: this.invoiceId() }),
    stream: ({ params }) => this.service.list(params.projectId, params.invoiceId),
  });

  protected readonly next = rxResource({
    params: () => ({ projectId: this.projectId(), invoiceId: this.invoiceId() }),
    stream: ({ params }) => this.service.next(params.projectId, params.invoiceId),
  });

  protected readonly interest = rxResource({
    params: () => ({ projectId: this.projectId(), invoiceId: this.invoiceId() }),
    stream: ({ params }) => this.service.interest(params.projectId, params.invoiceId),
  });

  // Work in whole cents so the totals are exact.
  protected readonly scheduledCents = computed(() =>
    (this.instalments.hasValue() ? this.instalments.value() : []).reduce((sum, n) => sum + Math.round(n.amount * 100), 0),
  );
  protected readonly interestCents = computed(() =>
    (this.instalments.hasValue() ? this.instalments.value() : []).reduce((sum, n) => sum + Math.round(n.interest * 100), 0),
  );
  protected readonly invoiceCents = computed(() => Math.round(this.invoiceAmount() * 100));

  protected readonly saving = signal(false);
  protected readonly saveError = signal<string | null>(null);
  protected readonly removing = signal<number | null>(null);
  protected readonly removeError = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    amount: ['', [Validators.required, Validators.min(0.01), Validators.pattern(TWO_DECIMALS)]],
    date: ['', Validators.required],
  });

  protected readonly interestSaving = signal(false);
  protected readonly interestError = signal<string | null>(null);

  protected readonly interestForm = inject(NonNullableFormBuilder).group({
    interestPerDay: ['', [Validators.required, Validators.min(0), Validators.pattern(TWO_DECIMALS)]],
  });

  // Show the current rate in the form once it has loaded.
  private readonly syncInterest = effect(() => {
    if (this.interest.hasValue()) {
      this.interestForm.setValue({ interestPerDay: String(this.interest.value().interestPerDay) });
    }
  });

  protected interestInvalid(): boolean {
    const control = this.interestForm.controls.interestPerDay;
    return control.invalid && (control.touched || control.dirty);
  }

  protected applyInterest(): void {
    if (this.interestForm.invalid) {
      this.interestForm.markAllAsTouched();
      return;
    }
    this.interestSaving.set(true);
    this.interestError.set(null);
    const interestPerDay = Number(this.interestForm.getRawValue().interestPerDay);
    this.service.applyInterest(this.projectId(), this.invoiceId(), interestPerDay).subscribe({
      next: (saved) => {
        this.interest.set(saved);
        // The interest on each instalment is worked out from the new rate.
        this.instalments.reload();
        this.interestSaving.set(false);
      },
      error: () => {
        this.interestError.set('Could not apply the interest. Please try again.');
        this.interestSaving.set(false);
      },
    });
  }

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
    const { amount, date } = this.form.getRawValue();
    this.service.schedule(this.projectId(), this.invoiceId(), { amount: Number(amount), date }).subscribe({
      next: () => {
        // Reload so the schedule stays in due-date order.
        this.instalments.reload();
        this.next.reload();
        this.form.reset();
        this.saving.set(false);
      },
      error: (err: HttpErrorResponse) => {
        this.saveError.set(
          err.status === 409
            ? 'The instalments would add up to more than the invoice.'
            : 'Could not schedule the instalment. Please try again.',
        );
        this.saving.set(false);
      },
    });
  }

  protected remove(instalment: Instalment): void {
    this.removing.set(instalment.id);
    this.removeError.set(null);
    this.service.remove(this.projectId(), this.invoiceId(), instalment.id).subscribe({
      next: () => {
        this.instalments.update((list) => (list ?? []).filter((n) => n.id !== instalment.id));
        this.next.reload();
        this.removing.set(null);
      },
      error: () => {
        this.removeError.set('Could not remove the instalment. Please try again.');
        this.removing.set(null);
      },
    });
  }
}
