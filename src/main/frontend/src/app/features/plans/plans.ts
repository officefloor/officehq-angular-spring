import { HttpClient } from '@angular/common/http';
import { Component, Injectable, computed, effect, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { AMOUNT, Claim } from '../claims/claims';

export interface Plan {
  id: number;
  personId: number;
  amount: string;
  start: string;
  /** Every so many days after the start, a plan date; null for a monthly plan. */
  every: number | null;
  /** The day of the month (1 to 31) a monthly plan runs on; null for one that runs every so many days. */
  day: number | null;
  end: string;
  /** How many claims the plan has made so far. */
  generated: number;
}

const DATE = /^\d{4}-\d{2}-\d{2}$/;
const EVERY = /^\d{1,6}$/;
const DAY = /^\d{1,2}$/;

// The active sheet's plans in the order they were added, re-read whenever a sheet is loaded.
@Injectable({ providedIn: 'root' })
export class PlanStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly plans = signal<Plan[]>([]);

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      const load = this.session.loads();
      this.plans.set([]);
      if (sheet) void this.read(sheet, load);
    });
  }

  /** Add a plan for a person; the server refuses a bad amount, interval or day of the month, or an end
   *  before the start. */
  async add(
    personId: number,
    fields: { amount: string; start: string | null; every: string | null; day: string | null; end: string },
  ): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.post<Plan>(`/api/sheets/${encodeURIComponent(sheet)}/plans`, { personId, ...fields }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.plans.update((list) => [...list.filter((p) => p.id !== saved.id), saved]);
  }

  /** Make every plan's claims due by the sheet's today; gives back the claims made. */
  async generate(): Promise<Claim[]> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const result = await firstValueFrom(
      this.http.post<{ made: Claim[]; plans: Plan[] }>(`/api/sheets/${encodeURIComponent(sheet)}/plans/generate`, {}),
    );
    if (this.session.sheetId() !== sheet) return [];
    this.plans.set(result.plans);
    return result.made;
  }

  private async read(sheet: string, load: number): Promise<void> {
    const list = await firstValueFrom(this.http.get<Plan[]>(`/api/sheets/${encodeURIComponent(sheet)}/plans`));
    if (this.session.loads() !== load) return;
    this.plans.set(list);
  }
}

// A person's plans, each with how many claims it has made, and the form that adds one.
@Component({
  selector: 'app-person-plans',
  template: `
    <ul [attr.aria-label]="'Plans of ' + name()">
      @for (pl of plans(); track pl.id) {
        <li [attr.data-testid]="'plan-row-' + pl.id">
          <span data-testid="plan-amount-shown">{{ pl.amount }}</span>
          from <span data-testid="plan-start">{{ pl.start }}</span>
          @if (pl.day !== null) {
            monthly on day <span data-testid="plan-day">{{ pl.day }}</span>
          } @else {
            every <span data-testid="plan-every">{{ pl.every }}</span> days
          }
          to <span data-testid="plan-end">{{ pl.end }}</span>;
          made <span data-testid="plan-generated">{{ pl.generated }}</span>
        </li>
      }
    </ul>
    <form (submit)="submit($event)" [attr.aria-label]="'Add a plan for ' + name()">
      <label [for]="'plan-form-amount-' + personId()">Plan amount</label>
      <input
        [id]="'plan-form-amount-' + personId()"
        data-testid="plan-form-amount"
        inputmode="decimal"
        [value]="amount()"
        (input)="amount.set(value($event)); error.set(null)"
      />
      <label [for]="'plan-form-start-' + personId()">Start</label>
      <input
        type="date"
        [id]="'plan-form-start-' + personId()"
        data-testid="plan-form-start"
        [value]="start()"
        (input)="start.set(value($event)); error.set(null)"
      />
      <label [for]="'plan-form-every-' + personId()">Every (days)</label>
      <input
        [id]="'plan-form-every-' + personId()"
        data-testid="plan-form-every"
        inputmode="numeric"
        [value]="every()"
        (input)="every.set(value($event)); error.set(null)"
      />
      <label [for]="'plan-form-day-' + personId()">Or monthly on day</label>
      <input
        [id]="'plan-form-day-' + personId()"
        data-testid="plan-form-day"
        inputmode="numeric"
        [value]="day()"
        (input)="day.set(value($event)); error.set(null)"
      />
      <label [for]="'plan-form-end-' + personId()">End</label>
      <input
        type="date"
        [id]="'plan-form-end-' + personId()"
        data-testid="plan-form-end"
        [value]="end()"
        (input)="end.set(value($event)); error.set(null)"
      />
      <button type="submit" data-testid="plan-form-submit">Add plan</button>
      @if (error(); as message) {
        <p role="alert" data-testid="plan-error">{{ message }}</p>
      }
    </form>
  `,
})
export class PersonPlans {
  private readonly store = inject(PlanStore);
  readonly personId = input.required<number>();
  readonly name = input.required<string>();
  protected readonly plans = computed(() => this.store.plans().filter((p) => p.personId === this.personId()));
  protected readonly amount = signal('');
  protected readonly start = signal('');
  protected readonly every = signal('');
  protected readonly day = signal('');
  protected readonly end = signal('');
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer; a second submit meanwhile is dropped. */
  private readonly busy = signal(false);

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  /** Add the typed plan, monthly when a day of the month is typed; a bad amount, interval or day, both
   *  an interval and a day, or an end before the start, is refused on the row. */
  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy()) return;
    this.error.set(null);
    const amount = this.amount().trim();
    const start = this.start().trim();
    const every = this.every().trim();
    const day = this.day().trim();
    const end = this.end().trim();
    const refused =
      'The plan could not be added: it needs an amount of 0.00 or more with up to two decimals, ' +
      'either every so many days (a whole number, at least 1) or a day of the month (1 to 31), ' +
      'and an end on or after its start.';
    const runs =
      day !== ''
        ? every === '' && DAY.test(day) && Number(day) >= 1 && Number(day) <= 31
        : EVERY.test(every) && Number(every) >= 1;
    if (!AMOUNT.test(amount) || !runs || !DATE.test(end) || (start !== '' && !DATE.test(start))) {
      this.error.set(refused);
      return;
    }
    this.busy.set(true);
    try {
      await this.store.add(this.personId(), {
        amount,
        start: start || null,
        every: day === '' ? every : null,
        day: day === '' ? null : day,
        end,
      });
      this.amount.set('');
      this.start.set('');
      this.every.set('');
      this.day.set('');
      this.end.set('');
    } catch {
      this.error.set(refused);
    } finally {
      this.busy.set(false);
    }
  }
}

// The button that makes every plan's claims due by the sheet's today, and how many it made.
@Component({
  selector: 'app-plan-generate',
  template: `
    <p>
      <button type="button" data-testid="plan-generate" (click)="generate()">Generate claims from plans</button>
      @if (made() !== null) {
        Made <strong data-testid="plan-generated-count">{{ made() }}</strong>
      }
    </p>
    @if (error(); as message) {
      <p role="alert" data-testid="plan-generate-error">{{ message }}</p>
    }
  `,
})
export class PlanGenerate {
  private readonly store = inject(PlanStore);
  private readonly session = inject(Session);
  /** The claims a generate made, for the screen to list. */
  readonly generated = output<Claim[]>();
  /** How many claims the last generate made; null while it runs or before one. */
  protected readonly made = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  private readonly busy = signal(false);

  constructor() {
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      this.made.set(null);
      this.error.set(null);
    });
  }

  protected async generate(): Promise<void> {
    if (this.busy()) return;
    this.made.set(null);
    this.error.set(null);
    this.busy.set(true);
    try {
      const claims = await this.store.generate();
      this.generated.emit(claims);
      this.made.set(claims.length);
    } catch {
      this.error.set('The plans could not generate their claims.');
    } finally {
      this.busy.set(false);
    }
  }
}
