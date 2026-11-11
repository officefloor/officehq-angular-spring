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
  /** The descendant sheet the plan's claims are made on, or null for this sheet. */
  target: string | null;
  generated: number;
  /** The first five plan dates after the sheet's today and on or before the end, comma separated. */
  nextDates: string;
  /** Whether the plan is paused now: its dates from the pause until a resume are skipped. */
  paused: boolean;
  /** The changes of the amount, oldest first, each as `<date> amount=<amount>`. */
  changes: string[];
}

// A person's plans and the form on their row that adds one: an amount, a start date, either every N
// days (a whole number, at least 1) or monthly on a day of the month (1 to 31), and an end date not
// before the start, and optionally one of the sheet's descendants whose same-named person the claims
// are made for, on that sheet. The sheet's generate button
// (on the people screen) makes each plan's DRAFT claims; each plan shows how many it has made,
// and its next five dates after the sheet's today. A plan can be paused and resumed from its row;
// the dates while it is paused are skipped, not postponed. Its amount can be changed from its row: the
// claims generated from then on take the new amount; the plan lists its changes, oldest first.
@Component({
  selector: 'app-person-plans',
  imports: [],
  template: `
    <table [attr.aria-label]="'Plans of ' + personName()">
      <thead>
        <tr>
          <th scope="col">Amount</th><th scope="col">Start</th><th scope="col">Every (days)</th>
          <th scope="col">Day of month</th>
          <th scope="col">End</th><th scope="col">Sheet</th><th scope="col">Made</th>
          <th scope="col">Next dates</th>
          <th scope="col">Paused</th><th scope="col">Pause or resume</th>
          <th scope="col">Changes</th>
        </tr>
      </thead>
      <tbody>
        @for (pl of plans(); track pl.id) {
          <tr [attr.data-testid]="'plan-row-' + pl.id">
            <td>
              <label class="visually-hidden" [for]="'plan-amount-' + pl.id">Amount</label>
              <input
                [id]="'plan-amount-' + pl.id"
                data-testid="plan-amount"
                inputmode="decimal"
                [value]="amountDrafts()[pl.id] ?? pl.amount"
                (input)="draftAmount(pl, $any($event.target).value)"
              />
              <button type="button" data-testid="plan-amount-save" (click)="changeAmount(pl)">Save amount</button>
            </td>
            <td data-testid="plan-start">{{ pl.start }}</td>
            <td data-testid="plan-every">{{ pl.every }}</td>
            <td data-testid="plan-day">{{ pl.day }}</td>
            <td data-testid="plan-end">{{ pl.end }}</td>
            <td data-testid="plan-sheet">{{ pl.target }}</td>
            <td data-testid="plan-generated">{{ pl.generated }}</td>
            <td data-testid="plan-next-dates">{{ pl.nextDates }}</td>
            <td data-testid="plan-paused">{{ pl.paused ? 'yes' : 'no' }}</td>
            <td>
              <button type="button" data-testid="plan-pause" (click)="pauseOrResume(pl, true)">Pause</button>
              <button type="button" data-testid="plan-resume" (click)="pauseOrResume(pl, false)">Resume</button>
              @if (rowError()?.planId === pl.id) {
                <p role="alert" data-testid="plan-error">{{ rowError()?.message }}</p>
              }
            </td>
            <td>
              <ul>
                @for (line of pl.changes; track $index) {
                  <li data-testid="plan-change-line">{{ line }}</li>
                }
              </ul>
            </td>
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
      <label [for]="'plan-form-sheet-' + personId()">On sheet (a descendant, optional)</label>
      <input
        [id]="'plan-form-sheet-' + personId()"
        data-testid="plan-form-sheet"
        [value]="sheetInput()"
        (input)="sheetInput.set($any($event.target).value); error.set(null)"
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
  /** A plan paused or resumed, to replace where it is. */
  readonly changed = output<Plan>();

  protected readonly amountInput = signal('');
  protected readonly startInput = signal('');
  protected readonly everyInput = signal('');
  protected readonly dayInput = signal('');
  protected readonly endInput = signal('');
  protected readonly sheetInput = signal('');
  protected readonly error = signal<string | null>(null);
  /** The error of the last pause or resume refused, on the plan's row. */
  protected readonly rowError = signal<{ planId: number; message: string } | null>(null);
  /** The amounts typed on the plans' rows and not yet saved, by plan id. */
  protected readonly amountDrafts = signal<Record<number, string>>({});
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
      this.sheetInput.set('');
      this.error.set(null);
      this.rowError.set(null);
      this.amountDrafts.set({});
    });
  }

  protected draftAmount(plan: Plan, value: string): void {
    this.amountDrafts.update((drafts) => ({ ...drafts, [plan.id]: value }));
    if (this.rowError()?.planId === plan.id) this.rowError.set(null);
  }

  protected async changeAmount(plan: Plan): Promise<void> {
    if (this.submitting) return;
    this.rowError.set(null);
    const amount = (this.amountDrafts()[plan.id] ?? plan.amount).trim();
    if (!AMOUNT.test(amount)) {
      this.rowError.set({ planId: plan.id, message: 'Enter an amount of 0.00 or more with up to two decimals.' });
      return;
    }
    const sheet = this.session.sheet();
    this.submitting = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<Plan>('/api/plans/amount', {
          sheet,
          user: this.session.user(),
          planId: plan.id,
          amount,
        }),
      );
      if (this.session.sheet() !== sheet) return;
      this.amountDrafts.update((drafts) => {
        const rest = { ...drafts };
        delete rest[plan.id];
        return rest;
      });
      this.changed.emit(saved);
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      this.rowError.set({
        planId: plan.id,
        message:
          e instanceof HttpErrorResponse && e.status === 403
            ? 'Your role may not change plans.'
            : 'Could not change this plan.',
      });
    } finally {
      this.submitting = false;
    }
  }

  protected async pauseOrResume(plan: Plan, pause: boolean): Promise<void> {
    if (this.submitting) return;
    this.rowError.set(null);
    const sheet = this.session.sheet();
    this.submitting = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<Plan>(pause ? '/api/plans/pause' : '/api/plans/resume', {
          sheet,
          user: this.session.user(),
          planId: plan.id,
        }),
      );
      if (this.session.sheet() !== sheet) return;
      this.changed.emit(saved);
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      this.rowError.set({
        planId: plan.id,
        message:
          e instanceof HttpErrorResponse && e.status === 403
            ? 'Your role may not pause or resume plans.'
            : e instanceof HttpErrorResponse && e.status === 409
              ? pause
                ? 'This plan is already paused.'
                : 'This plan is not paused.'
              : 'Could not change this plan.',
      });
    } finally {
      this.submitting = false;
    }
  }

  protected async add(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    this.rowError.set(null);
    const amount = this.amountInput().trim();
    const start = this.startInput().trim();
    const every = this.everyInput().trim();
    const day = this.dayInput().trim();
    const end = this.endInput().trim();
    const target = this.sheetInput().trim();
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
          target: target || null,
        }),
      );
      if (this.session.sheet() !== sheet) return;
      this.added.emit(plan);
      this.amountInput.set('');
      this.startInput.set('');
      this.everyInput.set('');
      this.dayInput.set('');
      this.endInput.set('');
      this.sheetInput.set('');
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 403
          ? 'Your role may not add plans.'
          : e instanceof HttpErrorResponse && e.status === 400 && target
            ? 'Sheet ' + target + ' is not a descendant of this sheet.'
            : 'Could not add this plan.',
      );
    } finally {
      this.submitting = false;
    }
  }
}

/** An amount the claim form takes without a correction: 0.00 or more, up to two decimals. */
const AMOUNT = /^\d{1,12}(\.\d{1,2})?$/;
