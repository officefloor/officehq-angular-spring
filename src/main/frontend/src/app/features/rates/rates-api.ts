import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A percent of 0.00 or more with up to two decimals. */
export const PCT_PATTERN = /^\d{1,3}(\.\d{1,2})?$/;

export interface Rate {
  grade: string;
  /** Always with two decimals, as the server formats it. */
  pct: string;
}

// The sheet's rate for each grade, read and written through the server.
@Injectable({ providedIn: 'root' })
export class RatesApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  list(sheetId: string): Promise<Rate[]> {
    return firstValueFrom(this.http.get<Rate[]>(this.url(sheetId), { headers: this.headers() }));
  }

  set(sheetId: string, grade: string, pct: string): Promise<Rate> {
    return firstValueFrom(
      this.http.put<Rate>(`${this.url(sheetId)}/${encodeURIComponent(grade)}`, { pct }, { headers: this.headers() }),
    );
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/rates`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}

export function rateError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Choose a grade and enter a percent of 0.00 or more with up to two decimals.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'The rate was changed at the same moment; try again.';
  return 'Something went wrong; try again.';
}

/** Whole cents times a percent (in hundredths of a percent), divided by 100, in whole cents,
 *  rounded half up (away from zero). Worked in BigInt so large amounts stay exact. */
export function dueCents(cents: number, pctHundredths: number): number {
  const product = BigInt(cents) * BigInt(pctHundredths);
  return Number((product + 5000n) / 10000n);
}
