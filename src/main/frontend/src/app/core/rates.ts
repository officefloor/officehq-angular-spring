import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session, isDate } from './session';

export interface Rate {
  grade: string;
  /** The first day the rate applies (YYYY-MM-DD); null when it applies from the beginning of time. */
  from: string | null;
  /** The last day the rate applies (YYYY-MM-DD); null when it applies on for good. */
  to: string | null;
  /** A percent with two decimals, as the server stores it. */
  rate: string;
  /** How many versions the rate has had; the one shown is the newest. */
  version: number;
}

/** What due shows for a grade with no rate. */
export const NO_RATE = 'n/a';

// The active sheet's rates for each grade, re-read from the server each time the sheet is loaded.
// A grade may have several, each from a date; on a date the one in force is the one whose from-date
// is the latest on or before it, a rate with no from-date applying from the beginning of time; none
// is in force when the date is after that rate's to-date. A grade with none in force on a date takes the
// one in force then on the sheet's parent, then the parent's parent, up to three levels up.
// Due is worked out in whole cents and hundredths of a percent, with BigInt, so it is exact.
@Injectable({ providedIn: 'root' })
export class Rates {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** By grade, then from-date, the one with none first. */
  readonly rates = signal<Rate[]>([]);
  /** The rates of each of the sheet's ancestors, its parent first, each by grade then from-date. */
  readonly inherited = signal<Rate[][]>([]);
  private loads = 0;

  /** The grade's rate in force on a date (YYYY-MM-DD) on the sheet, else on the nearest ancestor with one,
   *  or undefined when none applies. */
  inForce(grade: string, date: string): Rate | undefined {
    for (const rates of [this.rates(), ...this.inherited()]) {
      const found = inForceAmong(rates, grade, date);
      if (found) return found;
    }
    return undefined;
  }

  /** A grade's rate in force on the sheet's today, with two decimals, or n/a when it has none. */
  current(grade: string): string {
    const today = this.session.today();
    return (today ? this.inForce(grade, today)?.rate : undefined) ?? NO_RATE;
  }

  /** An amount in cents times the grade's rate in force on a date, divided by 100, rounded to cents as
   *  a claim dated `roundOn` rounds (half up before the policy date, else half to even): the rate figure,
   *  before any late charge or floor; null when no rate of the grade applies then. */
  dueCents(amountCents: bigint, grade: string, date: string, roundOn: string = date): bigint | null {
    const rate = this.inForce(grade, date);
    if (rate === undefined) return null;
    return rounded(amountCents * hundredths(rate.rate), 10000n, this.session.roundsHalfUp(roundOn));
  }

  /** An amount written with two decimals times the grade's rate on a date, as `dueCents`, shown
   *  with two decimals or n/a. */
  due(amount: string, grade: string, date: string): string {
    const d = this.dueCents(hundredths(amount), grade, date);
    return d === null ? NO_RATE : money(d);
  }

  /** Re-read the active sheet's rates from the server; an error message when that failed. */
  async load(): Promise<string | null> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    const load = ++this.loads;
    this.rates.set([]);
    this.inherited.set([]);
    if (!user || !sid) return null;
    try {
      const [rates, inherited] = await Promise.all([
        firstValueFrom(this.http.get<Rate[]>(this.url(sid), { headers: { 'X-User': user } })),
        firstValueFrom(this.http.get<Rate[][]>(`${this.url(sid)}/inherited`, { headers: { 'X-User': user } })),
      ]);
      if (load === this.loads) {
        this.rates.set([...rates].sort(byGradeAndFrom));
        this.inherited.set(inherited.map((level) => [...level].sort(byGradeAndFrom)));
      }
      return null;
    } catch (e) {
      return failed('load', e).replace(' rate', ' rates');
    }
  }

  /** Set a grade's rate from a date (blank: from the beginning of time) through a to-date (blank:
   *  for good), replacing the one it had from that date; an error message when refused. */
  async set(grade: string, pct: string, from: string, to: string): Promise<string | null> {
    if (!this.session.user() || !this.session.sheetId()) return 'Load a sheet first.';
    if (!/^\d+(\.\d{1,2})?$/.test(pct.trim())) return 'Enter a rate of 0.00 or more, with up to two decimals.';
    if (from.trim() && !isDate(from.trim())) return 'Enter a from-date as YYYY-MM-DD, or leave it blank.';
    if (to.trim() && !isDate(to.trim())) return 'Enter a to-date as YYYY-MM-DD, or leave it blank.';
    if (to.trim() && from.trim() && to.trim() < from.trim()) return "A rate's to-date cannot be before its from-date.";
    const sid = this.session.sheetId()!;
    try {
      const rate = await firstValueFrom(
        this.http.put<Rate>(`${this.url(sid)}/${encodeURIComponent(grade)}`, { rate: pct.trim(), from: from.trim() || null, to: to.trim() || null }, {
          headers: { 'X-User': this.session.user()! },
        }),
      );
      if (this.session.sheetId() === sid) {
        this.rates.update((rs) =>
          [...rs.filter((r) => r.grade !== rate.grade || r.from !== rate.from), rate].sort(byGradeAndFrom),
        );
      }
      return null;
    } catch (e) {
      return failed('set', e);
    }
  }

  /** Set the rate each pasted line of grade,rate,from,to names (from and to may be left out or blank),
   *  in order, as the form would; how many were set, or why the import was refused (then none was). */
  async importRates(text: string): Promise<number | string> {
    const sid = this.session.sheetId();
    const user = this.session.user();
    if (!user || !sid) return 'Load a sheet first.';
    if (!text.trim()) return 'Paste at least one line of grade,rate,from,to.';
    try {
      const done = await firstValueFrom(
        this.http.post<{ set: number; rates: Rate[] }>(`${this.url(sid)}/import`, { text }, {
          headers: { 'X-User': user },
        }),
      );
      if (this.session.sheetId() === sid) this.rates.set([...done.rates].sort(byGradeAndFrom));
      return done.set;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 422 && typeof e.error?.message === 'string' && e.error.message) {
        return e.error.message;
      }
      return e instanceof HttpErrorResponse && e.status === 422
        ? 'A line could not be set; nothing was imported.'
        : failed('import', e).replace(' rate', ' rates');
    }
  }

  private url(sid: string): string {
    return `/api/sheets/${encodeURIComponent(sid)}/rates`;
  }
}

/** The grade's rate in force on a date among one sheet's rates (by grade then from-date), or undefined. */
function inForceAmong(rates: Rate[], grade: string, date: string): Rate | undefined {
  let found: Rate | undefined;
  for (const r of rates) {
    if (r.grade === grade && (r.from === null || r.from <= date)) found = r;
  }
  return found && (found.to === null || date <= found.to) ? found : undefined;
}

/** By grade, then from-date, the one with none first. */
function byGradeAndFrom(a: Rate, b: Rate): number {
  return a.grade.localeCompare(b.grade) || (a.from ?? '').localeCompare(b.from ?? '');
}

/** A number divided by a positive one, rounded half to even (a negative number as its opposite). */
export function halfEven(n: bigint, d: bigint): bigint {
  if (n < 0n) return -halfEven(-n, d);
  const q = n / d;
  const twice = (n % d) * 2n;
  return twice > d || (twice === d && q % 2n === 1n) ? q + 1n : q;
}

/** A number divided by a positive one, rounded half up (away from zero) or half to even. */
export function rounded(n: bigint, d: bigint, halfUp: boolean): bigint {
  if (!halfUp) return halfEven(n, d);
  if (n < 0n) return -rounded(-n, d, true);
  const q = n / d;
  return (n % d) * 2n >= d ? q + 1n : q;
}

/** A number written with up to two decimals, perhaps negative, in hundredths. */
export function hundredths(s: string): bigint {
  if (s.startsWith('-')) return -hundredths(s.slice(1));
  const [whole, frac = ''] = s.split('.');
  return BigInt(whole) * 100n + BigInt((frac + '00').slice(0, 2));
}

/** Hundredths written with two decimals. */
export function money(h: bigint): string {
  if (h < 0n) return `-${money(-h)}`;
  return `${h / 100n}.${String(h % 100n).padStart(2, '0')}`;
}

function failed(what: string, e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a rate of 0.00 or more, with up to two decimals.';
  if (e instanceof HttpErrorResponse && e.status === 422) return "A rate's to-date cannot be before its from-date.";
  return e instanceof HttpErrorResponse ? `Could not ${what} rate (${e.status}).` : `Could not ${what} rate.`;
}
