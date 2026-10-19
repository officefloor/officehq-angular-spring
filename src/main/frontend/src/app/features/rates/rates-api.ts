import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Session } from '../../core/session';
import { Grade } from '../people/people-api';

export interface Rate {
  grade: Grade;
  /** The date (yyyy-mm-dd) the rate is in force from; null for from the beginning of time. */
  from: string | null;
  /** A percent with two decimals, e.g. "12.50". */
  rate: string;
}

/**
 * The rate a grade has in force on a date (yyyy-mm-dd): the one with the latest from-date on or
 * before it; none when the date is before every rate of the grade.
 */
export function rateOn(rates: readonly Rate[], grade: Grade, date: string): string | undefined {
  // No from-date sorts as '', before every date.
  let best: Rate | undefined;
  for (const r of rates) {
    const from = r.from ?? '';
    if (r.grade === grade && from <= date && (!best || from > (best.from ?? ''))) best = r;
  }
  return best?.rate;
}

/** Shown in place of a due whose grade has no rate. */
export const NO_RATE = 'n/a';

/** Hundredths of a decimal with up to two decimals, e.g. "12.5" is 1250. */
function hundredths(value: string): bigint {
  const [whole, frac = ''] = value.split('.');
  return BigInt(whole) * 100n + BigInt(frac.padEnd(2, '0'));
}

/**
 * What is due on an amount at a rate: the amount times the rate, divided by 100, rounded half up
 * to two decimals. Worked in whole numbers so no rounding creeps in; n/a when there is no rate.
 */
export function dueOf(amount: string, rate: string | undefined): string {
  if (rate === undefined) return NO_RATE;
  // cents × hundredths of a percent is in millionths; round to cents (amounts are never negative).
  const cents = (hundredths(amount) * hundredths(rate) + 5000n) / 10000n;
  return `${cents / 100n}.${String(cents % 100n).padStart(2, '0')}`;
}

// The rate for each grade on the active sheet, read and changed through the server.
@Injectable({ providedIn: 'root' })
export class RatesApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  list(sheetId: string): Observable<Rate[]> {
    return this.http.get<Rate[]>(this.url(sheetId), { headers: this.headers() });
  }

  set(sheetId: string, grade: Grade, rate: string, from: string | null): Observable<Rate> {
    return this.http.put<Rate>(`${this.url(sheetId)}/${grade}`, { rate, from }, { headers: this.headers() });
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/rates`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}
