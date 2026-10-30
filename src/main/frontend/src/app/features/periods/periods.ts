import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input, model, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { heldOut, type PersonWithClaims } from '../claims/claims';

export interface Period {
  id: number;
  name: string;
  /** The first and last date of the period (yyyy-mm-dd), both inclusive. */
  from: string;
  to: string;
  closed: boolean;
  /** The due-total fixed when the period was closed; null while it is open. */
  closedTotal: string | null;
  /** Per person id, the person's due-total fixed when the period was closed; empty while it is open. */
  personTotals: Record<number, string>;
}

const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** The period whose dates contain the date, or undefined when none does. */
export function periodOf(periods: Period[], date: string): Period | undefined {
  return periods.find((p) => p.from <= date && date <= p.to);
}

/** Cents in an amount shown with two decimals ("12.50" is 1250). */
function cents(amount: string): number {
  return Math.round(Number(amount) * 100);
}

/** Per period id, the person's due-total for it: the dues of their claims that count whose date falls in it,
 * added up, or once closed the figure fixed then (0.00 for a person added later); an unbound OPEN claim dated
 * within a hold of its person counts in none, nor does a deferred claim. */
export function personPeriodTotals(periods: Period[], person: PersonWithClaims): Record<number, string> {
  const totals: Record<number, number> = {};
  for (const p of periods) totals[p.id] = 0;
  for (const c of person.claims) {
    const unpaid = c.status === 'OPEN' || c.status === 'PENDING';
    if ((!unpaid && c.status !== 'SETTLED') || c.due === null) continue;
    if (heldOut(c, person.holds) || c.deferred) continue;
    const period = periodOf(periods, c.date);
    if (period && !period.closed) totals[period.id] += cents(c.due);
  }
  const shown: Record<number, string> = {};
  for (const p of periods) {
    shown[p.id] = p.closed ? (p.personTotals?.[person.id] ?? '0.00') : (totals[p.id] / 100).toFixed(2);
  }
  return shown;
}

/** Per period id, its due-total: the people's due-totals for it, added up. */
export function periodDueTotals(periods: Period[], people: PersonWithClaims[]): Record<number, string> {
  const totals: Record<number, number> = {};
  for (const p of periods) totals[p.id] = 0;
  for (const person of people) {
    const figures = personPeriodTotals(periods, person);
    for (const p of periods) totals[p.id] += cents(figures[p.id]);
  }
  const shown: Record<number, string> = {};
  for (const p of periods) shown[p.id] = (totals[p.id] / 100).toFixed(2);
  return shown;
}

// The sheet's periods, earliest first, each with its due-total: the people's due-totals for it added up,
// each the dues of their claims that count (OPEN, PENDING and SETTLED) whose date falls in it, or once
// closed the figure fixed then; and the form that
// adds one. No two periods share a name or a date.
@Component({
  selector: 'app-periods',
  template: `
    <section aria-label="Periods">
      <h2>Periods</h2>
      <table>
        <thead>
          <tr><th scope="col">Period</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Due total</th><th scope="col">Closed</th><th scope="col"><span class="visually-hidden">Actions</span></th></tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <th scope="row" data-testid="period-name">{{ p.name }}</th>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-due-total">{{ dueTotals()[p.id] }}</td>
              <td data-testid="period-closed">{{ p.closed ? 'yes' : 'no' }}</td>
              <td>
                <button type="button" data-testid="period-close" [attr.aria-label]="'Close period ' + p.name" (click)="act(p, 'close')">Close</button>
                <button type="button" data-testid="period-reopen" [attr.aria-label]="'Reopen period ' + p.name" (click)="act(p, 'reopen')">Reopen</button>
                @if (rowError()?.id === p.id) {
                  <p role="alert" data-testid="period-error">{{ rowError()?.message }}</p>
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
      <form (submit)="save($event)" aria-label="Add period">
        <label for="period-form-name">Name</label>
        <input
          id="period-form-name"
          data-testid="period-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set('')"
        />
        <label for="period-form-from">From</label>
        <input
          id="period-form-from"
          type="date"
          data-testid="period-form-from"
          [value]="from()"
          (input)="from.set(value($event)); error.set('')"
        />
        <label for="period-form-to">To</label>
        <input
          id="period-form-to"
          type="date"
          data-testid="period-form-to"
          [value]="to()"
          (input)="to.set(value($event)); error.set('')"
        />
        <button type="submit" data-testid="period-form-submit">Add period</button>
        @if (error()) {
          <p role="alert" data-testid="period-form-error">{{ error() }}</p>
        }
      </form>
    </section>
  `,
})
export class Periods {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly people = input.required<PersonWithClaims[]>();
  readonly periods = model<Period[]>([]);

  protected readonly dueTotals = computed(() => periodDueTotals(this.periods(), this.people()));
  protected readonly name = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly error = signal('');
  /** The refusal of the last action on a period's row, shown on that row. */
  protected readonly rowError = signal<{ id: number; message: string } | null>(null);
  /** A submit still waiting for its answer: another press meanwhile is ignored. */
  private busy = false;

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.periods.set([]);
      this.name.set('');
      this.from.set('');
      this.to.set('');
      this.error.set('');
      this.rowError.set(null);
      if (sheet) void this.reload(sheet);
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async save(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.error.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const name = this.name().trim();
    const from = this.from().trim() || (this.session.today() ?? '');
    const to = this.to().trim();
    if (!name) {
      this.error.set('Enter a name.');
      return;
    }
    if (!DATE.test(from) || !DATE.test(to)) {
      this.error.set('Enter a from-date and a to-date.');
      return;
    }
    if (to < from) {
      this.error.set('The to-date is before the from-date.');
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<Period>(`/api/sheets/${encodeURIComponent(sheet)}/periods`, { name, from, to }, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.periods.update((ps) => [...ps.filter((p) => p.id !== saved.id), saved].sort((a, b) => a.from.localeCompare(b.from)));
      this.name.set('');
      this.from.set('');
      this.to.set('');
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const message = err instanceof HttpErrorResponse && err.status === 409
        ? (err.error?.message ?? 'That period name is already in use on this sheet.')
        : 'Could not add the period.';
      this.error.set(message);
    } finally {
      this.busy = false;
    }
  }

  /**
   * Close a period, fixing its due-total, or reopen a closed one (a steward only), making it live again;
   * a refusal is shown on its row.
   */
  protected async act(p: Period, action: 'close' | 'reopen'): Promise<void> {
    if (this.busy) return;
    this.rowError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<Period>(`/api/sheets/${encodeURIComponent(sheet)}/periods/${p.id}/${action}`, null, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.periods.update((ps) => ps.map((q) => (q.id === saved.id ? saved : q)));
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const message = err instanceof HttpErrorResponse && err.status === 409
        ? (err.error?.message ?? (action === 'close' ? 'The period is already closed.' : 'The period cannot be reopened.'))
        : `Could not ${action} the period.`;
      this.rowError.set({ id: p.id, message });
    } finally {
      this.busy = false;
    }
  }

  private async reload(sheet: string): Promise<void> {
    const loads = this.session.loads();
    const periods = await firstValueFrom(this.http.get<Period[]>(`/api/sheets/${encodeURIComponent(sheet)}/periods`));
    if (this.session.sheet() === sheet && this.session.loads() === loads) this.periods.set(periods);
  }
}
