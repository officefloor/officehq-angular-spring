import { Component, inject, input, output, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MoneyPipe } from '../currencies/money.pipe';
import { Observable } from 'rxjs';
import { RecurringInvoice, RecurringInvoiceService } from './recurring-invoice.service';

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
                <th scope="col">First invoice (pro-rated)</th>
                <th scope="col">Repeats</th>
                <th scope="col">Next invoice</th>
                <th scope="col">Status</th>
                <th scope="col"><span class="visually-hidden">Actions</span></th>
              </tr>
            </thead>
            <tbody>
              @for (r of recurring.value(); track r.id) {
                <tr [attr.data-testid]="'recurring-row-' + r.id">
                  <td data-testid="recurring-amount">{{ r.amount | money: currency() }}</td>
                  <td data-testid="recurring-prorated-amount">
                    @if (r.proratedAmount !== null) {
                      {{ r.proratedAmount | money: currency() }}
                    } @else {
                      <span aria-hidden="true">—</span><span class="visually-hidden">Not pro-rated</span>
                    }
                  </td>
                  <td data-testid="recurring-frequency">{{ r.frequency }}</td>
                  <td data-testid="recurring-next-date">{{ r.nextDate }}</td>
                  <td data-testid="recurring-status">{{ r.status }}</td>
                  <td>
                    @if (r.status === 'PAUSED') {
                      <button
                        type="button"
                        [attr.data-testid]="'recurring-resume-' + r.id"
                        [attr.aria-label]="'Resume recurring invoice next due ' + r.nextDate"
                        [disabled]="toggling() === r.id"
                        (click)="resume(r.id)"
                      >
                        Resume
                      </button>
                    } @else {
                      <button
                        type="button"
                        [attr.data-testid]="'recurring-pause-' + r.id"
                        [attr.aria-label]="'Pause recurring invoice next due ' + r.nextDate"
                        [disabled]="toggling() === r.id"
                        (click)="pause(r.id)"
                      >
                        Pause
                      </button>
                    }
                    @if (r.due) {
                      <button
                        type="button"
                        [attr.data-testid]="'recurring-generate-' + r.id"
                        [attr.aria-label]="'Create draft invoice for ' + r.nextDate"
                        [disabled]="generating() === r.id"
                        (click)="generate(r.id)"
                      >
                        Create draft invoice
                      </button>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        }
      }
      @if (generateError()) {
        <p role="alert" data-testid="recurring-generate-error">{{ generateError() }}</p>
      }
      @if (toggleError()) {
        <p role="alert" data-testid="recurring-toggle-error">{{ toggleError() }}</p>
      }
      @if (generated(); as id) {
        <p role="status" data-testid="recurring-generated">Draft invoice #{{ id }} created for review.</p>
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
        <div>
          <input id="recurring-prorate-first" type="checkbox" formControlName="prorateFirst" data-testid="recurring-form-prorate-first" />
          <label for="recurring-prorate-first">Pro-rate the first invoice by the days left in the period</label>
        </div>
        @if (form.controls.prorateFirst.value) {
          <div>
            <label for="recurring-period-days">Days in the billing period</label>
            <input
              id="recurring-period-days"
              type="number"
              inputmode="numeric"
              min="1"
              max="366"
              step="1"
              formControlName="periodDays"
              data-testid="recurring-form-period-days"
              [attr.aria-invalid]="invalid('periodDays')"
              [attr.aria-describedby]="invalid('periodDays') ? 'recurring-period-days-error' : null"
            />
            @if (invalid('periodDays')) {
              <p id="recurring-period-days-error" role="alert" data-testid="recurring-form-period-days-error">
                Enter a whole number of days from 1 to 366.
              </p>
            }
          </div>
        }
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
  /** Emits when a due recurring invoice is raised as a draft on the project. */
  readonly generatedInvoice = output<void>();

  protected readonly recurring = rxResource({
    params: () => this.projectId(),
    stream: ({ params }) => this.service.list(params),
  });

  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly saveError = signal<string | null>(null);

  protected readonly generating = signal<number | null>(null);
  protected readonly generated = signal<number | null>(null);
  protected readonly generateError = signal<string | null>(null);

  protected readonly toggling = signal<number | null>(null);
  protected readonly toggleError = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    amount: ['', [Validators.required, Validators.min(0.01), Validators.pattern(TWO_DECIMALS)]],
    nextDate: ['', [Validators.required]],
    prorateFirst: [false],
    periodDays: [30, [Validators.required, Validators.min(1), Validators.max(366), Validators.pattern(/^\d+$/)]],
  });

  protected invalid(name: 'amount' | 'nextDate' | 'periodDays'): boolean {
    const control = this.form.controls[name];
    return control.invalid && (control.touched || control.dirty);
  }

  protected generate(recurringId: number): void {
    this.generating.set(recurringId);
    this.generated.set(null);
    this.generateError.set(null);
    this.service.generate(this.projectId(), recurringId).subscribe({
      next: (invoice) => {
        this.generating.set(null);
        this.generated.set(invoice.id);
        this.recurring.reload();
        this.generatedInvoice.emit();
      },
      error: () => {
        this.generating.set(null);
        this.generateError.set('Could not create the draft invoice. Please try again.');
      },
    });
  }

  protected pause(recurringId: number): void {
    this.toggle(recurringId, this.service.pause(this.projectId(), recurringId), 'pause');
  }

  protected resume(recurringId: number): void {
    this.toggle(recurringId, this.service.resume(this.projectId(), recurringId), 'resume');
  }

  private toggle(recurringId: number, request: Observable<RecurringInvoice>, action: string): void {
    this.toggling.set(recurringId);
    this.toggleError.set(null);
    request.subscribe({
      next: (updated) => {
        this.toggling.set(null);
        this.recurring.update((list) => list?.map((r) => (r.id === updated.id ? updated : r)));
      },
      error: () => {
        this.toggling.set(null);
        this.toggleError.set(`Could not ${action} the recurring invoice. Please try again.`);
      },
    });
  }

  protected submit(): void {
    this.saveError.set(null);
    this.saved.set(false);
    const controls = this.form.controls;
    const invalid = controls.amount.invalid || controls.nextDate.invalid
      || (controls.prorateFirst.value && controls.periodDays.invalid);
    if (invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const { amount, nextDate, prorateFirst, periodDays } = this.form.getRawValue();
    this.saving.set(true);
    const recurring = prorateFirst
      ? { amount: Number(amount), frequency: 'MONTHLY' as const, nextDate, prorateFirst, periodDays: Number(periodDays) }
      : { amount: Number(amount), frequency: 'MONTHLY' as const, nextDate };
    this.service.create(this.projectId(), recurring).subscribe({
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
