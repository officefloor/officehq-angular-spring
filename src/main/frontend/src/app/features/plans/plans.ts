import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, Injectable, computed, effect, inject, input, signal } from '@angular/core';
import { FigurePipe } from '../../core/figure';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

const AMOUNT = /^\d{1,12}(\.\d{1,2})?$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
/** How many of a plan's next dates are shown. */
const NEXT_DATES = 5;

/** A yyyy-mm-dd date as days since the epoch. */
function toDays(date: string): number {
  const [y, m, d] = date.split('-').map(Number);
  return Date.UTC(y, m - 1, d) / 86_400_000;
}

/** Days since the epoch as a yyyy-mm-dd date. */
function fromDays(days: number): string {
  return new Date(days * 86_400_000).toISOString().slice(0, 10);
}

/**
 * The plan's first dates after the day and on or before its end, at most the limit, in order: the start then every N
 * days, or that day of every month from the start's month (on the month's last day when it is shorter, dates before
 * the start left out). A date within one of the plan's pauses is left out; a plan still paused has none.
 */
export function nextPlanDates(plan: Plan, after: string, limit = NEXT_DATES): string[] {
  if (plan.paused) return [];
  const skipped = (on: number) =>
    plan.pauses.some((p) => on >= toDays(p.from) && (p.to === null || on < toDays(p.to)));
  const start = toDays(plan.start);
  const end = toDays(plan.end);
  const from = toDays(after);
  const dates: string[] = [];
  if (plan.day === null) {
    const every = plan.every ?? 1;
    let on = from < start ? start : start + (Math.floor((from - start) / every) + 1) * every;
    for (; on <= end && dates.length < limit; on += every) if (!skipped(on)) dates.push(fromDays(on));
    return dates;
  }
  const [y, m] = plan.start.split('-').map(Number);
  for (let month = y * 12 + (m - 1); dates.length < limit; month++) {
    const year = Math.floor(month / 12);
    const length = new Date(Date.UTC(year, (month % 12) + 1, 0)).getUTCDate();
    const on = Date.UTC(year, month % 12, Math.min(plan.day, length)) / 86_400_000;
    if (on > end) break;
    if (on >= start && on > from && !skipped(on)) dates.push(fromDays(on));
  }
  return dates;
}

/**
 * A person's plan: the amount (two decimals) claimed every N days, or monthly on a day of the month, from the start up
 * to the end.
 */
export interface Plan {
  id: number;
  personId: number;
  amount: string;
  start: string;
  /** Every how many days, or null for a monthly plan. */
  every: number | null;
  /** The day of the month (1 to 31) a monthly plan runs on, or null. */
  day: number | null;
  end: string;
  /** How many claims the plan has made so far. */
  generated: number;
  /** Whether the plan is paused now. */
  paused: boolean;
  /** The descendant sheet the plan's claims are made on, or null for the plan's own sheet. */
  sheet: string | null;
  /** The plan's pauses: from the day paused up to the day resumed (null while still paused). */
  pauses: { from: string; to: string | null }[];
  /** The plan's amount changes, oldest first: the day made and the new amount (two decimals). */
  changes: { on: string; amount: string }[];
}

/** The active sheet's plans in the order they were added, re-read on every sheet load. */
@Injectable({ providedIn: 'root' })
export class PlanList {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly plans = signal<Plan[]>([]);

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.plans.set([]);
      if (sheet) void this.reload(sheet);
    });
  }

  /** Add a plan to the person, every N days or on a day of the month: '' once added, else why it was refused. */
  async add(personId: number, amount: string, start: string, every: string, day: string, end: string, onSheet = ''): Promise<string> {
    const sheet = this.session.sheet();
    if (!sheet) return 'Load a sheet first.';
    if (!AMOUNT.test(amount.trim())) return 'Enter an amount with up to two decimals.';
    if (!DATE.test(start.trim())) return 'Enter a start date.';
    if ((every.trim() === '') === (day.trim() === '')) return 'Enter either every how many days or a day of the month.';
    if (every.trim() && (!/^\d{1,6}$/.test(every.trim()) || Number(every.trim()) < 1))
      return 'Enter every how many days, at least 1.';
    if (day.trim() && (!/^\d{1,2}$/.test(day.trim()) || Number(day.trim()) < 1 || Number(day.trim()) > 31))
      return 'Enter a day of the month from 1 to 31.';
    if (!DATE.test(end.trim())) return 'Enter an end date.';
    if (end.trim() < start.trim()) return 'The end cannot be before the start.';
    const headers = { 'X-User': this.session.user() ?? '' };
    try {
      const plans = await firstValueFrom(
        this.http.post<Plan[]>(
          `/api/sheets/${encodeURIComponent(sheet)}/people/${personId}/plans`,
          {
            amount: amount.trim(),
            start: start.trim(),
            every: every.trim() || null,
            day: day.trim() || null,
            end: end.trim(),
            sheet: onSheet.trim() || null,
          },
          { headers },
        ),
      );
      if (this.session.sheet() === sheet) this.plans.set(plans);
      return '';
    } catch (err) {
      const answer = err instanceof HttpErrorResponse ? err : null;
      return answer?.error?.message ?? 'Could not add the plan.';
    }
  }

  /** Pause the plan, or resume it: '' once done, else why it was refused. */
  async pause(planId: number, resume: boolean): Promise<string> {
    const sheet = this.session.sheet();
    if (!sheet) return 'Load a sheet first.';
    const headers = { 'X-User': this.session.user() ?? '' };
    try {
      const plans = await firstValueFrom(
        this.http.post<Plan[]>(
          `/api/sheets/${encodeURIComponent(sheet)}/plans/${planId}/${resume ? 'resume' : 'pause'}`,
          {},
          { headers },
        ),
      );
      if (this.session.sheet() === sheet) this.plans.set(plans);
      return '';
    } catch (err) {
      const answer = err instanceof HttpErrorResponse ? err : null;
      return answer?.error?.message ?? (resume ? 'Could not resume the plan.' : 'Could not pause the plan.');
    }
  }

  /** Change the plan's amount from now on: '' once changed, else why it was refused. */
  async changeAmount(planId: number, amount: string): Promise<string> {
    const sheet = this.session.sheet();
    if (!sheet) return 'Load a sheet first.';
    if (!AMOUNT.test(amount.trim())) return 'Enter an amount with up to two decimals.';
    const headers = { 'X-User': this.session.user() ?? '' };
    try {
      const plans = await firstValueFrom(
        this.http.post<Plan[]>(
          `/api/sheets/${encodeURIComponent(sheet)}/plans/${planId}/amount`,
          { amount: amount.trim() },
          { headers },
        ),
      );
      if (this.session.sheet() === sheet) this.plans.set(plans);
      return '';
    } catch (err) {
      const answer = err instanceof HttpErrorResponse ? err : null;
      return answer?.error?.message ?? 'Could not change the plan amount.';
    }
  }

  /** Make the claims every plan is due up to the sheet's today: how many were made. Throws when refused. */
  async generate(sheet: string): Promise<number> {
    const headers = { 'X-User': this.session.user() ?? '' };
    const result = await firstValueFrom(
      this.http.post<{ made: number }>(`/api/sheets/${encodeURIComponent(sheet)}/plans/generate`, {}, { headers }),
    );
    await this.reload(sheet);
    return result.made;
  }

  private async reload(sheet: string): Promise<void> {
    const loads = this.session.loads();
    const plans = await firstValueFrom(this.http.get<Plan[]>(`/api/sheets/${encodeURIComponent(sheet)}/plans`));
    if (this.session.sheet() === sheet && this.session.loads() === loads) this.plans.set(plans);
  }
}

// A person's plans, each with how many claims it has made so far, and the form that adds one.
@Component({
  selector: 'app-person-plans',
  imports: [FigurePipe],
  template: `
    <ul aria-label="Plans">
      @for (pl of plans(); track pl.id) {
        <li [attr.data-testid]="'plan-row-' + pl.id">
          <span>Plan <span data-testid="plan-amount-shown">{{ pl.amount | figure }}</span></span>
          <span>from <span data-testid="plan-start">{{ pl.start }}</span></span>
          @if (pl.day === null) {
            <span>every <span data-testid="plan-every">{{ pl.every }}</span> days</span>
          } @else {
            <span>monthly on day <span data-testid="plan-day">{{ pl.day }}</span></span>
          }
          <span>to <span data-testid="plan-end">{{ pl.end }}</span></span>
          @if (pl.sheet) {
            <span>on sheet <span data-testid="plan-sheet">{{ pl.sheet }}</span></span>
          }
          <span>Made <span data-testid="plan-generated">{{ pl.generated }}</span></span>
          <span>Next <span data-testid="plan-next-dates">{{ nextDates(pl) }}</span></span>
          <span>Paused <span data-testid="plan-paused">{{ pl.paused ? 'yes' : 'no' }}</span></span>
          <button type="button" data-testid="plan-pause" (click)="pause(pl.id, false)">
            Pause<span class="visually-hidden"> plan from {{ pl.start }}</span>
          </button>
          <button type="button" data-testid="plan-resume" (click)="pause(pl.id, true)">
            Resume<span class="visually-hidden"> plan from {{ pl.start }}</span>
          </button>
          <label [for]="'plan-amount-' + pl.id">New amount<span class="visually-hidden"> for plan from {{ pl.start }}</span></label>
          <input
            [id]="'plan-amount-' + pl.id"
            data-testid="plan-amount"
            inputmode="decimal"
            [value]="newAmounts()[pl.id] ?? ''"
            (input)="setNewAmount(pl.id, value($event))"
          />
          <button type="button" data-testid="plan-amount-save" (click)="changeAmount(pl.id)">
            Save amount<span class="visually-hidden"> for plan from {{ pl.start }}</span>
          </button>
          <ul aria-label="Amount changes">
            @for (c of pl.changes; track $index) {
              <li data-testid="plan-change-line">{{ c.on }} amount={{ c.amount }}</li>
            }
          </ul>
          @if (rowErrors()[pl.id]) {
            <span role="alert" data-testid="plan-error">{{ rowErrors()[pl.id] }}</span>
          }
        </li>
      }
    </ul>
    <form (submit)="add($event)" [attr.aria-label]="'Add plan for ' + name()">
      <label [for]="'plan-form-amount-' + personId()">Plan amount</label>
      <input
        [id]="'plan-form-amount-' + personId()"
        data-testid="plan-form-amount"
        inputmode="decimal"
        [value]="amount()"
        (input)="amount.set(value($event)); error.set('')"
      />
      <label [for]="'plan-form-start-' + personId()">Start</label>
      <input
        type="date"
        [id]="'plan-form-start-' + personId()"
        data-testid="plan-form-start"
        [value]="start()"
        (input)="start.set(value($event)); error.set('')"
      />
      <label [for]="'plan-form-every-' + personId()">Every (days)</label>
      <input
        [id]="'plan-form-every-' + personId()"
        data-testid="plan-form-every"
        inputmode="numeric"
        [value]="every()"
        (input)="every.set(value($event)); error.set('')"
      />
      <label [for]="'plan-form-day-' + personId()">Or monthly on day</label>
      <input
        [id]="'plan-form-day-' + personId()"
        data-testid="plan-form-day"
        inputmode="numeric"
        [value]="day()"
        (input)="day.set(value($event)); error.set('')"
      />
      <label [for]="'plan-form-end-' + personId()">End</label>
      <input
        type="date"
        [id]="'plan-form-end-' + personId()"
        data-testid="plan-form-end"
        [value]="end()"
        (input)="end.set(value($event)); error.set('')"
      />
      <label [for]="'plan-form-sheet-' + personId()">On descendant sheet (optional)</label>
      <input
        [id]="'plan-form-sheet-' + personId()"
        data-testid="plan-form-sheet"
        [value]="onSheet()"
        (input)="onSheet.set(value($event)); error.set('')"
      />
      <button type="submit" data-testid="plan-form-submit">Add plan<span class="visually-hidden"> for {{ name() }}</span></button>
      @if (error()) {
        <p role="alert" data-testid="plan-error">{{ error() }}</p>
      }
    </form>
  `,
})
export class PersonPlans {
  private readonly list = inject(PlanList);
  private readonly session = inject(Session);

  readonly personId = input.required<number>();
  readonly name = input('');

  protected readonly plans = computed(() => this.list.plans().filter((p) => p.personId === this.personId()));
  protected readonly amount = signal('');
  protected readonly start = signal('');
  protected readonly every = signal('');
  protected readonly day = signal('');
  protected readonly end = signal('');
  protected readonly onSheet = signal('');
  protected readonly error = signal('');
  /** Why the last action on a plan's row was refused, by plan id. */
  protected readonly rowErrors = signal<Record<number, string>>({});
  /** The new amount typed on each plan's row, by plan id. */
  protected readonly newAmounts = signal<Record<number, string>>({});
  /** A submit still waiting for its answer: another press meanwhile is ignored. */
  private busy = false;

  /** The plan's next dates after the sheet's today, comma and space separated. */
  protected nextDates(plan: Plan): string {
    const today = this.session.today();
    return today ? nextPlanDates(plan, today).join(', ') : '';
  }

  /** Pause or resume the plan, showing on its row why when refused. */
  protected async pause(planId: number, resume: boolean): Promise<void> {
    this.rowErrors.update((errors) => ({ ...errors, [planId]: '' }));
    const refusal = await this.list.pause(planId, resume);
    this.rowErrors.update((errors) => ({ ...errors, [planId]: refusal }));
  }

  protected setNewAmount(planId: number, amount: string): void {
    this.newAmounts.update((amounts) => ({ ...amounts, [planId]: amount }));
    this.rowErrors.update((errors) => ({ ...errors, [planId]: '' }));
  }

  /** Change the plan's amount to the one typed, showing on its row why when refused. */
  protected async changeAmount(planId: number): Promise<void> {
    this.rowErrors.update((errors) => ({ ...errors, [planId]: '' }));
    const refusal = await this.list.changeAmount(planId, this.newAmounts()[planId] ?? '');
    this.rowErrors.update((errors) => ({ ...errors, [planId]: refusal }));
    if (!refusal) this.newAmounts.update((amounts) => ({ ...amounts, [planId]: '' }));
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async add(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.error.set('');
    this.busy = true;
    try {
      const refusal = await this.list.add(this.personId(), this.amount(), this.start(), this.every(), this.day(), this.end(), this.onSheet());
      if (refusal) {
        this.error.set(refusal);
      } else {
        this.amount.set('');
        this.start.set('');
        this.every.set('');
        this.day.set('');
        this.end.set('');
        this.onSheet.set('');
      }
    } finally {
      this.busy = false;
    }
  }
}
