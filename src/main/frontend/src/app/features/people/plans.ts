import { Component, inject, input, output, signal } from '@angular/core';
import { Session } from '../../core/session';
import { Plan, PlansService } from './plans.service';

const AMOUNT = /^\d{1,10}(\.\d{1,2})?$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const EVERY = /^\d{1,5}$/;
const DAY = /^\d{1,2}$/;

// A person's plans, one line each in the order they were added with how many claims each has made so
// far and its next dates, and the form on the row that adds one: an amount, a start date, either every N days (a whole
// number, at least 1) or monthly on a day of the month (1 to 31), and an end date not before the start.
@Component({
  selector: 'app-plans',
  template: `
    @if (plans().length > 0) {
      <ul class="plans" [attr.aria-label]="'Plans of ' + personName()">
        @for (pl of plans(); track pl.id) {
          <li [attr.data-testid]="'plan-row-' + pl.id">
            {{ pl.amount }}
            from {{ pl.start }}
            @if (pl.day !== null) { monthly on day {{ pl.day }} } @else { every {{ pl.every }} days }
            to {{ pl.end }}, made
            <span data-testid="plan-generated">{{ pl.generated }}</span>, next
            <span data-testid="plan-next-dates">{{ pl.nextDates }}</span>
          </li>
        }
      </ul>
    }
    <form (submit)="submit($event)" [attr.aria-label]="'Plan for ' + personName()" data-testid="plan-form">
      <label [for]="'plan-form-amount-' + personId()">Plan amount</label>
      <input [id]="'plan-form-amount-' + personId()" data-testid="plan-form-amount" [value]="amount()" (input)="amount.set(value($event)); error.set(null)" />
      <label [for]="'plan-form-start-' + personId()">Start</label>
      <input [id]="'plan-form-start-' + personId()" data-testid="plan-form-start" placeholder="YYYY-MM-DD" [value]="start()" (input)="start.set(value($event)); error.set(null)" />
      <label [for]="'plan-form-every-' + personId()">Every (days)</label>
      <input [id]="'plan-form-every-' + personId()" data-testid="plan-form-every" inputmode="numeric" [value]="every()" (input)="every.set(value($event)); error.set(null)" />
      <label [for]="'plan-form-day-' + personId()">or monthly on day</label>
      <input [id]="'plan-form-day-' + personId()" data-testid="plan-form-day" inputmode="numeric" [value]="day()" (input)="day.set(value($event)); error.set(null)" />
      <label [for]="'plan-form-end-' + personId()">End</label>
      <input [id]="'plan-form-end-' + personId()" data-testid="plan-form-end" placeholder="YYYY-MM-DD" [value]="end()" (input)="end.set(value($event)); error.set(null)" />
      <button type="submit" data-testid="plan-form-submit">Add plan</button>
      @if (error(); as message) {
        <p role="alert" class="error" data-testid="plan-error">{{ message }}</p>
      }
    </form>
  `,
  styles: `
    .plans {
      margin: 0 0 0.25rem;
      padding-left: 1rem;
    }
    form {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      align-items: center;
    }
    .error {
      color: #a40000;
      margin: 0;
    }
  `,
})
export class Plans {
  private readonly session = inject(Session);
  private readonly service = inject(PlansService);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  readonly plans = input<Plan[]>([]);
  readonly added = output<Plan>();

  protected readonly amount = signal('');
  protected readonly start = signal('');
  protected readonly every = signal('');
  protected readonly day = signal('');
  protected readonly end = signal('');
  protected readonly error = signal<string | null>(null);
  private saving = false;

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.saving) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const fields = { amount: this.amount().trim(), start: this.start().trim(), every: this.every().trim(), day: this.day().trim(), end: this.end().trim() };
    if (!AMOUNT.test(fields.amount) || Number(fields.amount) <= 0) {
      this.error.set('Type an amount above zero with up to two decimals.');
      return;
    }
    if ((fields.every === '') === (fields.day === '')) {
      this.error.set('Give either every N days or a day of the month.');
      return;
    }
    if (fields.every !== '' && (!EVERY.test(fields.every) || Number(fields.every) < 1)) {
      this.error.set('Every must be a whole number of days, at least 1.');
      return;
    }
    if (fields.day !== '' && (!DAY.test(fields.day) || Number(fields.day) < 1 || Number(fields.day) > 31)) {
      this.error.set('The day of the month must be a whole number from 1 to 31.');
      return;
    }
    if (!DATE.test(fields.start) || !DATE.test(fields.end)) {
      this.error.set('Type the start and end as YYYY-MM-DD.');
      return;
    }
    if (fields.end < fields.start) {
      this.error.set('The end cannot be before the start.');
      return;
    }
    this.saving = true;
    try {
      const plan = await this.service.add(sheet, this.personId(), fields);
      if (this.session.sheetId() !== sheet) return;
      this.amount.set('');
      this.start.set('');
      this.every.set('');
      this.day.set('');
      this.end.set('');
      this.added.emit(plan);
    } catch {
      if (this.session.sheetId() !== sheet) return;
      this.error.set('That plan could not be added.');
    } finally {
      this.saving = false;
    }
  }
}
