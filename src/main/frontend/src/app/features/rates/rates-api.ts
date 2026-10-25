import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Grade } from '../people/people-api';

export interface RateView {
  grade: Grade;
  /** The percent with two decimals, as the store holds it. */
  pct: string;
}

/** What is due on an amount of `amountCents` at a rate of `pct` percent, in whole cents: the
 *  amount times the rate, divided by 100, rounded half up (a half rounds away from zero). */
export function dueCents(amountCents: number, pct: string): number {
  // Hundredths of a percent, so the product is exact; cents x basis points / 10000 = due cents.
  const product = BigInt(amountCents) * BigInt(Math.round(Number(pct) * 100));
  const sign = product < 0n ? -1n : 1n;
  return Number((sign * product + 5000n) / 10000n * sign);
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

  set(sheetId: string, grade: Grade, pct: string): Promise<RateView> {
    return firstValueFrom(this.http.put<RateView>(`${this.url(sheetId)}/${grade}`, { pct }));
  }
}
