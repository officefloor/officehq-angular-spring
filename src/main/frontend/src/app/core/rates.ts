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
  /** The last day (yyyy-mm-dd) the rate applies; null when it has no end. */
  to: string | null;
  /** The sheet the rate is set on: 0 for this sheet, 1 for its parent, 2 for the parent's parent,
   *  and so on. */
  level: number;
  /** How many versions the rate has had; this is the newest, the one in force. */
  version: number;
  /** Where a forward-only rate (one saved with a from-date on or after the sheet's today) was saved
   *  in the order claims are added: it applies only to claims added after it. Null when it applies
   *  to every claim. */
  forwardAfter: number | null;
  /** The rate's older versions, the newest first. */
  older: RateVersion[];
}

/** An older version of a rate: its percent and to-date, and where it was saved when it was
 *  forward-only (see {@link Rate}). */
export interface RateVersion {
  pct: string;
  to: string | null;
  forwardAfter: number | null;
}

/** What a candidate rate would change: how many open and pending claims would show a different
 *  due, and the sum of the differences as two-decimal text (negative when lower). */
export interface RatePreview {
  count: number;
  diff: string;
}

/** The percent of a grade's rate in force on a day: on this sheet, or else, when it has none in
 *  force on it, on the nearest ancestor that has (see {@link levelPctOn}); undefined when none has. */
export function pctOn(rates: Rate[], grade: Grade, day: string): string | undefined {
  const levels = [...new Set(rates.map((r) => r.level))].sort((a, b) => a - b);
  for (const level of levels) {
    const pct = levelPctOn(rates.filter((r) => r.level === level), grade, day);
    if (pct !== undefined) return pct;
  }
  return undefined;
}

/** The percent of a grade's rate that applies to a claim: as {@link pctOn} on the claim's date, each
 *  rate taken as the newest of its versions that applies to the claim (a forward-only one only to a
 *  claim added after it was saved), and passed over when none does. */
export function pctFor(rates: Rate[], grade: Grade, claim: { date: string; added: number }): string | undefined {
  const applying = rates.flatMap((r) => {
    const version = [r, ...r.older].find((v) => v.forwardAfter === null || claim.added > v.forwardAfter);
    return version === undefined ? [] : [{ ...r, pct: version.pct, to: version.to }];
  });
  return pctOn(applying, grade, claim.date);
}

/** The percent of a grade's rate in force on a day among the rates of one sheet: the rate with the
 *  latest from-date on or before it, or else the one with no from-date; undefined when the day is
 *  before every rate, or after the to-date of that rate. */
function levelPctOn(rates: Rate[], grade: Grade, day: string): string | undefined {
  let best: Rate | undefined;
  for (const r of rates) {
    if (r.grade !== grade || (r.from !== null && r.from > day)) continue;
    if (best === undefined || (best.from ?? '') < (r.from ?? '')) best = r;
  }
  if (best?.to != null && best.to < day) return undefined;
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
  return weighted(hundredths(amount) * hundredths(pct), halfUp);
}

/** Amounts each times a percent divided by 100, added up exactly and then rounded to two decimals
 *  half to even. */
export function weightedSum(parts: { amount: string; pct: string }[]): string {
  return weighted(parts.reduce((sum, x) => sum + hundredths(x.amount) * hundredths(x.pct), 0n), false);
}

/** An amount's part at a share percent and a rate percent: amount times share divided by 100, times
 *  pct divided by 100, rounded once to two decimals half to even, or half up when `halfUp`. */
export function sharedDue(amount: string, share: string, pct: string, halfUp = false): string {
  return weighted(hundredths(amount) * hundredths(share) * hundredths(pct), halfUp, 100000000n);
}

/** A product of hundredths (by default two, so in ten-thousandths of a hundredth) rounded to
 *  two-decimal text: `per` units of the product make one hundredth. */
function weighted(signed: bigint, halfUp: boolean, per = 10000n): string {
  const exact = signed < 0n ? -signed : signed;
  let cents = exact / per;
  const rest = exact % per;
  const half = per / 2n;
  if (rest > half || (rest === half && (halfUp || cents % 2n === 1n))) cents += 1n;
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

  /** The rates set on the sheet (level 0), then those of its ancestors, nearest first. */
  list(sheetId: string): Promise<Rate[]> {
    return firstValueFrom(this.http.get<Rate[]>(this.url(sheetId)));
  }

  /** Set a grade's rate from a day (null: from the beginning of time) through a day (null: no
   *  end), replacing the one the grade had for that from-date. */
  set(grade: Grade, pct: string, from: string | null, to: string | null): Promise<Rate> {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    return firstValueFrom(
      this.http.put<Rate>(`${this.url(sheetId)}/${grade}`, { pct, from, to }, { headers: { 'X-User': user } }),
    );
  }

  /** Withdraw the newest version of a grade's rate for a from-date (null: from the beginning of
   *  time); the version before it applies again, or with none the rate is gone. */
  async withdraw(grade: Grade, from: string | null): Promise<void> {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    await firstValueFrom(
      this.http.delete(`${this.url(sheetId)}/${grade}`, {
        headers: { 'X-User': user },
        params: from === null ? {} : { from },
      }),
    );
  }

  /** Set a rate per line of grade,rate,from,to, all or none; how many were set. */
  importRates(text: string): Promise<{ set: number }> {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    return firstValueFrom(
      this.http.post<{ set: number }>(`${this.url(sheetId)}/import`, { text }, { headers: { 'X-User': user } }),
    );
  }

  /** Preview a candidate rate applying to a grade on every date; nothing is saved. */
  preview(grade: Grade, pct: string): Promise<RatePreview> {
    const sheetId = this.session.sheetId();
    if (!sheetId) throw new Error('no sheet loaded');
    return firstValueFrom(
      this.http.get<RatePreview>(`${this.url(sheetId)}/${grade}/preview`, { params: { pct } }),
    );
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/rates`;
  }
}
