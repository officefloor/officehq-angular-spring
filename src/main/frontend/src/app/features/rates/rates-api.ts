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
}

/** Of one grade's rates, the one in force on a date: the one with the latest from-date on or before
 *  it, or null when the date is before every one of them. */
export function rateInForce(rates: readonly RateView[], date: string): RateView | null {
  let inForce: RateView | null = null;
  for (const r of rates) {
    if (r.from !== null && r.from > date) continue;
    if (inForce === null || (inForce.from ?? '') < (r.from ?? '')) inForce = r;
  }
  return inForce;
}

/** Whether two rates are the same grade's rate for the same from-date. */
export function sameRate(a: RateView, b: RateView): boolean {
  return a.grade === b.grade && a.from === b.from;
}

/** What is due on an amount of `amountCents` at a rate of `pct` percent, in whole cents: the
 *  amount times the rate, divided by 100, rounded half to even (a half rounds to the even cent). */
export function dueCents(amountCents: number, pct: string): number {
  // Hundredths of a percent, so the product is exact; cents x basis points / 10000 = due cents.
  const product = BigInt(amountCents) * BigInt(Math.round(Number(pct) * 100));
  const sign = product < 0n ? -1n : 1n;
  const magnitude = sign * product;
  let whole = magnitude / 10000n;
  const rest = magnitude % 10000n;
  if (rest > 5000n || (rest === 5000n && whole % 2n === 1n)) whole += 1n;
  return Number(whole * sign);
}

// The sheet's rates per grade, as the store holds them.
@Injectable({ providedIn: 'root' })
export class RatesApi {
  private readonly http = inject(HttpClient);

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/rates`;
  }

  /** The sheet's rates; a grade with no rate is left out. */
  list(sheetId: string): Promise<RateView[]> {
    return firstValueFrom(this.http.get<RateView[]>(this.url(sheetId)));
  }

  /** Set the grade's rate from a from-date (null: from the beginning of time), replacing the one it had for it. */
  set(sheetId: string, grade: Grade, pct: string, from: string | null): Promise<RateView> {
    return firstValueFrom(this.http.put<RateView>(`${this.url(sheetId)}/${grade}`, { pct, from }));
  }
}
