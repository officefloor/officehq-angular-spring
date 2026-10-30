import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, Injectable, computed, effect, inject, input, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

const AMOUNT = /^\d{1,12}(\.\d{1,2})?$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

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
  async add(personId: number, amount: string, start: string, every: string, day: string, end: string): Promise<string> {
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
  template: `
    <ul aria-label="Plans">
      @for (pl of plans(); track pl.id) {
        <li [attr.data-testid]="'plan-row-' + pl.id">
          <span>Plan <span data-testid="plan-amount-shown">{{ pl.amount }}</span></span>
          <span>from <span data-testid="plan-start">{{ pl.start }}</span></span>
          @if (pl.day === null) {
            <span>every <span data-testid="plan-every">{{ pl.every }}</span> days</span>
          } @else {
            <span>monthly on day <span data-testid="plan-day">{{ pl.day }}</span></span>
          }
          <span>to <span data-testid="plan-end">{{ pl.end }}</span></span>
          <span>Made <span data-testid="plan-generated">{{ pl.generated }}</span></span>
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
      <button type="submit" data-testid="plan-form-submit">Add plan<span class="visually-hidden"> for {{ name() }}</span></button>
      @if (error()) {
        <p role="alert" data-testid="plan-error">{{ error() }}</p>
      }
    </form>
  `,
})
export class PersonPlans {
  private readonly list = inject(PlanList);

  readonly personId = input.required<number>();
  readonly name = input('');

  protected readonly plans = computed(() => this.list.plans().filter((p) => p.personId === this.personId()));
  protected readonly amount = signal('');
  protected readonly start = signal('');
  protected readonly every = signal('');
  protected readonly day = signal('');
  protected readonly end = signal('');
  protected readonly error = signal('');
  /** A submit still waiting for its answer: another press meanwhile is ignored. */
  private busy = false;

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async add(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.error.set('');
    this.busy = true;
    try {
      const refusal = await this.list.add(this.personId(), this.amount(), this.start(), this.every(), this.day(), this.end());
      if (refusal) {
        this.error.set(refusal);
      } else {
        this.amount.set('');
        this.start.set('');
        this.every.set('');
        this.day.set('');
        this.end.set('');
      }
    } finally {
      this.busy = false;
    }
  }
}
