import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Claim, cents, isCorrection, isOpen } from './claims.service';
import { Grade, PersonGrade, gradeOn } from './people.service';

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

/** What decides how a claim's live figures round: the sheet's policy date in force (the one set, or
 *  the sheet's today; null until read) and the claims of the claim's person, which a correction
 *  looks up the claim it corrects in. */
export interface Rounding {
  policyDate: string | null;
  claims: Claim[];
}

/** Whether the claim's live figures round half up: it is dated before the policy date (a correction
 *  by the date of the claim it corrects); otherwise they round half to even. */
export function roundsHalfUp(claim: Claim, rounding: Rounding): boolean {
  let date = claim.date;
  if (isCorrection(claim) && claim.corrects !== null) {
    date = rounding.claims.find((c) => c.number === claim.corrects)?.date ?? date;
  }
  return rounding.policyDate !== null && date < rounding.policyDate;
}

/** What is due on an amount at a rate: amount times pct, divided by 100, rounded to two decimals
 *  half to even, or half up (away from zero) when halfUp; null when there is no rate. Worked in
 *  whole cents and hundredths of a percent, so it is exact. */
export function dueCents(amountCents: number, pct: string | null, halfUp = false): number | null {
  if (pct === null) return null;
  const product = BigInt(amountCents) * BigInt(Math.round(Number(pct) * 100));
  const negative = product < 0n;
  const abs = negative ? -product : product;
  let quotient = abs / 10000n;
  const remainder = abs % 10000n;
  if (remainder > 5000n || (remainder === 5000n && (halfUp || quotient % 2n === 1n))) quotient += 1n;
  return Number(negative ? -quotient : quotient);
}

/** What is due on a claim, in cents: a claim settled at a rate keeps what was due on it then; a
 *  claim a statement bound keeps, unless it is PENDING (no statement binds it while it is), the figure it was bound to (null when that was none); otherwise
 *  what is due on its amount at the rate in force on the claim's date of the grade in force
 *  for its person then, rounded as the claim rounds (see roundsHalfUp), raised to
 *  the sheet's floor (in cents) when below it (a correction has no floor), null when no rate applies
 *  then. */
export function claimDueCents(
  claim: Claim,
  rates: Rate[],
  grades: PersonGrade[],
  floorCents: number,
  rounding: Rounding,
): number | null {
  if (claim.status === 'SETTLED' && claim.settledDue !== null) return cents(claim.settledDue);
  if (claim.stated && claim.status !== 'PENDING') return claim.statedDue === null ? null : cents(claim.statedDue);
  const grade = gradeOn(grades, claim.date);
  const due = grade === null ? null : dueCents(cents(claim.amount), rateOn(rates, grade, claim.date), roundsHalfUp(claim, rounding));
  return due === null ? null : isCorrection(claim) ? due : Math.max(due, floorCents);
}

/** What a late charge is worked from: the sheet's today and its late percent
 *  with two decimals. */
export interface Lateness {
  today: string | null;
  latePct: string;
}

/** The late charge on a claim whose figure is figureCents, in cents: the late percent of that figure
 *  for every full 30 days the claim is overdue (OPEN or PENDING, and past its due-by on the sheet's today),
 *  rounded like the claim's due; 0 on any other claim, and on a correction. A statement never binds it; settling fixes it in. */
export function lateChargeCents(claim: Claim, figureCents: number, late: Lateness, rounding: Rounding): number {
  if (!isOpen(claim) || late.today === null || isCorrection(claim)) return 0;
  const days = (Date.parse(late.today) - Date.parse(claim.dueBy)) / 86_400_000;
  const periods = Math.max(0, Math.floor(days / 30));
  return dueCents(figureCents * periods, late.latePct, roundsHalfUp(claim, rounding)) ?? 0;
}

/** What is due on a claim as it shows and totals, in cents: its figure (see claimDueCents) plus its
 *  late charge; null when it has no figure. */
export function claimShownCents(
  claim: Claim,
  rates: Rate[],
  grades: PersonGrade[],
  floorCents: number,
  late: Lateness,
  rounding: Rounding,
): number | null {
  const figure = claimDueCents(claim, rates, grades, floorCents, rounding);
  return figure === null ? null : figure + lateChargeCents(claim, figure, late, rounding);
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
