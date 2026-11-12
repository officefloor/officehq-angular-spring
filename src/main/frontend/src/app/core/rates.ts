import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

export interface Rate {
  grade: string;
  /** A percent with two decimals, as the server stores it. */
  rate: string;
}

/** What due shows for a grade with no rate. */
export const NO_RATE = 'n/a';

// The active sheet's rate for each grade, re-read from the server each time the sheet is loaded.
// Due is worked out in whole cents and hundredths of a percent, with BigInt, so it is exact.
@Injectable({ providedIn: 'root' })
export class Rates {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly rates = signal<Rate[]>([]);
  private loads = 0;

  /** Each grade's rate in hundredths of a percent, by grade. */
  private readonly basisPoints = computed(() => new Map(this.rates().map((r) => [r.grade, hundredths(r.rate)])));

  /** A grade's rate with two decimals, or n/a when it has none. */
  current(grade: string): string {
    return this.rates().find((r) => r.grade === grade)?.rate ?? NO_RATE;
  }

  /** An amount in cents times the grade's rate, divided by 100, rounded half up to cents; null
   *  when the grade has no rate. */
  dueCents(amountCents: bigint, grade: string): bigint | null {
    const bp = this.basisPoints().get(grade);
    return bp === undefined ? null : (amountCents * bp + 5000n) / 10000n;
  }

  /** An amount written with two decimals times the grade's rate, as `dueCents`, shown with two
   *  decimals or n/a. */
  due(amount: string, grade: string): string {
    const d = this.dueCents(hundredths(amount), grade);
    return d === null ? NO_RATE : money(d);
  }

  /** Re-read the active sheet's rates from the server; an error message when that failed. */
  async load(): Promise<string | null> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    const load = ++this.loads;
    this.rates.set([]);
    if (!user || !sid) return null;
    try {
      const rates = await firstValueFrom(this.http.get<Rate[]>(this.url(sid), { headers: { 'X-User': user } }));
      if (load === this.loads) this.rates.set(rates);
      return null;
    } catch (e) {
      return failed('load', e).replace(' rate', ' rates');
    }
  }

  /** Set a grade's rate; an error message when refused. */
  async set(grade: string, pct: string): Promise<string | null> {
    if (!this.session.user() || !this.session.sheetId()) return 'Load a sheet first.';
    if (!/^\d+(\.\d{1,2})?$/.test(pct.trim())) return 'Enter a rate of 0.00 or more, with up to two decimals.';
    const sid = this.session.sheetId()!;
    try {
      const rate = await firstValueFrom(
        this.http.put<Rate>(`${this.url(sid)}/${encodeURIComponent(grade)}`, { rate: pct.trim() }, {
          headers: { 'X-User': this.session.user()! },
        }),
      );
      if (this.session.sheetId() === sid) {
        this.rates.update((rs) => [...rs.filter((r) => r.grade !== rate.grade), rate].sort((a, b) => a.grade.localeCompare(b.grade)));
      }
      return null;
    } catch (e) {
      return failed('set', e);
    }
  }

  private url(sid: string): string {
    return `/api/sheets/${encodeURIComponent(sid)}/rates`;
  }
}

/** A number written with up to two decimals, in hundredths. */
export function hundredths(s: string): bigint {
  const [whole, frac = ''] = s.split('.');
  return BigInt(whole) * 100n + BigInt((frac + '00').slice(0, 2));
}

/** Hundredths written with two decimals. */
export function money(h: bigint): string {
  return `${h / 100n}.${String(h % 100n).padStart(2, '0')}`;
}

function failed(what: string, e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a rate of 0.00 or more, with up to two decimals.';
  return e instanceof HttpErrorResponse ? `Could not ${what} rate (${e.status}).` : `Could not ${what} rate.`;
}
