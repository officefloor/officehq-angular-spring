import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input, model, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import type { PersonWithClaims } from '../claims/claims';

export interface Period {
  id: number;
  name: string;
  /** The first and last date of the period (yyyy-mm-dd), both inclusive. */
  from: string;
  to: string;
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

// The sheet's periods, earliest first, each with its due-total: the dues of the claims that count
// (OPEN and SETTLED) whose date falls in it; and the form that adds one. No two periods share a name
// or a date.
@Component({
  selector: 'app-periods',
  template: `
    <section aria-label="Periods">
      <h2>Periods</h2>
      <table>
        <thead>
          <tr><th scope="col">Period</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Due total</th></tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <th scope="row" data-testid="period-name">{{ p.name }}</th>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-due-total">{{ dueTotals()[p.id] }}</td>
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

  /** Per period id, the dues of the claims that count whose date falls in it, added up. */
  protected readonly dueTotals = computed<Record<number, string>>(() => {
    const totals: Record<number, number> = {};
    for (const p of this.periods()) totals[p.id] = 0;
    for (const person of this.people()) {
      for (const c of person.claims) {
        if ((c.status !== 'OPEN' && c.status !== 'SETTLED') || c.due === null) continue;
        const period = periodOf(this.periods(), c.date);
        if (period) totals[period.id] += cents(c.due);
      }
    }
    const shown: Record<number, string> = {};
    for (const id of Object.keys(totals)) shown[+id] = (totals[+id] / 100).toFixed(2);
    return shown;
  });
  protected readonly name = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly error = signal('');
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

  private async reload(sheet: string): Promise<void> {
    const loads = this.session.loads();
    const periods = await firstValueFrom(this.http.get<Period[]>(`/api/sheets/${encodeURIComponent(sheet)}/periods`));
    if (this.session.sheet() === sheet && this.session.loads() === loads) this.periods.set(periods);
  }
}
