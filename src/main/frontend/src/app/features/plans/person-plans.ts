import { Component, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Plan, Plans } from '../../core/plans';
import { FigurePipe } from '../../core/figure';

// A person's plans (every so many days or monthly on a day), in the order they were added, each with how many claims it has made so far and its next dates;
// and the form that adds one.
@Component({
  selector: 'app-person-plans',
  imports: [ReactiveFormsModule, FigurePipe],
  template: `
    <ul aria-label="Plans">
      @for (pl of plans(); track pl.id) {
        <li [attr.data-testid]="'plan-row-' + pl.id">
          <span data-testid="plan-amount-shown">{{ pl.amount | figure }}</span>
          from <span data-testid="plan-start">{{ pl.start }}</span>
          @if (pl.day !== null) {
            monthly on day <span data-testid="plan-day">{{ pl.day }}</span>
          } @else {
            every <span data-testid="plan-every">{{ pl.every }}</span> days
          }
          to <span data-testid="plan-end">{{ pl.end }}</span>;
          made <span data-testid="plan-generated">{{ pl.generated }}</span>;
          next <span data-testid="plan-next-dates">{{ pl.nextDates.join(', ') }}</span>
        </li>
      }
    </ul>
    <form [formGroup]="form" (ngSubmit)="submit()" [attr.aria-label]="'Add a plan of ' + name()">
      <label [for]="'plan-form-amount-' + personId()">Plan amount</label>
      <input [id]="'plan-form-amount-' + personId()" inputmode="decimal" data-testid="plan-form-amount"
        formControlName="amount" />
      <label [for]="'plan-form-start-' + personId()">Start</label>
      <input [id]="'plan-form-start-' + personId()" type="date" data-testid="plan-form-start" formControlName="start" />
      <label [for]="'plan-form-every-' + personId()">Every (days)</label>
      <input [id]="'plan-form-every-' + personId()" inputmode="numeric" data-testid="plan-form-every"
        formControlName="every" />
      <label [for]="'plan-form-day-' + personId()">Or monthly on day (1 to 31)</label>
      <input [id]="'plan-form-day-' + personId()" inputmode="numeric" data-testid="plan-form-day"
        formControlName="day" />
      <label [for]="'plan-form-end-' + personId()">End</label>
      <input [id]="'plan-form-end-' + personId()" type="date" data-testid="plan-form-end" formControlName="end" />
      <button type="submit" data-testid="plan-form-submit">Add plan</button>
      @if (error()) {
        <p role="alert" data-testid="plan-error">{{ error() }}</p>
      }
    </form>
  `,
})
export class PersonPlans {
  private readonly api = inject(Plans);

  readonly personId = input.required<number>();
  readonly name = input.required<string>();
  readonly plans = input.required<Plan[]>();
  readonly added = output<Plan>();

  protected readonly form = new FormGroup({
    amount: new FormControl('', { nonNullable: true }),
    start: new FormControl('', { nonNullable: true }),
    every: new FormControl('', { nonNullable: true }),
    day: new FormControl('', { nonNullable: true }),
    end: new FormControl('', { nonNullable: true }),
  });
  protected readonly error = signal('');
  /** Whether an add is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    const f = this.form.getRawValue();
    const amount = f.amount.trim();
    const every = f.every.trim();
    if (!/^\d{1,12}(\.\d{1,2})?$/.test(amount) || Number(amount) <= 0) {
      this.error.set('Enter an amount above zero with up to two decimals.');
      return;
    }
    const day = f.day.trim();
    if (!every === !day) {
      this.error.set('Enter either a number of days or a day of the month.');
      return;
    }
    if (every && (!/^\d{1,5}$/.test(every) || Number(every) < 1)) {
      this.error.set('Enter a whole number of days, at least 1.');
      return;
    }
    if (day && (!/^\d{1,2}$/.test(day) || Number(day) < 1 || Number(day) > 31)) {
      this.error.set('Enter a day of the month from 1 to 31.');
      return;
    }
    if (!f.end) {
      this.error.set('Enter the day the plan ends.');
      return;
    }
    if (f.start && f.end < f.start) {
      this.error.set('The end cannot be before the start.');
      return;
    }
    this.submitting = true;
    try {
      this.added.emit(await this.api.add(this.personId(), { amount, start: f.start, every, day, end: f.end }));
    } catch {
      this.error.set('The plan could not be added.');
    } finally {
      this.submitting = false;
    }
  }
}
