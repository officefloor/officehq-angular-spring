import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Grade } from './people';
import { Session } from './session';

export interface Rate {
  grade: Grade;
  /** The percent as text with two decimals. */
  pct: string;
  /** The day (yyyy-mm-dd) the rate applies from; null when it applies from the beginning of time. */
  from: string | null;
}

/** The percent of a grade's rate in force on a day: the rate with the latest from-date on or
 *  before it, or else the one with no from-date; undefined when the day is before every rate. */
export function pctOn(rates: Rate[], grade: Grade, day: string): string | undefined {
  let best: Rate | undefined;
  for (const r of rates) {
    if (r.grade !== grade || (r.from !== null && r.from > day)) continue;
    if (best === undefined || (best.from ?? '') < (r.from ?? '')) best = r;
  }
  return best?.pct;
}

/** A percent with up to two decimals, 0.00 or more. */
export const PCT = /^\d{1,5}(\.\d{1,2})?$/;

/** Two-decimal text, which may be negative, as a whole number of hundredths. */
function hundredths(text: string): bigint {
  const negative = text.startsWith('-');
  const [whole, frac = ''] = (negative ? text.slice(1) : text).split('.');
  const n = BigInt(whole) * 100n + BigInt(frac.padEnd(2, '0'));
  return negative ? -n : n;
}

/** An amount's due at a percent: amount times pct divided by 100, rounded to two decimals half to
 *  even, or half up (away from zero) when `halfUp`, worked in whole numbers so no rounding creeps
 *  in; a negative amount has a negative due. Null when there is no rate. */
export function due(amount: string, pct: string | undefined, halfUp = false): string | null {
  if (pct === undefined) return null;
  const signed = hundredths(amount) * hundredths(pct);
  const exact = signed < 0n ? -signed : signed;
  let cents = exact / 10000n;
  const rest = exact % 10000n;
  if (rest > 5000n || (rest === 5000n && (halfUp || cents % 2n === 1n))) cents += 1n;
  const text = cents.toString().padStart(3, '0');
  return `${signed < 0n && cents > 0n ? '-' : ''}${text.slice(0, -2)}.${text.slice(-2)}`;
}

/** The larger of two two-decimal figures. */
export function atLeast(figure: string, floor: string): string {
  return hundredths(figure) < hundredths(floor) ? floor : figure;
}

// The rates for each grade on the active sheet.
@Injectable({ providedIn: 'root' })
export class Rates {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  list(sheetId: string): Promise<Rate[]> {
    return firstValueFrom(this.http.get<Rate[]>(this.url(sheetId)));
  }

  /** Set a grade's rate from a day (null: from the beginning of time), replacing the one the grade
   *  had for that from-date. */
  set(grade: Grade, pct: string, from: string | null): Promise<Rate> {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    return firstValueFrom(
      this.http.put<Rate>(`${this.url(sheetId)}/${grade}`, { pct, from }, { headers: { 'X-User': user } }),
    );
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/rates`;
  }
}
