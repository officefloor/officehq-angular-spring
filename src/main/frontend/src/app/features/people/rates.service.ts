import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Claim, cents } from './claims.service';
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

/** What is due on an amount at a rate: amount times pct, divided by 100, rounded half to even to
 *  two decimals; null when there is no rate. Worked in whole cents and hundredths of a percent, so
 *  it is exact. */
export function dueCents(amountCents: number, pct: string | null): number | null {
  if (pct === null) return null;
  const product = BigInt(amountCents) * BigInt(Math.round(Number(pct) * 100));
  const negative = product < 0n;
  const abs = negative ? -product : product;
  let quotient = abs / 10000n;
  const remainder = abs % 10000n;
  if (remainder > 5000n || (remainder === 5000n && quotient % 2n === 1n)) quotient += 1n;
  return Number(negative ? -quotient : quotient);
}

/** What is due on a claim, in cents: a claim settled at a rate keeps what was due on it then; a
 *  claim a statement bound keeps the figure it was bound to (null when that was none); otherwise
 *  what is due on its amount at its person's grade's rate in force on the claim's date, null when
 *  none applies then. */
export function claimDueCents(claim: Claim, rates: Rate[], grade: Grade): number | null {
  if (claim.status === 'SETTLED' && claim.settledDue !== null) return cents(claim.settledDue);
  if (claim.stated) return claim.statedDue === null ? null : cents(claim.statedDue);
  return dueCents(cents(claim.amount), rateOn(rates, grade, claim.date));
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
