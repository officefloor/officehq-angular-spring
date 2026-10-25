import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Grade } from '../people/people-api';

export interface RateView {
  grade: Grade;
  /** The percent with two decimals, as the store holds it. */
  pct: string;
  /** The date the rate is in force from (yyyy-mm-dd), or null when it applies from the beginning of time. */
  from: string | null;
  /** The last date the rate is in force (yyyy-mm-dd), or null when it runs until the grade's next rate. */
  to: string | null;
  /** How many parents up the rate's sheet is: 0 for the sheet's own rate, 1 for its parent's, and so on. */
  level: number;
}

/** Of one grade's rates on one sheet, the one in force on a date: the one with the latest from-date on
 *  or before it, or null when the date is before every one of them or after that one's to-date. */
function ownRateInForce(rates: readonly RateView[], date: string): RateView | null {
  let inForce: RateView | null = null;
  for (const r of rates) {
    if (r.from !== null && r.from > date) continue;
    if (inForce === null || (inForce.from ?? '') < (r.from ?? '')) inForce = r;
  }
  return inForce !== null && inForce.to !== null && inForce.to < date ? null : inForce;
}

/** Of one grade's rates, the one in force on a date: the sheet's own, else the nearest parent's in
 *  force then; null when none is. */
export function rateInForce(rates: readonly RateView[], date: string): RateView | null {
  const levels = [...new Set(rates.map((r) => r.level ?? 0))].sort((a, b) => a - b);
  for (const level of levels) {
    const rate = ownRateInForce(rates.filter((r) => (r.level ?? 0) === level), date);
    if (rate !== null) return rate;
  }
  return null;
}

/** Whether two rates are the same sheet's rate of the same grade for the same from-date. */
export function sameRate(a: RateView, b: RateView): boolean {
  return a.grade === b.grade && a.from === b.from && a.level === b.level;
}

/** What is due on an amount of `amountCents` at a rate of `pct` percent, in whole cents: the
 *  amount times the rate, divided by 100, rounded half to even (a half rounds to the even cent), or
 *  half up (a half rounds away from zero) when `halfUp`. */
export function dueCents(amountCents: number, pct: string, halfUp = false): number {
  // Hundredths of a percent, so the product is exact; cents x basis points / 10000 = due cents.
  const product = BigInt(amountCents) * BigInt(Math.round(Number(pct) * 100));
  const sign = product < 0n ? -1n : 1n;
  const magnitude = sign * product;
  let whole = magnitude / 10000n;
  const rest = magnitude % 10000n;
  if (rest > 5000n || (rest === 5000n && (halfUp || whole % 2n === 1n))) whole += 1n;
  return Number(whole * sign);
}

/** What a candidate rate would do: how many claims would show a different due, and the sum of the
 *  differences (new due less current due) with two decimals. */
export interface RatePreview {
  count: number;
  diff: string;
}

/** What an import set: how many rates, and the rates, in the order of their lines. */
export interface ImportRatesView {
  set: number;
  rates: RateView[];
}

// The sheet's rates per grade, as the store holds them.
@Injectable({ providedIn: 'root' })
export class RatesApi {
  private readonly http = inject(HttpClient);

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/rates`;
  }

  /** The sheet's rates, then its parents' up to three levels up (by level); a grade with no rate is left out. */
  list(sheetId: string): Promise<RateView[]> {
    return firstValueFrom(this.http.get<RateView[]>(this.url(sheetId)));
  }

  /** Set the grade's rate from a from-date (null: from the beginning of time) through a to-date
   *  (null: until the grade's next rate), replacing the one it had for that from-date. */
  set(sheetId: string, grade: Grade, pct: string, from: string | null, to: string | null): Promise<RateView> {
    return firstValueFrom(this.http.put<RateView>(`${this.url(sheetId)}/${grade}`, { pct, from, to }));
  }

  /** Set a rate per line of grade,rate,from,to, in order, as the rate form would; refused as a whole,
   *  setting nothing, when any line is refused. */
  importRates(sheetId: string, text: string): Promise<ImportRatesView> {
    return firstValueFrom(this.http.post<ImportRatesView>(`${this.url(sheetId)}/import`, { text }));
  }

  /** Preview the grade's candidate percent on every date; nothing is saved. */
  preview(sheetId: string, grade: Grade, pct: string): Promise<RatePreview> {
    return firstValueFrom(this.http.get<RatePreview>(`${this.url(sheetId)}/${grade}/preview`, { params: { pct } }));
  }
}
