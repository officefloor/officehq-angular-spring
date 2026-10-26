import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, Injectable, computed, inject, input, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { sumSkippingNa } from '../rates/rates';

export interface PeriodView {
  id: number;
  name: string;
  // Both inclusive (yyyy-mm-dd).
  from: string;
  to: string;
}

/** A counted claim's date and the figure it totals (null for n/a). */
export interface DatedFigure {
  date: string;
  figure: string | null;
}

const DATE = /^\d{4}-\d{2}-\d{2}$/;

/** The period whose dates contain the date; undefined when none does. */
export function periodOn(periods: readonly PeriodView[], date: string): PeriodView | undefined {
  return periods.find((p) => p.from <= date && date <= p.to);
}

// The periods of the active sheet, by from-date.
@Injectable({ providedIn: 'root' })
export class PeriodStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly list = signal<readonly PeriodView[]>([]);

  /** Re-read the sheet's periods from the store. */
  async load(sheet: string): Promise<void> {
    this.list.set([]);
    const list = await firstValueFrom(this.http.get<PeriodView[]>(`/api/sheets/${encodeURIComponent(sheet)}/periods`));
    if (this.session.sheetId() === sheet) this.list.set(list);
  }

  async add(sheet: string, name: string, from: string, to: string): Promise<void> {
    const p = await firstValueFrom(
      this.http.post<PeriodView>(`/api/sheets/${encodeURIComponent(sheet)}/periods`, { name, from, to }),
    );
    if (this.session.sheetId() === sheet) {
      this.list.update((l) => [...l.filter((x) => x.id !== p.id), p].sort((a, b) => a.from.localeCompare(b.from)));
    }
  }
}

// The sheet's periods, each with its due-total, and the form that adds one.
@Component({
  selector: 'app-periods',
  template: `
    <section aria-label="Periods">
      <h2>Periods</h2>
      <form (submit)="submit($event)" aria-label="Add period">
        <label for="period-name">Name</label>
        <input
          id="period-name"
          data-testid="period-form-name"
          [value]="name()"
          (input)="name.set(value($event)); error.set(null)"
        />
        <label for="period-from">From</label>
        <input
          id="period-from"
          type="date"
          data-testid="period-form-from"
          [value]="from()"
          (input)="from.set(value($event)); error.set(null)"
        />
        <label for="period-to">To</label>
        <input
          id="period-to"
          type="date"
          data-testid="period-form-to"
          [value]="to()"
          (input)="to.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="period-form-submit">Add period</button>
        @if (error(); as err) {
          <p role="alert" data-testid="period-form-error">{{ err }}</p>
        }
      </form>

      <table>
        <thead>
          <tr><th scope="col">Name</th><th scope="col">From</th><th scope="col">To</th><th scope="col">Due</th></tr>
        </thead>
        <tbody>
          @for (p of store.list(); track p.id) {
            <tr [attr.data-testid]="'period-row-' + p.name">
              <th scope="row" data-testid="period-name">{{ p.name }}</th>
              <td data-testid="period-from">{{ p.from }}</td>
              <td data-testid="period-to">{{ p.to }}</td>
              <td data-testid="period-due-total">{{ dueBy().get(p.id) }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Periods {
  private readonly session = inject(Session);
  protected readonly store = inject(PeriodStore);

  // The counted claims of the sheet: each one's date and figure.
  readonly figures = input<readonly DatedFigure[]>([]);

  protected readonly name = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly error = signal<string | null>(null);
  // A submit in flight: a second submit before it answers is ignored, so it stores one period.
  private submitting = false;

  // Each period's due-total: the figures of the counted claims dated in it, skipping n/a.
  protected readonly dueBy = computed(() => {
    const by = new Map<number, (string | null)[]>();
    const periods = this.store.list();
    for (const p of periods) by.set(p.id, []);
    for (const f of this.figures()) {
      const p = periodOn(periods, f.date);
      if (p) by.get(p.id)?.push(f.figure);
    }
    return new Map([...by].map(([id, list]) => [id, sumSkippingNa(list)]));
  });

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async submit(e: Event): Promise<void> {
    e.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const name = this.name().trim();
    if (!name) {
      this.error.set('Enter a name.');
      return;
    }
    const from = this.from().trim() || (this.session.sheetToday() ?? '');
    const to = this.to().trim();
    if (!DATE.test(from) || !DATE.test(to)) {
      this.error.set('Enter a from and a to date as yyyy-mm-dd.');
      return;
    }
    if (to < from) {
      this.error.set('A period cannot end before it starts.');
      return;
    }
    if (this.store.list().some((p) => p.name === name)) {
      this.error.set('That period name is already in use on this sheet.');
      return;
    }
    if (this.store.list().some((p) => p.from <= to && from <= p.to)) {
      this.error.set('That period overlaps another.');
      return;
    }
    this.submitting = true;
    try {
      await this.store.add(sheet, name, from, to);
      this.name.set('');
      this.from.set('');
      this.to.set('');
    } catch (err) {
      this.error.set(
        err instanceof HttpErrorResponse && err.status === 409
          ? 'That period name is in use, or its dates overlap another period.'
          : 'Could not add that period.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
