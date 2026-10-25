import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Claim, cents, deferred, isCorrection, isOpen } from './claims.service';
import { Grade, PersonGrade, gradeOn } from './people.service';
import { Dated, FLOOR, LATE_PCT, inForce } from './settings.service';

export interface Rate {
  grade: Grade;
  /** The percent with two decimals, as text (e.g. "12.50"). */
  pct: string;
  /** The date the rate applies from (YYYY-MM-DD); null when it applies from the beginning of time. */
  from: string | null;
  /** The last date the rate applies on (YYYY-MM-DD); null when it applies on with no end. */
  to: string | null;
  /** 0 for the sheet's own rate, 1 for its parent sheet's, 2 for its parent's parent's, and so on. */
  level: number;
  /** The newest version's number (the one in force), which is how many versions the rate has. */
  version: number;
  /** The versions as they apply to claims, newest first: each for the claims added after its mark (a
   *  claim id; null: every claim). A version saved forward-only (with a from-date on or after the
   *  sheet's today) is marked with the highest claim id there was then. */
  terms: RateTerm[];
}

/** A version of a rate as it applies to claims (see `Rate.terms`). */
export interface RateTerm {
  pct: string;
  to: string | null;
  mark: number | null;
}

/** The rates as they apply to a claim added at `added` (see `Claim.added`): each with its newest term
 *  that applies to the claim; a rate none of whose terms does is left out. */
export function ratesFor(rates: Rate[], added: number): Rate[] {
  const out: Rate[] = [];
  for (const r of rates) {
    const term = r.terms.find((t) => t.mark === null || added > t.mark);
    if (term !== undefined) out.push({ ...r, pct: term.pct, to: term.to });
  }
  return out;
}

/** How a rate import went: how many rates it set, and the rates, in the order of their lines. */
export interface RatesImported {
  set: number;
  rates: Rate[];
}

/** A withdrawn rate's grade and from-date, and the rate left there: its version before, or null when
 *  it had none and is gone. */
export interface RateWithdrawn {
  grade: Grade;
  from: string | null;
  rate: Rate | null;
}

/** A rate preview: the claims whose due would differ, and the sum of the differences with two decimals. */
export interface RatePreview {
  count: number;
  diff: string;
}

/** The grade's rate in force on a date on the sheet (see `levelRateOn`), or else on the first
 *  sheet it inherits from (its parents, then its source's) that has one in force then; null when none has. */
export function rateOn(rates: Rate[], grade: Grade, date: string): string | null {
  const levels = [...new Set(rates.map((r) => r.level ?? 0))].sort((a, b) => a - b);
  for (const level of levels) {
    const pct = levelRateOn(rates.filter((r) => (r.level ?? 0) === level), grade, date);
    if (pct !== null) return pct;
  }
  return null;
}

/** The grade's rate in force on a date among one sheet's rates: the one with the latest from-date on
 *  or before it, or the one with no from-date when none has; null when no rate applies yet, or when
 *  that rate's to-date is before the date (an earlier rate does not apply again). */
function levelRateOn(rates: Rate[], grade: Grade, date: string): string | null {
  let best: Rate | null = null;
  for (const r of rates) {
    if (r.grade !== grade || (r.from !== null && r.from > date)) continue;
    if (best === null || (best.from ?? '') < (r.from ?? '')) best = r;
  }
  if (best !== null && best.to !== null && best.to < date) return null;
  return best?.pct ?? null;
}

/** What decides how a claim's live figures round: every rounding mode recorded (half-up or
 *  half-even), each from its date, oldest first (null records none from then), the sheet's policy
 *  date in force (the one set, or the sheet's today; null until read) and the claims of the claim's
 *  person, which a correction looks up the claim it corrects in. */
export interface Rounding {
  modes: readonly Dated<string | null>[];
  policyDate: string | null;
  claims: Claim[];
}

/** Whether the claim's live figures round half up: by the rounding mode in force on its date when
 *  there is one; else when it is dated before the policy date (a correction by the date of the claim
 *  it corrects); otherwise they round half to even. */
export function roundsHalfUp(claim: Claim, rounding: Rounding): boolean {
  let date = claim.date;
  if (isCorrection(claim) && claim.corrects !== null) {
    date = rounding.claims.find((c) => c.number === claim.corrects)?.date ?? date;
  }
  const mode = inForce(rounding.modes, date, null);
  if (mode !== null) return mode === 'half-up';
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

/** Whether a claim keeps the figure fixed when it was settled at a rate: it is SETTLED, or was voided
 *  once settled, and has one. */
function isFrozen(claim: Claim): boolean {
  return claim.settledDue !== null && (claim.status === 'SETTLED' || claim.status === 'VOID');
}

/** The figure of a claim, in cents: a claim settled at a rate keeps what was due on it then (and once voided); a
 *  claim a statement bound keeps (PENDING or not) the figure it was bound to (null when that was none); otherwise
 *  what is due on its amount at the rate in force on the claim's date of the grade in force
 *  for its person then, rounded as the claim rounds (see roundsHalfUp) — the rate figure alone,
 *  before any late charge or floor (see claimShownCents); null when no rate applies then. A rate saved
 *  forward-only after the claim was added does not apply to it (see ratesFor). */
export function claimDueCents(
  claim: Claim,
  rates: Rate[],
  grades: PersonGrade[],
  rounding: Rounding,
): number | null {
  if (isFrozen(claim) && claim.settledDue !== null) return cents(claim.settledDue);
  if (claim.stated) return claim.statedDue === null ? null : cents(claim.statedDue);
  const grade = gradeOn(grades, claim.date);
  return grade === null ? null : dueCents(cents(claim.amount), rateOn(ratesFor(rates, claim.added), grade, claim.date), roundsHalfUp(claim, rounding));
}

/** A holder's part of a shared claim, in cents, by their share (a percent with two decimals): of a claim
 *  that keeps the figure fixed when it was settled, that figure (`shownCents`) times the share divided by
 *  100, rounded half to even; otherwise the claim's amount times the share divided by 100, times the rate
 *  in force on the claim's date of the holder's own grade in force then (`holderGrades`) divided by 100,
 *  rounded as the claim rounds (see roundsHalfUp). Null when the holder's grade has no rate then. */
export function sharePartCents(
  claim: Claim,
  share: string,
  shownCents: number,
  rates: Rate[],
  holderGrades: PersonGrade[] | null,
  rounding: Rounding,
): number | null {
  const shareHundredths = BigInt(cents(share));
  if (isFrozen(claim)) return roundedCents(BigInt(shownCents) * shareHundredths, 10000n, false);
  const grade = holderGrades === null ? null : gradeOn(holderGrades, claim.date);
  const pct = grade === null ? null : rateOn(ratesFor(rates, claim.added), grade, claim.date);
  if (pct === null) return null;
  const product = BigInt(cents(claim.amount)) * shareHundredths * BigInt(cents(pct));
  return roundedCents(product, 100000000n, roundsHalfUp(claim, rounding));
}

/** `numerator` divided by the positive `denominator`, rounded to a whole number half to even, or half
 *  up (away from zero) when halfUp. */
function roundedCents(numerator: bigint, denominator: bigint, halfUp: boolean): number {
  const negative = numerator < 0n;
  const abs = negative ? -numerator : numerator;
  let quotient = abs / denominator;
  const twice = (abs % denominator) * 2n;
  if (twice > denominator || (twice === denominator && (halfUp || quotient % 2n === 1n))) quotient += 1n;
  return Number(negative ? -quotient : quotient);
}

/** Every grace recorded for each grade, each from its date, oldest first, as a whole number of days
 *  in text; null records no grace from then. */
export type Graces = Partial<Record<Grade, readonly Dated<string | null>[]>>;

/** The date (YYYY-MM-DD) a claim's lateness counts from: its due-by plus the grace days in force on
 *  the claim's date of its person's grade in force then (its due-by when that grade has none). */
export function overdueFrom(claim: Claim, grades: PersonGrade[], graces: Graces): string {
  const grade = gradeOn(grades, claim.date);
  const grace = grade === null ? null : inForce(graces[grade] ?? [], claim.date, null);
  if (grace === null) return claim.dueBy;
  return new Date(Date.parse(claim.dueBy) + Number(grace) * 86_400_000).toISOString().slice(0, 10);
}

/** What a late charge is worked from: the sheet's today, every late percent recorded, oldest
 *  first, with two decimals, and every grace recorded for each grade. */
export interface Lateness {
  today: string | null;
  latePcts: readonly Dated[];
  graces: Graces;
}

/** The late charge on a claim whose figure is figureCents, in cents: for every full 30 days the claim
 *  is overdue (OPEN or PENDING, not deferred, and past its due-by plus any grace of its grade on the
 *  sheet's today, see overdueFrom), the late percent in force on that boundary date (that date plus
 *  30, 60, 90 ... days) of that figure plus the charges of the boundaries before it (compounding), rounded
 *  like the claim's due, the charges added up (a boundary whose percent is 0.00 adds nothing); 0 on any other claim, and on a correction. A statement binds it (see claimShownCents); settling fixes it in. */
export function lateChargeCents(
  claim: Claim,
  figureCents: number,
  late: Lateness,
  rounding: Rounding,
  grades: PersonGrade[],
): number {
  if (!isOpen(claim) || late.today === null || isCorrection(claim) || deferred(claim, late.today)) return 0;
  const dueBy = Date.parse(overdueFrom(claim, grades, late.graces));
  const days = (Date.parse(late.today) - dueBy) / 86_400_000;
  const periods = Math.max(0, Math.floor(days / 30));
  let charge = 0;
  for (let k = 1; k <= periods; k++) {
    const boundary = new Date(dueBy + k * 30 * 86_400_000).toISOString().slice(0, 10);
    const latePct = inForce(late.latePcts, boundary, LATE_PCT);
    if (Number(latePct) === 0) continue;
    charge += dueCents(figureCents + charge, latePct, roundsHalfUp(claim, rounding)) ?? 0;
  }
  return charge;
}

/** What is due on a claim as it shows and totals, in cents: a claim settled at a rate keeps what was
 *  due on it then; a claim a statement bound shows what was due on it as it showed then, late charge
 *  included; otherwise its figure (see claimDueCents), at least the sheet's floor in force on the
 *  claim's date, plus the late charge on that floored figure (a correction has neither); null when it has no
 *  figure. `floors` is every floor recorded, oldest first. */
export function claimShownCents(
  claim: Claim,
  rates: Rate[],
  grades: PersonGrade[],
  floors: readonly Dated[],
  late: Lateness,
  rounding: Rounding,
): number | null {
  const figure = claimDueCents(claim, rates, grades, rounding);
  if (figure === null || isCorrection(claim)) return figure;
  if (isFrozen(claim)) return figure;
  if (claim.stated && claim.statedShown !== null) return cents(claim.statedShown);
  const floored = Math.max(figure, cents(inForce(floors, claim.date, FLOOR)));
  return floored + lateChargeCents(claim, floored, late, rounding, grades);
}

// The rates of the grades on a sheet, read from and written to the server.
@Injectable({ providedIn: 'root' })
export class RatesService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  list(sheet: string): Promise<Rate[]> {
    return firstValueFrom(this.http.get<Rate[]>(this.url(sheet), { headers: this.session.headers() }));
  }

  /** A null from sets the rate that applies from the beginning of time; a null to, one with no end. */
  set(sheet: string, grade: Grade, pct: string, from: string | null, to: string | null): Promise<Rate> {
    return firstValueFrom(
      this.http.put<Rate>(`${this.url(sheet)}/${grade}`, { pct, from, to }, { headers: this.session.headers() }),
    );
  }

  /** Set a rate per pasted line of grade,rate,from,to, in order; when any line is refused none is set. */
  importRates(sheet: string, text: string): Promise<RatesImported> {
    return firstValueFrom(
      this.http.post<RatesImported>(`${this.url(sheet)}/import`, { text }, { headers: this.session.headers() }),
    );
  }

  /** Withdraw the newest version of the grade's rate from a date (null: from the beginning of time);
   *  refused unless the user is a steward. */
  withdraw(sheet: string, grade: Grade, from: string | null): Promise<RateWithdrawn> {
    return firstValueFrom(
      this.http.delete<RateWithdrawn>(`${this.url(sheet)}/${grade}`, {
        params: from === null ? {} : { from },
        headers: this.session.headers(),
      }),
    );
  }

  /** How many OPEN and PENDING claims of people on the grade would show a different due, and the sum
   *  of the differences, were pct the grade's rate on every date. Saves nothing. */
  preview(sheet: string, grade: Grade, pct: string): Promise<RatePreview> {
    return firstValueFrom(
      this.http.get<RatePreview>(`${this.url(sheet)}/${grade}/preview`, {
        params: { pct },
        headers: this.session.headers(),
      }),
    );
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/rates`;
  }
}
