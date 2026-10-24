import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Grade } from './people.service';

export interface Rate {
  grade: Grade;
  /** The percent with two decimals, as text (e.g. "12.50"). */
  pct: string;
  /** The date the rate applies from (YYYY-MM-DD); null when it applies from the beginning of time. */
  from: string | null;
}

/** The grade's rate in force on a date: the one with the latest from-date on or before it, or the
 *  one with no from-date when none has; null when no rate applies yet. */
export function rateOn(rates: Rate[], grade: Grade, date: string): string | null {
  let best: Rate | null = null;
  for (const r of rates) {
    if (r.grade !== grade || (r.from !== null && r.from > date)) continue;
    if (best === null || (best.from ?? '') < (r.from ?? '')) best = r;
  }
  return best?.pct ?? null;
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

  /** A null from sets the rate that applies from the beginning of time. */
  set(sheet: string, grade: Grade, pct: string, from: string | null): Promise<Rate> {
    return firstValueFrom(
      this.http.put<Rate>(`${this.url(sheet)}/${grade}`, { pct, from }, { headers: this.session.headers() }),
    );
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/rates`;
  }
}
