import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/**
 * A person's plan: an amount (two decimals) from its start, every so many days or monthly on a day of the month
 * (one set, the other null), up to its end; how many claims it has made, and its next five dates after the sheet's
 * today (comma and space separated).
 */
export interface Plan {
  id: number;
  personId: number;
  amount: string;
  start: string;
  every: number | null;
  day: number | null;
  end: string;
  generated: number;
  nextDates: string;
}

const AMOUNT = /^\d{1,12}(\.\d{1,2})?$/;
const EVERY = /^\d{1,5}$/;
const DAY = /^\d{1,2}$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

// A person's plans, on their row, and the form that adds one. The sheet's generate button makes their claims.
@Component({
  selector: 'app-plans',
  imports: [ReactiveFormsModule],
  template: `
    <ul [attr.aria-label]="'Plans of ' + personName()">
      @for (pl of plans(); track pl.id) {
        <li [attr.data-testid]="'plan-row-' + pl.id">
          <span data-testid="plan-amount-shown">{{ pl.amount }}</span>
          from <span data-testid="plan-start">{{ pl.start }}</span>
          @if (pl.day !== null) {
            monthly on day <span data-testid="plan-day">{{ pl.day }}</span>
          } @else {
            every <span data-testid="plan-every">{{ pl.every }}</span> days
          }
          to <span data-testid="plan-end">{{ pl.end }}</span>,
          made <span data-testid="plan-generated">{{ pl.generated }}</span>,
          next <span data-testid="plan-next-dates">{{ pl.nextDates }}</span>
        </li>
      }
    </ul>
    <form [formGroup]="form" (ngSubmit)="add()" [attr.aria-label]="'Add a plan for ' + personName()">
      <label [for]="'plan-form-amount-' + personId()">Plan amount</label>
      <input [id]="'plan-form-amount-' + personId()" data-testid="plan-form-amount" formControlName="amount" inputmode="decimal" />
      <label [for]="'plan-form-start-' + personId()">Start</label>
      <input type="date" [id]="'plan-form-start-' + personId()" data-testid="plan-form-start" formControlName="start" />
      <label [for]="'plan-form-every-' + personId()">Every (days)</label>
      <input [id]="'plan-form-every-' + personId()" data-testid="plan-form-every" formControlName="every" inputmode="numeric" />
      <label [for]="'plan-form-day-' + personId()">Or monthly on day</label>
      <input [id]="'plan-form-day-' + personId()" data-testid="plan-form-day" formControlName="day" inputmode="numeric" />
      <label [for]="'plan-form-end-' + personId()">End</label>
      <input type="date" [id]="'plan-form-end-' + personId()" data-testid="plan-form-end" formControlName="end" />
      <button type="submit" data-testid="plan-form-submit">Add plan</button>
      @if (error()) {
        <p role="alert" class="error" data-testid="plan-error">{{ error() }}</p>
      }
    </form>
  `,
})
export class Plans {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  /** The person's plans, in the order they were added. */
  readonly plans = input.required<Plan[]>();
  /** A plan added from the form. */
  readonly added = output<Plan>();

  protected readonly error = signal('');
  protected readonly form = new FormGroup({
    amount: new FormControl('', { nonNullable: true }),
    start: new FormControl('', { nonNullable: true }),
    every: new FormControl('', { nonNullable: true }),
    day: new FormControl('', { nonNullable: true }),
    end: new FormControl('', { nonNullable: true }),
  });
  private submitting = false;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      this.error.set('');
      this.form.reset({ amount: '', start: '', every: '', day: '', end: '' }, { emitEvent: false });
    });
  }

  protected async add(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const v = this.form.getRawValue();
    const amount = v.amount.trim();
    const every = v.every.trim();
    const day = v.day.trim();
    const start = v.start || this.session.today() || '';
    const end = v.end;
    if (!AMOUNT.test(amount)) {
      this.error.set('Enter an amount of 0.00 or more with at most two decimals.');
      return;
    }
    if ((every === '') === (day === '')) {
      this.error.set('Enter either every how many days or a day of the month, not both.');
      return;
    }
    if (every !== '' && (!EVERY.test(every) || Number(every) < 1)) {
      this.error.set('Every must be a whole number of days, at least 1.');
      return;
    }
    if (day !== '' && (!DAY.test(day) || Number(day) < 1 || Number(day) > 31)) {
      this.error.set('The day of the month must be a whole number from 1 to 31.');
      return;
    }
    if (!DATE.test(start) || !DATE.test(end)) {
      this.error.set('Enter a start date and an end date.');
      return;
    }
    if (end < start) {
      this.error.set('The end date cannot be before the start date.');
      return;
    }
    this.submitting = true;
    try {
      const plan = await firstValueFrom(
        this.http.post<Plan>(
          `/api/sheets/${encodeURIComponent(sheet)}/people/${this.personId()}/plans`,
          { amount, start, every: every === '' ? null : Number(every), day: day === '' ? null : Number(day), end },
          { headers: { 'X-User': this.session.user() ?? '' } },
        ),
      );
      if (this.session.sheetId() !== sheet) return;
      this.added.emit(plan);
      this.form.reset({ amount: '', start: '', every: '', day: '', end: '' }, { emitEvent: false });
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 403
          ? 'You may not change this sheet.'
          : 'The plan was refused: check its amount, interval and dates.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
