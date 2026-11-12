import { HttpErrorResponse } from '@angular/common/http';
import { FigurePipe } from '../../core/locale';
import { Component, computed, inject, input, signal } from '@angular/core';
import { Plans } from '../../core/plans';

/** An amount with at most two decimals, more than 0.00. */
const AMOUNT = /^\d{1,10}(\.\d{1,2})?$/;
/** A date typed as yyyy-mm-dd. */
const DATE = /^\d{4}-\d{2}-\d{2}$/;
/** A whole number of days. */
const WHOLE = /^\d{1,6}$/;

// A person's plans, and the form on their row that adds one.
@Component({
  selector: 'app-person-plans',
  imports: [FigurePipe],
  template: `
    <ul [attr.aria-label]="'Plans of ' + personName()">
      @for (pl of plans(); track pl.id) {
        <li [attr.data-testid]="'plan-row-' + pl.id">
          {{ pl.amount }} from {{ pl.start }}
          @if (pl.day !== null) { monthly on day {{ pl.day }} } @else { every {{ pl.every }} days }
          to {{ pl.end }};
          made <span data-testid="plan-generated">{{ pl.generated | fig }}</span>;
          next <span data-testid="plan-next-dates">{{ pl.nextDates | fig }}</span>;
          paused <span data-testid="plan-paused">{{ pl.paused ? 'yes' : 'no' }}</span>
          <button type="button" data-testid="plan-pause" (click)="pause(pl.id, true)">Pause</button>
          <button type="button" data-testid="plan-resume" (click)="pause(pl.id, false)">Resume</button>
          @if (rowErrors()[pl.id]; as message) {
            <p role="alert" data-testid="plan-error">{{ message | fig }}</p>
          }
        </li>
      }
    </ul>
    <form (submit)="add($event)" [attr.aria-label]="'Add a plan for ' + personName()">
      <label [for]="'plan-form-amount-' + personId()" class="visually-hidden">Plan amount</label>
      <input
        [id]="'plan-form-amount-' + personId()"
        data-testid="plan-form-amount"
        placeholder="Amount"
        [value]="amount()"
        (input)="amount.set(value($event)); error.set(null)"
      />
      <label [for]="'plan-form-start-' + personId()" class="visually-hidden">Plan start</label>
      <input
        [id]="'plan-form-start-' + personId()"
        type="date"
        data-testid="plan-form-start"
        [value]="start()"
        (input)="start.set(value($event)); error.set(null)"
      />
      <label [for]="'plan-form-every-' + personId()" class="visually-hidden">Every how many days</label>
      <input
        [id]="'plan-form-every-' + personId()"
        data-testid="plan-form-every"
        placeholder="Every N days"
        [value]="every()"
        (input)="every.set(value($event)); error.set(null)"
      />
      <label [for]="'plan-form-day-' + personId()" class="visually-hidden">Or monthly on day of the month</label>
      <input
        [id]="'plan-form-day-' + personId()"
        data-testid="plan-form-day"
        placeholder="Or day of month"
        [value]="day()"
        (input)="day.set(value($event)); error.set(null)"
      />
      <label [for]="'plan-form-end-' + personId()" class="visually-hidden">Plan end</label>
      <input
        [id]="'plan-form-end-' + personId()"
        type="date"
        data-testid="plan-form-end"
        [value]="end()"
        (input)="end.set(value($event)); error.set(null)"
      />
      <button type="submit" data-testid="plan-form-submit">Add plan</button>
      @if (error(); as message) {
        <p role="alert" data-testid="plan-error">{{ message | fig }}</p>
      }
    </form>
  `,
})
export class PersonPlans {
  private readonly store = inject(Plans);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();

  protected readonly plans = computed(() => this.store.list().filter((p) => p.personId === this.personId()));
  protected readonly amount = signal('');
  protected readonly start = signal('');
  protected readonly every = signal('');
  protected readonly day = signal('');
  protected readonly end = signal('');
  protected readonly error = signal<string | null>(null);
  /** The error of the last action on each plan's row, by plan id. */
  protected readonly rowErrors = signal<Record<number, string>>({});
  /** Whether an add is waiting for its answer: a second add meanwhile is ignored. */
  private adding = false;

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async pause(planId: number, on: boolean): Promise<void> {
    this.rowErrors.update(({ [planId]: _, ...rest }) => rest);
    try {
      await this.store.pause(planId, on);
    } catch (e) {
      const detail = e instanceof HttpErrorResponse && typeof e.error?.message === 'string' ? ` ${e.error.message}` : '';
      const message = (on ? 'The plan could not be paused.' : 'The plan could not be resumed.') + detail;
      this.rowErrors.update((errors) => ({ ...errors, [planId]: message }));
    }
  }

  protected async add(event: Event): Promise<void> {
    event.preventDefault();
    if (this.adding) return;
    this.error.set(null);
    const plan = {
      amount: this.amount().trim(),
      start: this.start().trim(),
      every: this.every().trim(),
      day: this.day().trim(),
      end: this.end().trim(),
    };
    if (!AMOUNT.test(plan.amount) || Number(plan.amount) <= 0) {
      this.error.set('Type an amount more than 0.00 with at most two decimals.');
      return;
    }
    if (!DATE.test(plan.start) || !DATE.test(plan.end)) {
      this.error.set('Type the start and end as yyyy-mm-dd.');
      return;
    }
    if ((plan.every === '') === (plan.day === '')) {
      this.error.set('Type either every how many days or a day of the month, not both.');
      return;
    }
    if (plan.every !== '' && (!WHOLE.test(plan.every) || Number(plan.every) < 1)) {
      this.error.set('Type every how many days as a whole number, at least 1.');
      return;
    }
    if (plan.day !== '' && (!WHOLE.test(plan.day) || Number(plan.day) < 1 || Number(plan.day) > 31)) {
      this.error.set('Type the day of the month as a whole number from 1 to 31.');
      return;
    }
    if (plan.end < plan.start) {
      this.error.set('The end may not be before the start.');
      return;
    }
    this.adding = true;
    try {
      await this.store.add(this.personId(), plan);
      this.amount.set('');
      this.start.set('');
      this.every.set('');
      this.day.set('');
      this.end.set('');
    } catch (e) {
      const detail = e instanceof HttpErrorResponse && typeof e.error?.message === 'string' ? ` ${e.error.message}` : '';
      this.error.set('The plan could not be added.' + detail);
    } finally {
      this.adding = false;
    }
  }
}
