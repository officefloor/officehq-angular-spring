import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Session } from '../../core/session';
import { Grade } from '../people/people-api';
import { NO_RATE, Rate, dueOf, floored, fromHundredths, hundredths, rateOn } from '../rates/rates-api';

export interface Claim {
  id: number;
  personId: number;
  /** With two decimals, e.g. "10.00". */
  amount: string;
  /** yyyy-mm-dd */
  date: string;
  note: string;
  status: ClaimStatus;
  /** The sheet's number for the claim, given when it is opened; null while a draft or once voided. */
  number: string | null;
  /** What was due when the claim was settled, with two decimals; null while it is live (not settled, or
   * settled when its grade had no rate). */
  due: string | null;
  /** Who opened the claim; null while it is a draft. */
  openedBy: string | null;
  /** Whether the claim is bound to the figure it had at its person's last statement. */
  stated: boolean;
  /** That bound figure, with two decimals; null when it was n/a or the claim is not bound. */
  statedDue: string | null;
  /** For a correction (a negative amount), the number of the settled claim it corrects; null otherwise. */
  corrects: string | null;
}

/** Whether a claim is a correction: its amount is negative. */
export function isCorrection(claim: Claim): boolean {
  return claim.amount.startsWith('-');
}

/** A claim starts as DRAFT, is opened and then settled; one a clerk settles above the clerk limit is PENDING
 * until a steward approves it (SETTLED) or rejects it (OPEN again). A DRAFT, OPEN or PENDING claim can be voided. */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'PENDING' | 'SETTLED' | 'VOID';

/** The status changes a person can ask for on a claim; a rejection also needs a reason. */
export type ClaimAction = 'open' | 'settle' | 'approve' | 'void';

/** Whether a claim is still open: OPEN, or PENDING a steward. */
export function isOpen(claim: Claim): boolean {
  return claim.status === 'OPEN' || claim.status === 'PENDING';
}

/** Only OPEN, PENDING and SETTLED claims count toward what is claimed and due. */
export function counts(claim: Claim): boolean {
  return isOpen(claim) || claim.status === 'SETTLED';
}

/** Whether a claim goes by the figure its person's last statement bound it to: a PENDING claim is not bound, but
 * shows its live figure. */
function bound(claim: Claim): boolean {
  return claim.stated && claim.status !== 'PENDING';
}

/** What a claim's late charge goes by: the sheet's today, its terms and its late percent; and the policy date
 * its rounding goes by, the sheet's today when there is none. */
export interface Lateness {
  today: string | null;
  terms: number;
  /** With two decimals, e.g. "10.00". */
  latePct: string;
  policyDate: string | null;
}

/** The date a claim's rounding goes by: a correction's is the date of the claim of its person's (`claims`) it
 * corrects, any other's its own. */
export function roundsBy(claim: Claim, claims: readonly Claim[]): string {
  if (claim.corrects === null) return claim.date;
  return claims.find((c) => c.number === claim.corrects)?.date ?? claim.date;
}

/** Whether a claim's live figures round half up: the date they go by is before the policy date. Otherwise
 * they round half to even. */
function roundsHalfUp(date: string, late: Lateness): boolean {
  return late.policyDate !== null && date < late.policyDate;
}

/** The days in each period an overdue claim gains the late percent for. */
const LATE_PERIOD_DAYS = 30;

/** A settled claim's due is the figure it was settled at (its late charge frozen in); a claim bound by its
 * person's last statement (and not PENDING) has the figure it was bound to; any other has the figure due at the rate `grade`, the
 * one its person has on the claim's date, has now in force on that date, and at least the sheet's floor (n/a
 * when there is no rate). An overdue claim's due adds its late charge to its figure. Live figures round by the
 * date `roundsBy` (see {@link roundsBy}). */
export function claimDue(
  claim: Claim,
  rates: readonly Rate[],
  grade: Grade | undefined,
  floor: string,
  late: Lateness,
  roundsBy: string = claim.date,
): string {
  if (claim.due !== null) return claim.due;
  const halfUp = roundsHalfUp(roundsBy, late);
  const figure = bound(claim)
    ? (claim.statedDue ?? NO_RATE)
    : correctionFloored(claim, dueOf(claim.amount, grade && rateOn(rates, grade, claim.date), halfUp), floor);
  if (figure === NO_RATE) return figure;
  return sumAmounts([figure, lateCharge(claim, figure, late, halfUp)]);
}

/** A due raised to the sheet's floor, except a correction's, which has no floor. */
function correctionFloored(claim: Claim, due: string, floor: string): string {
  return isCorrection(claim) ? due : floored(due, floor);
}

/** The late charge on an overdue claim: the late percent of its figure for every full 30 days it is overdue,
 * rounded like a due; never bound by a statement, it follows the sheet's today. A correction has none. */
function lateCharge(claim: Claim, figure: string, late: Lateness, halfUp: boolean): string {
  if (isCorrection(claim) || !isOverdue(claim, late.terms, late.today) || late.today === null) return '0.00';
  const days = (Date.parse(late.today) - Date.parse(dueBy(claim, late.terms))) / 86_400_000;
  const periods = Math.floor(days / LATE_PERIOD_DAYS);
  return dueOf(figure, timesPct(late.latePct, periods), halfUp);
}

/** A percent with two decimals times a whole number, with two decimals. */
function timesPct(pct: string, times: number): string {
  const [whole, frac = ''] = pct.split('.');
  const hundredths = (BigInt(whole) * 100n + BigInt(frac.padEnd(2, '0'))) * BigInt(times);
  return `${hundredths / 100n}.${String(hundredths % 100n).padStart(2, '0')}`;
}

/** Whether a claim keeps a figure whatever the rates: settled at one, or bound to one (not n/a) by a
 * statement while not PENDING. */
export function keepsFigure(claim: Claim): boolean {
  return claim.due !== null || (bound(claim) && claim.statedDue !== null);
}

/** The date a claim is due by: its date plus the sheet's terms in days (yyyy-mm-dd). */
export function dueBy(claim: Claim, terms: number): string {
  const [y, m, d] = claim.date.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + terms)).toISOString().slice(0, 10);
}

/** Whether a claim is overdue: it is OPEN or PENDING and its due-by is before the sheet's today. */
export function isOverdue(claim: Claim, terms: number, today: string | null): boolean {
  return isOpen(claim) && today !== null && dueBy(claim, terms) < today;
}

export interface ClaimForm {
  amount: string;
  /** Null for the sheet's today. */
  date: string | null;
  note: string;
  /** The number of the settled claim a correction corrects; null when none is named. */
  corrects: string | null;
}

/** What opening claims together did: how many were opened and refused, and the opened claims. */
export interface OpenedAll {
  opened: number;
  refused: number;
  claims: Claim[];
}

/** The sum of claim amounts with two decimals, added up in cents so no rounding creeps in. */
export function sumAmounts(amounts: string[]): string {
  return fromHundredths(amounts.reduce((sum, a) => sum + hundredths(a), 0n));
}

// The claims people make on the active sheet, read and changed through the server.
@Injectable({ providedIn: 'root' })
export class ClaimsApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  list(sheetId: string): Observable<Claim[]> {
    return this.http.get<Claim[]>(`${this.url(sheetId)}/claims`, { headers: this.headers() });
  }

  add(sheetId: string, personId: number, form: ClaimForm): Observable<Claim> {
    return this.http.post<Claim>(`${this.url(sheetId)}/people/${personId}/claims`, form, {
      headers: this.headers(),
    });
  }

  update(sheetId: string, id: number, form: ClaimForm): Observable<Claim> {
    return this.http.put<Claim>(`${this.url(sheetId)}/claims/${id}`, form, { headers: this.headers() });
  }

  changeStatus(sheetId: string, id: number, action: ClaimAction): Observable<Claim> {
    return this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/${action}`, null, { headers: this.headers() });
  }

  /** Reject a PENDING claim with a reason: it is OPEN again. */
  reject(sheetId: string, id: number, reason: string): Observable<Claim> {
    return this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/reject`, { reason }, { headers: this.headers() });
  }

  /** Open the claims together; each that cannot be opened is refused and left as it is. */
  openAll(sheetId: string, ids: number[]): Observable<OpenedAll> {
    return this.http.post<OpenedAll>(`${this.url(sheetId)}/claims/open`, { ids }, { headers: this.headers() });
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}
