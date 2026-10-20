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
  /** The date (yyyy-mm-dd) the claim is due by, fixed when it was added from the terms of its person's grade. */
  dueBy: string;
  /** The claim's status log, oldest first: one line per status it has had, from the DRAFT it was added as. */
  statusLog: StatusLogLine[];
  /** The date (yyyy-mm-dd) an OPEN claim is deferred until; null when it was never deferred. */
  deferredUntil: string | null;
  /** How many proofs the claim has. */
  proofs: number;
  /** For a claim split off another, the number of the claim it was split from; null otherwise. */
  splitFrom: string | null;
}

/** What a split did: the claim split, with what it keeps, and the new claim split off it. */
export interface Split {
  claim: Claim;
  into: Claim;
}

/** A line of a claim's status log: the status, who changed it, the sheet's today (yyyy-mm-dd) then, and the
 * claim's due as computed at that moment, with two decimals or n/a. */
export interface StatusLogLine {
  status: ClaimStatus;
  by: string;
  on: string;
  due: string;
}

/** Whether a claim is a correction: its amount is negative. */
export function isCorrection(claim: Claim): boolean {
  return claim.amount.startsWith('-');
}

/** A claim starts as DRAFT, is opened and then settled; one a clerk settles above the clerk limit is PENDING
 * until a steward approves it (SETTLED) or rejects it (OPEN again). A DRAFT, OPEN or PENDING claim can be voided, and a steward may void a SETTLED one
 * within 7 days of its settlement. */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'PENDING' | 'SETTLED' | 'VOID';

/** The status changes a person can ask for on a claim; a rejection also needs a reason. */
export type ClaimAction = 'open' | 'settle' | 'approve' | 'void';

/** Whether a claim is still open: OPEN, or PENDING a steward. */
export function isOpen(claim: Claim): boolean {
  return claim.status === 'OPEN' || claim.status === 'PENDING';
}

/** Whether a claim is deferred on the sheet's today: it is still open and today has not reached the date it is
 * deferred until. Such a claim counts in no total, is not overdue and cannot be settled. */
export function isDeferred(claim: Claim, today: string | null): boolean {
  return isOpen(claim) && claim.deferredUntil !== null && today !== null && today < claim.deferredUntil;
}

/** Only OPEN, PENDING and SETTLED claims count toward what is claimed and due. */
export function counts(claim: Claim): boolean {
  return isOpen(claim) || claim.status === 'SETTLED';
}

/** The status a claim had at the end of a day (yyyy-mm-dd), as its status log records it (its lines are dated by
 * the sheet's today when each change was made): that of the last line on or before it; null when the claim was
 * added after it. A claim with no log (one there before the log was kept) has the status it has now. */
export function statusAt(claim: Claim, date: string): ClaimStatus | null {
  if (claim.statusLog.length === 0) return claim.status;
  return claim.statusLog.filter((l) => l.on <= date).at(-1)?.status ?? null;
}

/** Whether a claim goes by the figure its person's last statement bound it to: a PENDING claim is not bound, but
 * shows its live figure. */
export function bound(claim: Claim): boolean {
  return claim.stated && claim.status !== 'PENDING';
}

/** What a claim's late charge goes by: the sheet's today and its late percent in force on each of a claim's 30-day boundaries; and
 * the policy date its rounding goes by, the sheet's today when there is none. */
export interface Lateness {
  today: string | null;
  /** The late percent in force on a date, with two decimals, e.g. "10.00". */
  latePct: (date: string) => string;
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

/** A settled claim's due is the figure it was settled at (its late charge frozen in). Any other's figure is the
 * one its person's last statement bound it to (when bound and not PENDING), else the figure due at the rate
 * `grade`, the one its person has on the claim's date, has now in force on that date (n/a when there is no rate);
 * its due is the greater of the sheet's floor in force on its date and that figure plus its late charge when
 * overdue. A correction's due is its figure alone (no floor, no charge). Live figures round by the date
 * `roundsBy` (see {@link roundsBy}). */
export function claimDue(
  claim: Claim,
  rates: readonly Rate[],
  grade: Grade | undefined,
  floor: (date: string) => string,
  late: Lateness,
  roundsBy: string = claim.date,
): string {
  if (claim.due !== null) return claim.due;
  const halfUp = roundsHalfUp(roundsBy, late);
  const figure = bound(claim)
    ? (claim.statedDue ?? NO_RATE)
    : dueOf(claim.amount, grade && rateOn(rates, grade, claim.date), halfUp);
  if (figure === NO_RATE || isCorrection(claim)) return figure;
  return floored(sumAmounts([figure, lateCharge(claim, figure, late, halfUp)]), floor(claim.date));
}

/** The late charge on an overdue claim: for each full 30 days it is overdue, the late percent in force on that boundary
 * date (its due-by plus 30, 60, 90 ... days) of its figure, rounded like a due, the charges added up; a boundary whose
 * percent is 0.00 adds nothing. Never bound by a statement, it follows the sheet's today. A correction has none. */
function lateCharge(claim: Claim, figure: string, late: Lateness, halfUp: boolean): string {
  if (isCorrection(claim) || !isOverdue(claim, late.today) || late.today === null) return '0.00';
  const dueBy = Date.parse(claim.dueBy);
  const days = (Date.parse(late.today) - dueBy) / 86_400_000;
  const periods = Math.floor(days / LATE_PERIOD_DAYS);
  const charges: string[] = [];
  for (let k = 1; k <= periods; k++) {
    const boundary = new Date(dueBy + k * LATE_PERIOD_DAYS * 86_400_000).toISOString().slice(0, 10);
    const pct = late.latePct(boundary);
    if (hundredths(pct) !== 0n) charges.push(dueOf(figure, pct, halfUp));
  }
  return sumAmounts(charges);
}

/** Whether a claim is overdue: it is OPEN or PENDING, not deferred, and its due-by is before the sheet's today. */
export function isOverdue(claim: Claim, today: string | null): boolean {
  return isOpen(claim) && today !== null && claim.dueBy < today && !isDeferred(claim, today);
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

/** What a paste added: how many claims, and those claims in the order of their lines. */
export interface Pasted {
  added: number;
  claims: Claim[];
}

/** What settling claims together did: how many were settled (or sent PENDING) and refused, and those claims. */
export interface SettledAll {
  settled: number;
  refused: number;
  claims: Claim[];
}

/** The sum of claim amounts with two decimals, added up in cents so no rounding creeps in. */
export function sumAmounts(amounts: string[]): string {
  return fromHundredths(amounts.reduce((sum, a) => sum + hundredths(a), 0n));
}

/** Amounts added up, leaving out the single highest and the single lowest when there are three or more. */
export function trimmedSum(amounts: string[]): string {
  if (amounts.length < 3) return sumAmounts(amounts);
  const sorted = amounts.map(hundredths).sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
  return fromHundredths(sorted.slice(1, -1).reduce((sum, a) => sum + a, 0n));
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

  /** Defer an OPEN claim until a date after the sheet's today. */
  defer(sheetId: string, id: number, until: string): Observable<Claim> {
    return this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/defer`, { until }, { headers: this.headers() });
  }

  /** Split an amount (more than 0.00, less than its amount) off an OPEN claim into a new OPEN claim. */
  split(sheetId: string, id: number, amount: string): Observable<Split> {
    return this.http.post<Split>(`${this.url(sheetId)}/claims/${id}/split`, { amount }, { headers: this.headers() });
  }

  /** Raise a claim's proofs count by one; refused for a SETTLED or VOID claim. */
  addProof(sheetId: string, id: number): Observable<Claim> {
    return this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/proof`, {}, { headers: this.headers() });
  }

  /** Reject a PENDING claim with a reason: it is OPEN again. */
  reject(sheetId: string, id: number, reason: string): Observable<Claim> {
    return this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/reject`, { reason }, { headers: this.headers() });
  }

  /** A steward re-settles a SETTLED claim at the figure it would have today as an open claim bound by no
   * statement. */
  resettle(sheetId: string, id: number): Observable<Claim> {
    return this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/resettle`, {}, { headers: this.headers() });
  }

  /** Move a DRAFT, OPEN or PENDING claim to another person on the sheet; it is live again until their next
   * statement. */
  reassign(sheetId: string, id: number, personId: number): Observable<Claim> {
    return this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/reassign`, { personId }, {
      headers: this.headers(),
    });
  }

  /** Open the claims together; each that cannot be opened is refused and left as it is. */
  openAll(sheetId: string, ids: number[]): Observable<OpenedAll> {
    return this.http.post<OpenedAll>(`${this.url(sheetId)}/claims/open`, { ids }, { headers: this.headers() });
  }

  /** Add a draft claim per pasted line of name,amount,date,note; one line refused adds none. */
  paste(sheetId: string, text: string): Observable<Pasted> {
    return this.http.post<Pasted>(`${this.url(sheetId)}/paste-claims`, { text }, { headers: this.headers() });
  }

  /** Settle the claims together, each as a single settle would; each that cannot be settled is refused and left
   * as it is. */
  settleAll(sheetId: string, ids: number[]): Observable<SettledAll> {
    return this.http.post<SettledAll>(`${this.url(sheetId)}/claims/settle`, { ids }, { headers: this.headers() });
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}
