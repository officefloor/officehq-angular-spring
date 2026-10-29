import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, Injectable, computed, inject, input, signal } from '@angular/core';
import { FigPipe } from '../../core/locale';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Claim } from '../claims/claims';

export interface PlanView {
  id: number;
  personId: number;
  amount: string;
  start: string;
  // The interval in days, or null for a plan that runs monthly on `day`.
  every: number | null;
  day: number | null;
  end: string;
  // How many claims the plan has made so far.
  generated: number;
  // The first five plan dates after the sheet's today and on or before the end.
  nextDates: string[];
  // Whether the plan is paused and not resumed yet.
  paused: boolean;
  // The plan's changes of amount, oldest first.
  changes: { on: string; amount: string }[];
}

// The plans of the active sheet, in the order they were added.
@Injectable({ providedIn: 'root' })
export class PlanStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly list = signal<readonly PlanView[]>([]);

  /** Re-read the sheet's plans from the store. */
  async load(sheet: string): Promise<void> {
    this.list.set([]);
    const l = await firstValueFrom(this.http.get<PlanView[]>(`/api/sheets/${encodeURIComponent(sheet)}/plans`));
    if (this.session.sheetId() === sheet) this.list.set(l);
  }

  async add(sheet: string, personId: number, form: { amount: string; start: string; every: number | null; day: number | null; end: string }): Promise<void> {
    const p = await firstValueFrom(
      this.http.post<PlanView>(`/api/sheets/${encodeURIComponent(sheet)}/people/${personId}/plans`, form),
    );
    if (this.session.sheetId() === sheet) this.list.update((l) => [...l.filter((x) => x.id !== p.id), p]);
  }

  /** Pauses (true) or resumes (false) the plan from the sheet's today. */
  async setPaused(sheet: string, planId: number, paused: boolean): Promise<void> {
    const p = await firstValueFrom(
      this.http.post<PlanView>(
        `/api/sheets/${encodeURIComponent(sheet)}/plans/${planId}/${paused ? 'pause' : 'resume'}`,
        null,
      ),
    );
    if (this.session.sheetId() === sheet) this.list.update((l) => l.map((x) => (x.id === p.id ? p : x)));
  }

  /** Changes the plan's amount from the sheet's today. */
  async changeAmount(sheet: string, planId: number, amount: string): Promise<void> {
    const p = await firstValueFrom(
      this.http.post<PlanView>(`/api/sheets/${encodeURIComponent(sheet)}/plans/${planId}/amount`, { amount }),
    );
    if (this.session.sheetId() === sheet) this.list.update((l) => l.map((x) => (x.id === p.id ? p : x)));
  }

  /** Makes the plans' claims up to the sheet's today: the claims made, in the order they were made. */
  async generate(sheet: string): Promise<Claim[]> {
    const g = await firstValueFrom(
      this.http.post<{ claims: Claim[]; plans: PlanView[] }>(`/api/sheets/${encodeURIComponent(sheet)}/plans/generate`, null),
    );
    if (this.session.sheetId() === sheet) this.list.set(g.plans);
    return g.claims;
  }
}

// A person's plans, and the form that adds one, every so many days or monthly on a day of the month. An amount the
// claim form would refuse without a claim to correct, both or neither of an interval and a day, an interval under one
// day, a day outside 1 to 31, or an end before the start is refused.
@Component({
  selector: 'app-person-plans',
  imports: [FigPipe],
  template: `
    <table>
      <caption class="visually-hidden">Plans of {{ personName() }}</caption>
      <thead>
        <tr>
          <th scope="col">Amount</th><th scope="col">Start</th><th scope="col">Every (days)</th>
          <th scope="col">Day of month</th><th scope="col">End</th><th scope="col">Claims made</th>
          <th scope="col">Next dates</th><th scope="col">Paused</th><th scope="col">Changes</th>
          <th scope="col">Actions</th>
        </tr>
      </thead>
      <tbody>
        @for (pl of plans(); track pl.id) {
          <tr [attr.data-testid]="'plan-row-' + pl.id">
            <td data-testid="plan-figure">{{ pl.amount | fig }}</td>
            <td data-testid="plan-start">{{ pl.start | fig }}</td>
            <td data-testid="plan-every">{{ (pl.every ?? '') | fig }}</td>
            <td data-testid="plan-day">{{ (pl.day ?? '') | fig }}</td>
            <td data-testid="plan-end">{{ pl.end | fig }}</td>
            <td data-testid="plan-generated">{{ pl.generated | fig }}</td>
            <td data-testid="plan-next-dates">{{ (pl.nextDates.join(', ')) | fig }}</td>
            <td data-testid="plan-paused">{{ pl.paused ? 'yes' : 'no' }}</td>
            <td>
              <ul>
                @for (c of pl.changes; track $index) {
                  <li data-testid="plan-change-line">{{ c.on }} amount={{ c.amount }}</li>
                }
              </ul>
            </td>
            <td>
              <label [for]="'plan-new-amount-' + pl.id">New amount</label>
              <input
                [id]="'plan-new-amount-' + pl.id"
                data-testid="plan-amount"
                inputmode="decimal"
                [value]="newAmounts()[pl.id] ?? ''"
                (input)="setNewAmount(pl.id, value($event))"
              />
              <button type="button" data-testid="plan-amount-save" (click)="changeAmount(pl.id)">
                Save amount<span class="visually-hidden"> of plan starting {{ pl.start }}</span>
              </button>
              <button type="button" data-testid="plan-pause" (click)="setPaused(pl.id, true)">
                Pause<span class="visually-hidden"> plan starting {{ pl.start }}</span>
              </button>
              <button type="button" data-testid="plan-resume" (click)="setPaused(pl.id, false)">
                Resume<span class="visually-hidden"> plan starting {{ pl.start }}</span>
              </button>
              @if (rowError()?.id === pl.id) {
                <p role="alert" data-testid="plan-error">{{ rowError()?.message | fig }}</p>
              }
            </td>
          </tr>
        }
      </tbody>
    </table>
    <form (submit)="add($event)" [attr.aria-label]="'Add a plan for ' + personName()">
      <label [for]="'plan-amount-' + personId()">Plan amount</label>
      <input
        [id]="'plan-amount-' + personId()"
        data-testid="plan-form-amount"
        inputmode="decimal"
        [value]="amount()"
        (input)="amount.set(value($event)); error.set(null)"
      />
      <label [for]="'plan-start-' + personId()">Start</label>
      <input
        type="date"
        [id]="'plan-start-' + personId()"
        data-testid="plan-form-start"
        [value]="start()"
        (input)="start.set(value($event)); error.set(null)"
      />
      <label [for]="'plan-every-' + personId()">Every (days)</label>
      <input
        [id]="'plan-every-' + personId()"
        data-testid="plan-form-every"
        inputmode="numeric"
        [value]="every()"
        (input)="every.set(value($event)); error.set(null)"
      />
      <label [for]="'plan-day-' + personId()">Or monthly on day</label>
      <input
        [id]="'plan-day-' + personId()"
        data-testid="plan-form-day"
        inputmode="numeric"
        [value]="day()"
        (input)="day.set(value($event)); error.set(null)"
      />
      <label [for]="'plan-end-' + personId()">End</label>
      <input
        type="date"
        [id]="'plan-end-' + personId()"
        data-testid="plan-form-end"
        [value]="end()"
        (input)="end.set(value($event)); error.set(null)"
      />
      <button type="submit" data-testid="plan-form-submit">
        Add plan<span class="visually-hidden"> for {{ personName() }}</span>
      </button>
      @if (error(); as err) {
        <p role="alert" data-testid="plan-error">{{ err | fig }}</p>
      }
    </form>
  `,
})
export class PersonPlans {
  private readonly session = inject(Session);
  private readonly store = inject(PlanStore);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();

  protected readonly plans = computed(() => this.store.list().filter((p) => p.personId === this.personId()));
  protected readonly amount = signal('');
  protected readonly start = signal('');
  protected readonly every = signal('');
  protected readonly day = signal('');
  protected readonly end = signal('');
  protected readonly error = signal<string | null>(null);
  // The refusal of the last action taken on a plan's row.
  protected readonly rowError = signal<{ id: number; message: string } | null>(null);
  // The new amounts typed on the plans' rows, by plan id.
  protected readonly newAmounts = signal<Record<number, string>>({});
  // A submit in flight: a second one before it answers is ignored.
  private submitting = false;

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected setNewAmount(planId: number, amount: string): void {
    this.newAmounts.update((m) => ({ ...m, [planId]: amount }));
    if (this.rowError()?.id === planId) this.rowError.set(null);
  }

  protected async changeAmount(planId: number): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const amount = (this.newAmounts()[planId] ?? '').trim();
    if (!/^\d{1,12}(\.\d{1,2})?$/.test(amount)) {
      this.rowError.set({ id: planId, message: 'Enter an amount of at least 0 with up to two decimals.' });
      return;
    }
    try {
      await this.store.changeAmount(sheet, planId, amount);
    } catch (err) {
      const reason = err instanceof HttpErrorResponse ? (err.error as { message?: string } | null)?.message : null;
      this.rowError.set({
        id: planId,
        message:
          err instanceof HttpErrorResponse && err.status === 403
            ? 'Your role may not change a plan on this sheet.'
            : reason || 'Could not change the plan amount.',
      });
    }
  }

  protected async setPaused(planId: number, paused: boolean): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    try {
      await this.store.setPaused(sheet, planId, paused);
    } catch (err) {
      const reason = err instanceof HttpErrorResponse ? (err.error as { message?: string } | null)?.message : null;
      this.rowError.set({
        id: planId,
        message:
          err instanceof HttpErrorResponse && err.status === 403
            ? 'Your role may not change a plan on this sheet.'
            : reason || (paused ? 'Could not pause the plan.' : 'Could not resume the plan.'),
      });
    }
  }

  protected async add(e: Event): Promise<void> {
    e.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const amount = this.amount().trim();
    if (!/^\d{1,12}(\.\d{1,2})?$/.test(amount)) {
      this.error.set('Enter an amount of at least 0 with up to two decimals.');
      return;
    }
    const every = this.every().trim();
    const day = this.day().trim();
    if (!every === !day) {
      this.error.set('Enter either a number of days or a day of the month.');
      return;
    }
    if (every && (!/^\d+$/.test(every) || Number(every) < 1)) {
      this.error.set('Enter a whole number of days of at least 1.');
      return;
    }
    if (day && (!/^\d+$/.test(day) || Number(day) < 1 || Number(day) > 31)) {
      this.error.set('Enter a day of the month from 1 to 31.');
      return;
    }
    // a blank start takes the sheet's today
    const start = this.start() || this.session.sheetToday() || '';
    const end = this.end();
    if (!start || !end) {
      this.error.set('Enter a start date and an end date.');
      return;
    }
    if (end < start) {
      this.error.set('The end date is before the start date.');
      return;
    }
    this.submitting = true;
    try {
      await this.store.add(sheet, this.personId(), {
        amount,
        start,
        every: every ? Number(every) : null,
        day: day ? Number(day) : null,
        end,
      });
    } catch (err) {
      const reason = err instanceof HttpErrorResponse ? (err.error as { message?: string } | null)?.message : null;
      this.error.set(
        err instanceof HttpErrorResponse && err.status === 403
          ? 'Your role may not add a plan on this sheet.'
          : reason || 'Could not add the plan.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
