import { HttpClient } from '@angular/common/http';
import { Component, Injectable, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export interface Rate {
  grade: string;
  /** A percent with two decimals, e.g. "12.50". */
  rate: string;
  /** The date the rate applies from (yyyy-mm-dd); null when it applies from the beginning of time. */
  from: string | null;
}

export const GRADES = ['A', 'B', 'C'];

/** Shown for a due that has no rate to work from. */
export const NO_RATE = 'n/a';

/** The due on an amount in cents at a percent rate (two decimals), in cents: amount times rate
 *  divided by 100, rounded half to even (a negative amount rounds as its opposite does). Worked in whole numbers so nothing is lost to floating
 *  point. */
export function dueCents(amountCents: number, rate: string): number {
  if (amountCents < 0) return -dueCents(-amountCents, rate);
  const hundredths = BigInt(Math.round(Number(rate) * 100));
  const product = BigInt(amountCents) * hundredths;
  const whole = product / 10000n;
  const rest = product % 10000n;
  return Number(rest > 5000n || (rest === 5000n && whole % 2n === 1n) ? whole + 1n : whole);
}

/** The rate in force on a date among a grade's rates: the one whose from-date is the latest on or
 *  before the date (no from-date applies from the beginning of time); null when none applies. */
export function rateOn(rates: Rate[], date: string): string | null {
  let best: Rate | null = null;
  for (const r of rates) {
    if (r.from !== null && r.from > date) continue;
    if (best === null || (best.from ?? '') < (r.from ?? '')) best = r;
  }
  return best?.rate ?? null;
}

/** A grade a person was given, in force from its from-date. */
export interface PersonGrade {
  grade: string;
  /** The date the grade applies from (yyyy-mm-dd); null when it applies from the beginning of time,
   *  as the first grade a person was given does. */
  from: string | null;
}

/** The grade in force on a date among a person's grades: the one whose from-date is the latest on
 *  or before the date; null when none applies. */
export function gradeOn(grades: PersonGrade[], date: string): string | null {
  let best: PersonGrade | null = null;
  for (const g of grades) {
    if (g.from !== null && g.from > date) continue;
    if (best === null || (best.from ?? '') < (g.from ?? '')) best = g;
  }
  return best?.grade ?? null;
}

/** Rates in the order they are listed: by grade, then from the earliest. */
function byGradeAndFrom(a: Rate, b: Rate): number {
  return a.grade.localeCompare(b.grade) || (a.from ?? '').localeCompare(b.from ?? '');
}

const PERCENT = /^\d{1,5}(\.\d{1,2})?$/;

// The active sheet's rates for each grade, each from its from-date, re-read whenever a sheet is
// loaded.
@Injectable({ providedIn: 'root' })
export class RateStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The sheet's rates by grade, each grade's from the earliest. */
  readonly rates = signal<Rate[]>([]);
  /** Each grade's rates by grade; a grade with no rate is absent. */
  readonly byGrade = computed(() => {
    const map = new Map<string, Rate[]>();
    for (const r of this.rates()) map.set(r.grade, [...(map.get(r.grade) ?? []), r]);
    return map;
  });
  /** Each grade's rate in force on the sheet's today; a grade with none is absent. */
  readonly current = computed(() => {
    const today = this.session.today();
    const map = new Map<string, string>();
    if (!today) return map;
    for (const [grade, rates] of this.byGrade()) {
      const rate = rateOn(rates, today);
      if (rate !== null) map.set(grade, rate);
    }
    return map;
  });

  /** A grade's rates; none when the grade has no rate. */
  of(grade: string): Rate[] {
    return this.byGrade().get(grade) ?? [];
  }

  /** The rates of a person's grade in force on a date; none when that grade has no rate. */
  ofGradeOn(grades: PersonGrade[], date: string): Rate[] {
    const grade = gradeOn(grades, date);
    return grade === null ? [] : this.of(grade);
  }

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      const load = this.session.loads();
      this.rates.set([]);
      if (sheet) void this.read(sheet, load);
    });
  }

  /** Set a grade's rate from a date (null: from the beginning of time) on the active sheet,
   *  replacing the one the grade had from that date. */
  async set(grade: string, rate: string, from: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<Rate>(`/api/sheets/${encodeURIComponent(sheet)}/rates/${grade}`, { rate, from }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.rates.update((list) =>
      [...list.filter((r) => r.grade !== saved.grade || r.from !== saved.from), saved].sort(byGradeAndFrom),
    );
  }

  private async read(sheet: string, load: number): Promise<void> {
    const list = await firstValueFrom(
      this.http.get<Rate[]>(`/api/sheets/${encodeURIComponent(sheet)}/rates`),
    );
    if (this.session.loads() !== load) return;
    this.rates.set(list);
  }
}

// The sheet's rate in force today for each grade, every rate with its from-date, and the form that
// sets one.
@Component({
  selector: 'app-rates',
  template: `
    <section aria-labelledby="rates-heading">
      <h2 id="rates-heading">Rates</h2>
      <form (submit)="submit($event)">
        <label for="rate-form-grade">Grade</label>
        <select
          id="rate-form-grade"
          data-testid="rate-form-grade"
          [value]="grade()"
          (change)="grade.set(value($event)); error.set(null)"
        >
          @for (g of grades; track g) {
            <option [value]="g" [selected]="g === grade()">{{ g }}</option>
          }
        </select>
        <label for="rate-form-pct">Rate (%)</label>
        <input
          id="rate-form-pct"
          data-testid="rate-form-pct"
          inputmode="decimal"
          [value]="pct()"
          (input)="pct.set(value($event)); error.set(null)"
        />
        <label for="rate-form-from">From (blank: always)</label>
        <input
          id="rate-form-from"
          type="date"
          data-testid="rate-form-from"
          [value]="from()"
          (input)="from.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="rate-form-submit">Set rate</button>
      </form>
      @if (error(); as message) {
        <p role="alert" data-testid="rate-form-error">{{ message }}</p>
      }
      <table>
        <caption>In force today</caption>
        <thead>
          <tr>
            <th scope="col">Grade</th>
            <th scope="col">Rate (%)</th>
          </tr>
        </thead>
        <tbody>
          @for (g of grades; track g) {
            <tr>
              <th scope="row">{{ g }}</th>
              <td [attr.data-testid]="'rate-current-' + g">{{ store.current().get(g) ?? noRate }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table>
        <caption>All rates</caption>
        <thead>
          <tr>
            <th scope="col">Grade</th>
            <th scope="col">From</th>
            <th scope="col">Rate (%)</th>
          </tr>
        </thead>
        <tbody>
          @for (r of store.rates(); track r.grade + '-' + (r.from ?? 'start')) {
            <tr [attr.data-testid]="'rate-row-' + r.grade + '-' + (r.from ?? 'start')">
              <th scope="row">{{ r.grade }}</th>
              <td data-testid="rate-from">{{ r.from ?? 'always' }}</td>
              <td data-testid="rate-pct">{{ r.rate }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Rates {
  protected readonly store = inject(RateStore);
  protected readonly grades = GRADES;
  protected readonly noRate = NO_RATE;
  protected readonly grade = signal('A');
  protected readonly pct = signal('');
  protected readonly from = signal('');
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer; a second submit meanwhile is dropped. */
  private readonly busy = signal(false);

  protected value(event: Event): string {
    return (event.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy()) return;
    this.error.set(null);
    const pct = this.pct().trim();
    if (!PERCENT.test(pct)) {
      this.error.set('Type a rate of 0.00 or more, with up to two decimals.');
      return;
    }
    this.busy.set(true);
    try {
      await this.store.set(this.grade(), pct, this.from().trim() || null);
    } catch {
      this.error.set('The rate could not be set.');
    } finally {
      this.busy.set(false);
    }
  }
}
