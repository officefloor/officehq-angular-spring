import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, of, tap } from 'rxjs';
import { Grade } from './people.service';
import { SessionService } from './session.service';
import { SheetService } from './sheet.service';

export interface Rate {
  grade: Grade;
  /** A percent as text with two decimals, e.g. "12.50". */
  pct: string;
}

// The active sheet's rates, at most one per grade; a grade with no rate has none.
@Injectable({ providedIn: 'root' })
export class RatesService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionService);
  private readonly sheets = inject(SheetService);
  private readonly list = signal<Rate[]>([]);

  readonly rates = this.list.asReadonly();

  /** Each grade's rate, in hundredths of a percent; a grade with no rate is absent. */
  readonly byGrade = computed(() => {
    const out = new Map<Grade, number>();
    for (const r of this.list()) out.set(r.grade, hundredths(r.pct));
    return out;
  });

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

  set(grade: Grade, pct: string): Observable<Rate> {
    const id = this.sheets.sheetId();
    if (!id) throw new Error('no sheet loaded');
    return this.http
      .put<Rate>(`${this.url(id)}/${grade}`, { pct }, { headers: this.session.headers() })
      .pipe(tap((r) => this.list.update((all) => [...all.filter((o) => o.grade !== r.grade), r])));
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/rates`;
  }
}

/** "12.50" as 1250. */
function hundredths(pct: string): number {
  const [whole, frac = ''] = pct.split('.');
  return Number(whole) * 100 + Number((frac + '00').slice(0, 2));
}

/**
 * Cents times a rate (in hundredths of a percent) divided by 100, in cents, rounded half up (a half
 * rounds away from zero). Worked in BigInt so it is exact for any amount.
 */
export function due(cents: number, rate: number): number {
  return Number((BigInt(cents) * BigInt(rate) + 5000n) / 10000n);
}
