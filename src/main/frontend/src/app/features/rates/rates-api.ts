import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Session } from '../../core/session';
import { Grade } from '../people/people-api';

export interface Rate {
  grade: Grade;
  /** The date (yyyy-mm-dd) the rate is in force from; null for from the beginning of time. */
  from: string | null;
  /** The date (yyyy-mm-dd) the rate applies through; null for no end. */
  to: string | null;
  /** A percent with two decimals, e.g. "12.50". */
  rate: string;
}

/**
 * The rate a grade has in force on a date (yyyy-mm-dd): the one with the latest from-date on or
 * before it, provided the date is not after its to-date; none when the date is before every rate of
 * the grade, or after the to-date of that one.
 */
export function rateOn(rates: readonly Rate[], grade: Grade, date: string): string | undefined {
  // No from-date sorts as '', before every date.
  let best: Rate | undefined;
  for (const r of rates) {
    const from = r.from ?? '';
    if (r.grade === grade && from <= date && (!best || from > (best.from ?? ''))) best = r;
  }
  return best && (best.to === null || date <= best.to) ? best.rate : undefined;
}

/** Shown in place of a due whose grade has no rate. */
export const NO_RATE = 'n/a';

/** Hundredths of a decimal with up to two decimals, e.g. "12.5" is 1250 and "-0.3" is -30. */
export function hundredths(value: string): bigint {
  const negative = value.startsWith('-');
  const [whole, frac = ''] = (negative ? value.slice(1) : value).split('.');
  const abs = BigInt(whole) * 100n + BigInt(frac.padEnd(2, '0'));
  return negative ? -abs : abs;
}

/** Hundredths as a decimal with two decimals, e.g. -30 is "-0.30". */
export function fromHundredths(value: bigint): string {
  const abs = value < 0n ? -value : value;
  return `${value < 0n ? '-' : ''}${abs / 100n}.${String(abs % 100n).padStart(2, '0')}`;
}

/**
 * What is due on an amount at a rate: the amount times the rate, divided by 100, rounded to two
 * decimals, a half to the even cent, or up when `halfUp`. Worked in whole numbers so no rounding
 * creeps in; n/a when there is no rate.
 */
export function dueOf(amount: string, rate: string | undefined, halfUp = false): string {
  if (rate === undefined) return NO_RATE;
  // cents × hundredths of a percent is in ten-thousandths of a cent; round to cents, a half to the even
  // cent (or up), worked on the size so a correction's negative amount rounds the same way.
  const exact = hundredths(amount) * hundredths(rate);
  const size = exact < 0n ? -exact : exact;
  const rest = size % 10000n;
  let cents = size / 10000n;
  if (rest > 5000n || (rest === 5000n && (halfUp || cents % 2n === 1n))) cents += 1n;
  return fromHundredths(exact < 0n ? -cents : cents);
}

/** A due raised to the floor when it is below it; n/a stays n/a. */
export function floored(due: string, floor: string): string {
  if (due === NO_RATE) return due;
  return hundredths(due) < hundredths(floor) ? floor : due;
}

// The rate for each grade on the active sheet, read and changed through the server.
@Injectable({ providedIn: 'root' })
export class RatesApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  list(sheetId: string): Observable<Rate[]> {
    return this.http.get<Rate[]>(this.url(sheetId), { headers: this.headers() });
  }

  set(sheetId: string, grade: Grade, rate: string, from: string | null, to: string | null): Observable<Rate> {
    return this.http.put<Rate>(`${this.url(sheetId)}/${grade}`, { rate, from, to }, { headers: this.headers() });
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/rates`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}
