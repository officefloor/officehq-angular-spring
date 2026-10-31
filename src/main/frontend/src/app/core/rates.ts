import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { Grade } from './people';

export interface RateView {
  grade: Grade;
  /** The percent with exactly two decimals, e.g. "12.50". */
  rate: string;
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

// The rate set for each grade on the active sheet; a grade with none has no rate.
@Injectable({ providedIn: 'root' })
export class Rates {
  private readonly http = inject(HttpClient);
  private readonly list = signal<RateView[]>([]);
  /** The active sheet's rates by grade. */
  readonly byGrade = computed(() => new Map(this.list().map((r) => [r.grade, r.rate])));

  /** Re-read a sheet's rates from the store; null clears them. */
  load(sheetId: string | null): Observable<RateView[]> | null {
    this.list.set([]);
    if (sheetId === null) return null;
    return this.http.get<RateView[]>(this.base(sheetId)).pipe(tap((rates) => this.list.set(rates)));
  }

  set(sheetId: string, grade: Grade, rate: string): Observable<RateView> {
    return this.http
      .put<RateView>(`${this.base(sheetId)}/${grade}`, { rate })
      .pipe(tap((saved) => this.list.update((list) => [...list.filter((r) => r.grade !== saved.grade), saved])));
  }

  private base(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/rates`;
  }
}
