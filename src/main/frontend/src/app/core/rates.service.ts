import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, of, tap } from 'rxjs';
import { GRADES, Grade } from './people.service';
import { SessionService } from './session.service';
import { SheetService } from './sheet.service';

export interface Rate {
  grade: Grade;
  /** A percent as text with two decimals, e.g. "12.50". */
  pct: string;
  /** The first date (yyyy-MM-dd) the rate applies on; null when it applies from the beginning of time. */
  from: string | null;
}

// The active sheet's rates, at most one per grade and from-date. A claim uses its grade's rate with
// the latest from-date on or before the claim's date (a rate with none applies from the beginning of
// time); a claim dated before every rate of its grade has none.
@Injectable({ providedIn: 'root' })
export class RatesService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionService);
  private readonly sheets = inject(SheetService);
  private readonly list = signal<Rate[]>([]);

  readonly rates = this.list.asReadonly();

  /** Each grade's rate in force on the sheet's today; a grade with none then is absent. */
  readonly current = computed(() => {
    const today = this.sheets.today();
    const out = new Map<Grade, Rate>();
    if (today === null) return out;
    for (const g of GRADES) {
      const r = inForce(this.list(), g, today);
      if (r) out.set(g, r);
    }
    return out;
  });

  /** Each grade's rate in force on the sheet's today, in hundredths of a percent; a grade with none then is absent. */
  readonly byGrade = computed(() => {
    const out = new Map<Grade, number>();
    for (const [g, r] of this.current()) out.set(g, hundredths(r.pct));
    return out;
  });

  /** The grade's rate in force on a date (yyyy-MM-dd), in hundredths of a percent; undefined when none. */
  rateOn(grade: Grade, date: string): number | undefined {
    const r = inForce(this.list(), grade, date);
    return r ? hundredths(r.pct) : undefined;
  }

  /** Re-read the active sheet's rates from the store. */
  reload(): Observable<unknown> {
    const id = this.sheets.sheetId();
    if (!id) {
      this.list.set([]);
      return of(null);
    }
    return this.http
      .get<Rate[]>(this.url(id), { headers: this.session.headers() })
      .pipe(tap((rates) => {
        if (this.sheets.sheetId() === id) this.list.set(rates);
      }));
  }

  /** Set a grade's rate from a date (null: from the beginning of time), replacing the one it had for that from-date. */
  set(grade: Grade, pct: string, from: string | null): Observable<Rate> {
    const id = this.sheets.sheetId();
    if (!id) throw new Error('no sheet loaded');
    return this.http
      .put<Rate>(`${this.url(id)}/${grade}`, { pct, from }, { headers: this.session.headers() })
      .pipe(tap((r) => this.list.update((all) => [
        ...all.filter((o) => o.grade !== r.grade || o.from !== r.from),
        r,
      ].sort(byGradeThenFrom))));
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/rates`;
  }
}

/** The grade's rate with the latest from-date on or before the date; undefined when none. */
function inForce(rates: readonly Rate[], grade: Grade, date: string): Rate | undefined {
  let best: Rate | undefined;
  for (const r of rates) {
    if (r.grade !== grade || (r.from !== null && r.from > date)) continue;
    if (best === undefined || (best.from ?? '') < (r.from ?? '')) best = r;
  }
  return best;
}

function byGradeThenFrom(a: Rate, b: Rate): number {
  return a.grade.localeCompare(b.grade) || (a.from ?? '').localeCompare(b.from ?? '');
}

/** "12.50" as 1250. */
function hundredths(pct: string): number {
  const [whole, frac = ''] = pct.split('.');
  return Number(whole) * 100 + Number((frac + '00').slice(0, 2));
}

/**
 * Cents times a rate (in hundredths of a percent) divided by 100, in cents, rounded half to even (a
 * half goes to the even cent), or half up (away from zero) when `halfUp`. Worked in BigInt so it is
 * exact for any amount.
 */
export function due(cents: number, rate: number, halfUp = false): number {
  if (cents < 0) return -due(-cents, rate, halfUp);
  const product = BigInt(cents) * BigInt(rate);
  const q = product / 10000n;
  const r = product % 10000n;
  return Number(r > 5000n || (r === 5000n && (halfUp || q % 2n === 1n)) ? q + 1n : q);
}
