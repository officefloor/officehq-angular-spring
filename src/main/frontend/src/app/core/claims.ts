import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Grade, Hold, Person, heldOn } from './people';
import { Rate, atLeast, due, pctOn } from './rates';
import { DatedValue, STARTING_FLOOR, STARTING_LATE_PCT, valueOn } from './settings';
import { Session } from './session';

/** Where a claim is in its life: DRAFT, then OPEN, then SETTLED; a clerk's settle above the clerk
 *  limit leaves it PENDING until a steward approves it (SETTLED) or rejects it (OPEN again); a
 *  DRAFT, OPEN or PENDING claim may be VOID. */
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

/** What a claim's late charge follows: the sheet's today and its late percents (a claim takes the
 *  one in force on its date); and the sheet's policy date, which decides how its live figures round. */
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

/** A claim's due figure: the one fixed when it was settled, or else the one its person's last
 *  statement bound it to (n/a when it bound it to n/a), or else its live due at the rate in force on the claim's date for the
 *  grade (the person's grade in force on that date), rounded by the policy date, at least the sheet's floor in force on the claim's date; with the late charge on top while it is overdue (a
 *  settled claim's figure already holds it). A correction has no floor and no late charge. Null when there is none. */
export function claimDue(c: Claim, rates: Rate[], grade: Grade | undefined, floors: DatedValue[], late: Lateness): string | null {
  if (c.settledDue !== null) return c.settledDue;
  if (bound(c) && c.statedNa) return null;
  const live = due(c.amount, grade === undefined ? undefined : pctOn(rates, grade, c.date), roundsHalfUp(c, late));
  const floored = live === null || correction(c) ? live : atLeast(live, valueOn(floors, c.date, STARTING_FLOOR));
  const figure = (bound(c) ? c.statedDue : null) ?? floored;
  return figure === null ? null : sumAmounts([figure, lateCharge(c, figure, late)]);
}

/** The late charge on an overdue claim: the late percent in force on its date of its figure for every full 30 days it
 *  is overdue, rounded like a due; 0.00 when it is not overdue, and on a correction. */
export function lateCharge(c: Claim, figure: string, late: Lateness): string {
  if (correction(c)) return '0.00';
  if (!overdue(c, late.today) || late.today === null) return '0.00';
  const days = (Date.parse(late.today) - Date.parse(c.dueBy)) / 86_400_000;
  const periods = Math.floor(days / 30);
  if (periods <= 0) return '0.00';
  return due(figure, (Number(valueOn(late.pcts, c.date, STARTING_LATE_PCT)) * periods).toFixed(2), roundsHalfUp(c, late)) ?? '0.00';
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
