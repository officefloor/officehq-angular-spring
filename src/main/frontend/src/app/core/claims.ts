import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Grade, Hold, Person, heldOn } from './people';
import { Rate, atLeast, due, pctOn } from './rates';
import { DatedValue, STARTING_FLOOR, STARTING_LATE_PCT, valueOn } from './settings';
import { Session } from './session';

/** Where a claim is in its life: DRAFT, then OPEN, then SETTLED; a clerk's settle above the clerk
 *  limit leaves it PENDING until a steward approves it (SETTLED) or rejects it (OPEN again); a
 *  DRAFT, OPEN or PENDING claim may be VOID, and a steward may void a SETTLED one within 7 days of
 *  its settlement. */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'PENDING' | 'SETTLED' | 'VOID';

/** A change of status a claim can be asked for. */
export type ClaimAction = 'open' | 'settle' | 'resettle' | 'void' | 'approve' | 'reject';

/** Whether a claim is open: OPEN, or PENDING approval of its settle. */
export function isOpen(c: Claim): boolean {
  return c.status === 'OPEN' || c.status === 'PENDING';
}

/** Whether a claim at this status counts toward claimed, due-total and every total. */
export function counts(c: Claim): boolean {
  return isOpen(c) || c.status === 'SETTLED';
}

/** Whether a claim is deferred on the sheet's today: it is OPEN and today is before the day it is
 *  deferred until. Until then it counts in no total, is not overdue and cannot be settled. */
export function deferred(c: Claim, today: string | null): boolean {
  return c.status === 'OPEN' && c.deferredUntil !== null && today !== null && today < c.deferredUntil;
}

/** Whether a claim counts in the live totals on the sheet's today: it counts, it is not deferred,
 *  and it is not an open (or pending) claim, unbound by a statement, dated within one of its
 *  person's holds. */
export function countsLive(c: Claim, holds: Hold[], today: string | null): boolean {
  return counts(c) && !deferred(c, today) && !(isOpen(c) && !c.stated && heldOn(holds, c.date));
}

/** The day (yyyy-mm-dd) a number of days after another (before it when negative). */
export function addDays(day: string, days: number): string {
  const d = new Date(day + 'T00:00:00Z');
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** Whether a claim keeps its person active on the sheet's today: it is open (or pending), it
 *  counts in the live totals, and it is dated within the 30 days up to and including today. */
export function keepsActive(c: Claim, holds: Hold[], today: string | null): boolean {
  return today !== null && isOpen(c) && countsLive(c, holds, today)
    && addDays(today, -29) <= c.date && c.date <= today;
}

/** The status a claim had at the end of the day, as its status log records it: that of the last
 *  line logged on or before the day; null when it was not yet made then. A claim with no log has
 *  its status now. */
export function statusOn(c: Claim, day: string): ClaimStatus | null {
  if (c.log.length === 0) return c.status;
  let then: ClaimStatus | null = null;
  for (const line of c.log) if (line.on <= day) then = line.status;
  return then;
}

/** Whether a claim counts in a period's totals: by the status it had at the end of the period's
 *  last day it counts, and the live rules do not leave it out: it is not deferred on the sheet's
 *  today, and it is not a claim open (or pending) now, unbound by a statement, dated within one of
 *  its person's holds. */
export function countsAsOf(c: Claim, holds: Hold[], end: string, today: string | null): boolean {
  const then = statusOn(c, end);
  if (then === null || !(then === 'OPEN' || then === 'PENDING' || then === 'SETTLED')) return false;
  return !deferred(c, today) && !(isOpen(c) && !c.stated && heldOn(holds, c.date));
}

export interface Claim {
  id: number;
  personId: number;
  /** The amount as text with two decimals. */
  amount: string;
  date: string;
  note: string;
  status: ClaimStatus;
  /** The claim's number on the sheet, given when it is opened (1, 2, ... or, under period numbering,
   *  <period name>-<n>, or X-<n> for a claim in no period); null for a claim never opened. */
  number: string | null;
  /** The due fixed when the claim was settled, as text with two decimals; null when it is not
   *  settled, or was settled while its person's grade had no rate (its due then stays live). */
  settledDue: string | null;
  /** Who opened the claim; null when it was never opened. That user cannot settle it. */
  openedBy: string | null;
  /** Whether its person's last statement bound it to the due it had then. */
  stated: boolean;
  /** The due the last statement bound it to, as text with two decimals; null when it is unbound,
   *  or was bound while it had no due (its due then stays live). */
  statedDue: string | null;
  /** Whether the last statement bound it to n/a: the rate that would apply to it had ended (its
   *  due then does not stay live). */
  statedNa: boolean;
  /** The number of the settled claim this correction corrects; null when it is not a correction. */
  corrects: string | null;
  /** The date (yyyy-mm-dd) its figures round by: that of the claim a correction corrects, or else
   *  its own. */
  roundsBy: string;
  /** Where the claim is listed: claims are listed newest first by this (a claim moved to another
   *  person is listed as that person's newest). */
  listed: number;
  /** The day (yyyy-mm-dd) it is due by: fixed when it was made, from its date and the terms of its
   *  person's grade in force on that date. */
  dueBy: string;
  /** Its status log, oldest first: a line for each status it has had. */
  log: ClaimLogLine[];
  /** The day (yyyy-mm-dd) it is deferred until; null when it was never deferred. */
  deferredUntil: string | null;
  /** How many proofs it has. One whose due is above the proof threshold in force on its date cannot
   *  be settled or approved while it has none. */
  proofs: number;
  /** The number of the claim it was split off; null when it was not split off one. */
  splitFrom: string | null;
}

/** A line of a claim's status log: the status, who changed it to that, the sheet's today then
 *  (yyyy-mm-dd), and the claim's due at that moment as text with two decimals (null when it had
 *  none). */
export interface ClaimLogLine {
  status: ClaimStatus;
  by: string;
  on: string;
  due: string | null;
}

export interface ClaimForm {
  amount: string;
  /** yyyy-mm-dd, or null for the sheet's today. */
  date: string | null;
  note: string;
  /** The number of the settled claim of the same person a correction (a negative amount)
   *  corrects; null when none is named. */
  corrects: string | null;
}

/** What a quick-add made: the person the claim is for (added when the name was not on the sheet)
 *  and the draft claim. */
export interface QuickAdded {
  person: Person;
  personAdded: boolean;
  claim: Claim;
}

/** An amount with up to two decimals, 0.00 or more. */
export const AMOUNT = /^\d{1,12}(\.\d{1,2})?$/;

/** A claim's amount with up to two decimals; a negative one is a correction. */
export const CLAIM_AMOUNT = /^-?\d{1,12}(\.\d{1,2})?$/;

/** Whether a claim is a correction: its amount is below 0.00. */
export function correction(c: Claim): boolean {
  return c.amount.startsWith('-') && Number(c.amount) !== 0;
}

/** The sum of amounts (two-decimal text), added in cents so no rounding creeps in. */
export function sumAmounts(amounts: string[]): string {
  const cents = amounts.reduce((sum, a) => sum + Math.round(Number(a) * 100), 0);
  return (cents / 100).toFixed(2);
}

/** Amounts added up, leaving out the single highest and the single lowest when there are three or
 *  more. */
export function trimmedSum(amounts: string[]): string {
  if (amounts.length < 3) return sumAmounts(amounts);
  const sorted = [...amounts].sort((a, b) => Number(a) - Number(b));
  return sumAmounts(sorted.slice(1, -1));
}

/** Whether a claim is bound by its person's last statement now: it was bound, and it is not
 *  pending (a pending claim shows its live figure). */
function bound(c: Claim): boolean {
  return c.stated && c.status !== 'PENDING';
}

/** Whether a claim keeps a figure that rates and grades no longer change: settled or bound by a
 *  statement. */
export function fixed(c: Claim): boolean {
  return c.settledDue !== null || (bound(c) && (c.statedDue !== null || c.statedNa));
}

/** What a claim's late charge follows: the sheet's today and its late percents (each 30-day boundary
 *  takes the one in force on it); and the sheet's policy date, which decides how its live figures round. */
export interface Lateness {
  today: string | null;
  /** The late percents recorded, each from its from-date, earliest first. */
  pcts: DatedValue[];
  /** The policy date set (yyyy-mm-dd); null when blank, the sheet's today then stands in. */
  policyDate: string | null;
}

/** Whether a claim's live figures round half up: the date it rounds by is before the policy date
 *  in force (the one set, or else the sheet's today); otherwise they round half to even. */
export function roundsHalfUp(c: Claim, late: Lateness): boolean {
  const policy = late.policyDate ?? late.today;
  return policy !== null && c.roundsBy < policy;
}

/** A claim's due figure: the one fixed when it was settled, or else the greater of the sheet's floor in force on the
 *  claim's date and its rate figure with the late charge on that figure on top while it is overdue. The rate figure is
 *  the one its person's last statement bound it to (n/a when it bound it to n/a), or else its live due at the rate in
 *  force on the claim's date for the grade (the person's grade in force on that date), rounded by the policy date (a
 *  settled claim's figure already holds the charge and the floor). A correction has no floor and no late charge. Null
 *  when there is none. */
export function claimDue(c: Claim, rates: Rate[], grade: Grade | undefined, floors: DatedValue[], late: Lateness): string | null {
  if (c.settledDue !== null) return c.settledDue;
  if (bound(c) && c.statedNa) return null;
  const live = due(c.amount, grade === undefined ? undefined : pctOn(rates, grade, c.date), roundsHalfUp(c, late));
  const figure = (bound(c) ? c.statedDue : null) ?? live;
  if (figure === null) return null;
  const charged = sumAmounts([figure, lateCharge(c, figure, late)]);
  return correction(c) ? charged : atLeast(charged, valueOn(floors, c.date, STARTING_FLOOR));
}

/** The late charge on an overdue claim: for each full 30 days it is overdue, the late percent in force on that
 *  boundary (its due-by plus 30, 60, 90 ... days) of its figure, rounded like a due, the charges added up (a
 *  boundary whose percent is 0.00 adds nothing); 0.00 when it is not overdue, and on a correction. */
export function lateCharge(c: Claim, figure: string, late: Lateness): string {
  if (correction(c)) return '0.00';
  if (!overdue(c, late.today) || late.today === null) return '0.00';
  const days = (Date.parse(late.today) - Date.parse(c.dueBy)) / 86_400_000;
  const periods = Math.floor(days / 30);
  if (periods <= 0) return '0.00';
  const charges: string[] = [];
  for (let n = 1; n <= periods; n++) {
    const boundary = new Date(Date.parse(c.dueBy) + n * 30 * 86_400_000).toISOString().slice(0, 10);
    const pct = valueOn(late.pcts, boundary, STARTING_LATE_PCT);
    if (Number(pct) !== 0) charges.push(due(figure, pct, roundsHalfUp(c, late)) ?? '0.00');
  }
  return sumAmounts(charges);
}

/** Whether a claim is overdue: it is open (or pending), not deferred, and its due-by is before the
 *  sheet's today. */
export function overdue(c: Claim, today: string | null): boolean {
  return isOpen(c) && !deferred(c, today) && today !== null && c.dueBy < today;
}

// The claims people make on the active sheet.
@Injectable({ providedIn: 'root' })
export class Claims {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** Every claim on the sheet, newest first. */
  list(sheetId: string): Promise<Claim[]> {
    return firstValueFrom(this.http.get<Claim[]>(`${this.url(sheetId)}/claims`));
  }

  add(personId: number, form: ClaimForm): Promise<Claim> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/people/${personId}/claims`, form, { headers: { 'X-User': user } }),
    );
  }

  /** Add a draft claim of the amount, dated the sheet's today, for the person of the name, who is
   *  added at grade A first when the name is not on the sheet. */
  quickAdd(name: string, amount: string): Promise<QuickAdded> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<QuickAdded>(`${this.url(sheetId)}/quick-add`, { name, amount }, { headers: { 'X-User': user } }),
    );
  }

  /** Add a draft claim per line of name,amount,date,note, all or none: how many were added. */
  paste(text: string): Promise<{ added: number }> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<{ added: number }>(`${this.url(sheetId)}/claims/paste`, { text }, { headers: { 'X-User': user } }),
    );
  }

  edit(id: number, form: ClaimForm): Promise<Claim> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.put<Claim>(`${this.url(sheetId)}/claims/${id}`, form, { headers: { 'X-User': user } }),
    );
  }

  /** Ask for a change of the claim's status; refused (409) when its status does not allow it. A
   *  reject carries its reason. */
  act(id: number, action: ClaimAction, reason?: string): Promise<Claim> {
    const { sheetId, user } = this.active();
    const body = action === 'reject' ? { reason: reason ?? '' } : null;
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/${action}`, body, { headers: { 'X-User': user } }),
    );
  }

  /** Defer an open claim until a day after the sheet's today; refused (409) when it is not open,
   *  (400) when the day is not after today, and (423) when its period is closed. */
  defer(id: number, until: string): Promise<Claim> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/defer`, { until }, { headers: { 'X-User': user } }),
    );
  }

  /** Re-date an open or pending claim; refused (403) when the user is not a steward, (409) when it
   *  is not open or pending, (423) when its period or the new date's is closed, and (422) when the
   *  date is after the person's retirement. */
  redate(id: number, date: string): Promise<Claim> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/redate`, { date }, { headers: { 'X-User': user } }),
    );
  }

  /** Raise a claim's proofs count by one; refused (409) when it is settled or void, and (423) when
   *  its period is closed. */
  addProof(id: number): Promise<Claim> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/proof`, null, { headers: { 'X-User': user } }),
    );
  }

  /** Split the amount (above 0.00 and below the claim's amount) off an open claim: the claim with
   *  the rest of its amount, and the new open claim. Refused (409) when it is not open or is
   *  deferred, (400) when the amount is out of range, (403) when the user's role may not open a
   *  claim, and (423) when its period is closed. */
  split(id: number, amount: string): Promise<{ original: Claim; split: Claim }> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<{ original: Claim; split: Claim }>(`${this.url(sheetId)}/claims/${id}/split`, { amount },
        { headers: { 'X-User': user } }),
    );
  }

  /** Move a draft, open or pending claim to another person on the sheet; refused (403) unless the
   *  user is a steward, and (409) when the claim is settled or void. */
  reassign(id: number, personId: number): Promise<Claim> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/reassign`, { personId }, { headers: { 'X-User': user } }),
    );
  }

  /** Open each of the claims that can be opened, in list order; the rest are refused and left as
   *  they are. */
  openAll(claimIds: number[]): Promise<{ opened: number; refused: number }> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<{ opened: number; refused: number }>(`${this.url(sheetId)}/claims/open`, { claimIds },
        { headers: { 'X-User': user } }),
    );
  }

  /** Settle each of the claims that can be settled, in list order, as settling one does (one left
   *  pending a steward's approval counts as settled); the rest are refused and left as they are. */
  settleAll(claimIds: number[]): Promise<{ settled: number; refused: number }> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<{ settled: number; refused: number }>(`${this.url(sheetId)}/claims/settle`, { claimIds },
        { headers: { 'X-User': user } }),
    );
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }

  private active(): { sheetId: string; user: string } {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    return { sheetId, user };
  }
}
