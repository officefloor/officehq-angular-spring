import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Grade } from './people.service';

export interface Rate {
  grade: Grade;
  /** The percent with two decimals, as text (e.g. "12.50"). */
  pct: string;
}

/** What is due on an amount at a rate: amount times pct, divided by 100, rounded half up to two
 *  decimals; null when there is no rate. Worked in whole cents and hundredths of a percent, so it
 *  is exact. */
export function dueCents(amountCents: number, pct: string | null): number | null {
  if (pct === null) return null;
  const product = BigInt(amountCents) * BigInt(Math.round(Number(pct) * 100));
  return Number((product + 5000n) / 10000n);
}

// The rates of the grades on a sheet, read from and written to the server.
@Injectable({ providedIn: 'root' })
export class RatesService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  list(sheet: string): Promise<Rate[]> {
    return firstValueFrom(this.http.get<Rate[]>(this.url(sheet), { headers: this.session.headers() }));
  }

  set(sheet: string, grade: Grade, pct: string): Promise<Rate> {
    return firstValueFrom(
      this.http.put<Rate>(`${this.url(sheet)}/${grade}`, { pct }, { headers: this.session.headers() }),
    );
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/rates`;
  }
}
