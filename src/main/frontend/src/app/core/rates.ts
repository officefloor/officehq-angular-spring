import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { money } from './claims';
import { Grade } from './people';

export interface RateView {
  grade: Grade;
  /** The percent with two decimals, e.g. "12.50". */
  pct: string;
}

/** A percent as a rate takes it: 0.00 or more, with up to two decimals. */
export const PERCENT = /^\d{1,3}(\.\d{1,2})?$/;

/** Shown in place of a due when the grade has no rate. */
export const NO_RATE = 'n/a';

/**
 * What is due on an amount in whole cents at a rate (a two-decimal percent): the amount times the
 * rate divided by 100, rounded half up to whole cents. Worked in integers so it is exact.
 */
export function dueCents(amountCents: number, pct: string): number {
  const [whole, frac = ''] = pct.split('.');
  const basisPoints = BigInt(whole) * 100n + BigInt(frac.padEnd(2, '0'));
  return Number((BigInt(amountCents) * basisPoints + 5000n) / 10000n);
}

/** What is due on an amount in whole cents at a grade's rate, shown with two decimals; n/a with no rate. */
export function due(amountCents: number, pct: string | null): string {
  return pct === null ? NO_RATE : money(dueCents(amountCents, pct));
}

// The rates for the grades on a sheet, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class RatesApi {
  private readonly http = inject(HttpClient);

  /** The sheet's rates; a grade with no rate is not listed. */
  list(sheet: string): Promise<RateView[]> {
    return firstValueFrom(this.http.get<RateView[]>(this.url(sheet)));
  }

  set(sheet: string, grade: Grade, pct: string): Promise<RateView> {
    return firstValueFrom(this.http.put<RateView>(`${this.url(sheet)}/${grade}`, { pct }));
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/rates`;
  }
}
