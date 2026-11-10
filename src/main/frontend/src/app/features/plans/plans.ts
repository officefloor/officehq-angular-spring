import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A person's plan, with how many claims it has made so far. */
export interface Plan {
  id: number;
  personId: number;
  amount: string;
  start: string;
  /** Every how many days, or null for a monthly plan. */
  every: number | null;
  /** The day of the month of a monthly plan, or null. */
  day: number | null;
  end: string;
  generated: number;
  /** The first five plan dates after the sheet's today and on or before the end, comma separated. */
  nextDates: string;
}

// A person's plans and the form on their row that adds one: an amount, a start date, either every N
// days (a whole number, at least 1) or monthly on a day of the month (1 to 31), and an end date not
// before the start. The sheet's generate button
// (on the people screen) makes each plan's DRAFT claims; each plan shows how many it has made,
// and its next five dates after the sheet's today.
@Component({
  selector: 'app-person-plans',
  template: `
    <table [attr.aria-label]="'Plans of ' + personName()">
      <thead>
        <tr>
          <th scope="col">Amount</th><th scope="col">Start</th><th scope="col">Every (days)</th>
          <th scope="col">Day of month</th>
          <th scope="col">End</th><th scope="col">Made</th>
          <th scope="col">Next dates</th>
        </tr>
      </thead>
      <tbody>
        @for (pl of plans(); track pl.id) {
          <tr [attr.data-testid]="'plan-row-' + pl.id">
            <td data-testid="plan-amount">{{ pl.amount }}</td>
            <td data-testid="plan-start">{{ pl.start }}</td>
            <td data-testid="plan-every">{{ pl.every }}</td>
            <td data-testid="plan-day">{{ pl.day }}</td>
            <td data-testid="plan-end">{{ pl.end }}</td>
            <td data-testid="plan-generated">{{ pl.generated }}</td>
            <td data-testid="plan-next-dates">{{ pl.nextDates }}</td>
          </tr>
        }
      </tbody>
    </table>
    <form [attr.aria-label]="'Add a plan for ' + personName()" (submit)="add($event)">
      <label [for]="'plan-form-amount-' + personId()">Plan amount</label>
      <input
        [id]="'plan-form-amount-' + personId()"
        data-testid="plan-form-amount"
        inputmode="decimal"
        [value]="amountInput()"
        (input)="amountInput.set($any($event.target).value); error.set(null)"
      />
      <label [for]="'plan-form-start-' + personId()">Start</label>
      <input
        [id]="'plan-form-start-' + personId()"
        type="date"
        data-testid="plan-form-start"
        [value]="startInput()"
        (input)="startInput.set($any($event.target).value); error.set(null)"
      />
      <label [for]="'plan-form-every-' + personId()">Every (days)</label>
      <input
        [id]="'plan-form-every-' + personId()"
        data-testid="plan-form-every"
        inputmode="numeric"
        [value]="everyInput()"
        (input)="everyInput.set($any($event.target).value); error.set(null)"
      />
      <label [for]="'plan-form-day-' + personId()">Or monthly on day</label>
      <input
        [id]="'plan-form-day-' + personId()"
        data-testid="plan-form-day"
        inputmode="numeric"
        [value]="dayInput()"
        (input)="dayInput.set($any($event.target).value); error.set(null)"
      />
      <label [for]="'plan-form-end-' + personId()">End</label>
      <input
        [id]="'plan-form-end-' + personId()"
        type="date"
        data-testid="plan-form-end"
        [value]="endInput()"
        (input)="endInput.set($any($event.target).value); error.set(null)"
      />
      <button type="submit" data-testid="plan-form-submit">Add plan</button>
      @if (error(); as err) {
        <p role="alert" data-testid="plan-error">{{ err }}</p>
      }
    </form>
  `,
})
export class PersonPlans {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  readonly plans = input.required<Plan[]>();
  readonly added = output<Plan>();

  protected readonly amountInput = signal('');
  protected readonly startInput = signal('');
  protected readonly everyInput = signal('');
  protected readonly dayInput = signal('');
  protected readonly endInput = signal('');
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;

  constructor() {
    effect(() => {
      this.session.sheet();
      this.session.loads();
      this.amountInput.set('');
      this.startInput.set('');
      this.everyInput.set('');
      this.dayInput.set('');
      this.endInput.set('');
      this.error.set(null);
    });
  }

  protected async add(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const amount = this.amountInput().trim();
    const start = this.startInput().trim();
    const every = this.everyInput().trim();
    const day = this.dayInput().trim();
    const end = this.endInput().trim();
    if (!AMOUNT.test(amount)) {
      this.error.set('Enter an amount of 0.00 or more with up to two decimals.');
      return;
    }
    if (!start || !end) {
      this.error.set('Enter a start date and an end date.');
      return;
    }
    if (!every === !day) {
      this.error.set('Enter either every how many days or a day of the month.');
      return;
    }
    if (every && (!/^\d{1,6}$/.test(every) || Number(every) < 1)) {
      this.error.set('Enter every how many days as a whole number, at least 1.');
      return;
    }
    if (day && (!/^\d{1,2}$/.test(day) || Number(day) < 1 || Number(day) > 31)) {
      this.error.set('Enter the day of the month as a whole number from 1 to 31.');
      return;
    }
    if (end < start) {
      this.error.set("A plan's end cannot be before its start.");
      return;
    }
    const sheet = this.session.sheet();
    this.submitting = true;
    try {
      const plan = await firstValueFrom(
        this.http.post<Plan>('/api/plans/add', {
          sheet,
          user: this.session.user(),
          personId: this.personId(),
          amount,
          start,
          every: every || null,
          day: day || null,
          end,
        }),
      );
      if (this.session.sheet() !== sheet) return;
      this.added.emit(plan);
      this.amountInput.set('');
      this.startInput.set('');
      this.everyInput.set('');
      this.dayInput.set('');
      this.endInput.set('');
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 403
          ? 'Your role may not add plans.'
          : 'Could not add this plan.',
      );
    } finally {
      this.submitting = false;
    }
  }
}

/** An amount the claim form takes without a correction: 0.00 or more, up to two decimals. */
const AMOUNT = /^\d{1,12}(\.\d{1,2})?$/;
