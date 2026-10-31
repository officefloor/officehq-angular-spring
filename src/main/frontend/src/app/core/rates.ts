import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { Grade } from './people';
import { Sheets } from './sheet';

export interface RateView {
  grade: Grade;
  /** The percent with exactly two decimals, e.g. "12.50". */
  rate: string;
  /** The day it takes effect (yyyy-mm-dd); null when it applies from the beginning of time. */
  from: string | null;
}

/** The rate among a grade's rates (in the order they take effect) in force on a date (yyyy-mm-dd):
 *  the one with the latest from-date on or before it; undefined when the date is before them all. */
export function rateOn(gradeRates: readonly RateView[], date: string): string | undefined {
  let inForce: string | undefined;
  for (const r of gradeRates) if (r.from === null || r.from <= date) inForce = r.rate;
  return inForce;
}

/** Rates in the order they take effect: the one with no from-date first, then by from-date. */
function byFrom(a: RateView, b: RateView): number {
  return a.from === b.from ? 0 : a.from === null ? -1 : b.from === null ? 1 : a.from < b.from ? -1 : 1;
}

/** A rate the form accepts: a percent of 0.00 or more, up to 999.99, with up to two decimals. */
export const RATE_PATTERN = /^\d{1,3}(\.\d{1,2})?$/;

/** What is shown in place of a due when its grade has no rate. */
export const NOT_AVAILABLE = 'n/a';

/** The due on an amount (in whole cents) at a rate (a percent, e.g. "12.50"), in whole cents:
 *  amount × rate / 100, rounded half up to the cent. Exact, whatever the size of the amount. */
export function dueCents(amountCents: number, rate: string): number {
  const [whole, frac = ''] = rate.split('.');
  const basisPoints = BigInt(whole) * 100n + BigInt(frac.padEnd(2, '0').slice(0, 2));
  return Number((BigInt(amountCents) * basisPoints + 5000n) / 10000n);
}

// The rates set for each grade on the active sheet, by from-date; a grade with none has no rate.
@Injectable({ providedIn: 'root' })
export class Rates {
  private readonly http = inject(HttpClient);
  private readonly sheets = inject(Sheets);
  private readonly list = signal<RateView[]>([]);
  /** The active sheet's rates of each grade, in the order they take effect. */
  readonly ratesByGrade = computed(() => {
    const byGrade = new Map<Grade, RateView[]>();
    for (const r of [...this.list()].sort(byFrom)) byGrade.set(r.grade, [...(byGrade.get(r.grade) ?? []), r]);
    return byGrade;
  });
  /** The rate of each grade in force on the active sheet's today; a grade with none is missing. */
  readonly byGrade = computed(() => {
    const today = this.sheets.current()?.today ?? '';
    const current = new Map<Grade, string>();
    for (const [grade, rates] of this.ratesByGrade()) {
      const rate = rateOn(rates, today);
      if (rate !== undefined) current.set(grade, rate);
    }
    return current;
  });

  /** Re-read a sheet's rates from the store; null clears them. */
  load(sheetId: string | null): Observable<RateView[]> | null {
    this.list.set([]);
    if (sheetId === null) return null;
    return this.http.get<RateView[]>(this.base(sheetId)).pipe(tap((rates) => this.list.set(rates)));
  }

  /** Set a grade's rate from a date (null: from the beginning of time), replacing the one it had from that date. */
  set(sheetId: string, grade: Grade, rate: string, from: string | null): Observable<RateView> {
    return this.http
      .put<RateView>(`${this.base(sheetId)}/${grade}`, { rate, from })
      .pipe(
        tap((saved) =>
          this.list.update((list) => [...list.filter((r) => r.grade !== saved.grade || r.from !== saved.from), saved]),
        ),
      );
  }

  private base(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/rates`;
  }
}
