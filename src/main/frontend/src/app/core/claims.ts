import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Grade, Leave, Person, retiredOn } from './people';
import { Rate, atLeast, due, pctFor } from './rates';
import { DatedValue, Graces, RoundingMode, STARTING_FLOOR, STARTING_LATE_PCT, graceOn, valueOn } from './settings';
import { Session } from './session';

/** Where a claim is in its life: DRAFT, then OPEN, then SETTLED; a clerk's settle above the clerk
 *  limit leaves it PENDING until a steward approves it (SETTLED) or rejects it (OPEN again); a
 *  DRAFT, OPEN or PENDING claim may be VOID, and a steward may void a SETTLED one within 7 days of
 *  its settlement. A DRAFT may be DELETED (counting nowhere), and restored to DRAFT the same day.
 *  Opening a claim whose due is above the review threshold leaves it in REVIEW (counting nowhere,
 *  with no number) until a steward reviews it (OPEN); a REVIEW claim may be VOID. */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'PENDING' | 'SETTLED' | 'VOID' | 'DELETED' | 'REVIEW';

/** The kinds a proof may be; a proof added with none chosen is a receipt. */
export const PROOF_KINDS = ['receipt', 'photo'] as const;

/** A kind of proof. */
export type ProofKind = (typeof PROOF_KINDS)[number];

/** A change of status a claim can be asked for. */
export type ClaimAction = 'open' | 'settle' | 'resettle' | 'void' | 'approve' | 'reject' | 'delete' | 'restore'
  | 'review';

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
 *  and it is not an open (or pending) claim, unbound by a statement, of a person retired on that
 *  day. A hold does not take it out of any total. */
export function countsLive(c: Claim, leave: Leave, today: string | null): boolean {
  return counts(c) && !deferred(c, today) && !leftOut(c, leave, today);
}

/** Whether a claim counts toward what its person has claimed on the sheet's today: it counts, it is
 *  not deferred, and it is not an open (or pending) claim, unbound by a statement, of a person
 *  retired on that day. A hold does not take it out of claimed. */
export function countsClaimed(c: Claim, leave: Leave, today: string | null): boolean {
  return counts(c) && !deferred(c, today) && !(isOpen(c) && !c.stated && retiredOn(leave.retiredFrom, today));
}

/** Whether the live rules leave a claim out: it is open (or pending), unbound by a statement, and
 *  its person is retired on the sheet's today. A hold (of the claim or the person) leaves nothing
 *  out: it only takes the person's cut out of the pools. */
function leftOut(c: Claim, leave: Leave, today: string | null): boolean {
  return isOpen(c) && !c.stated && retiredOn(leave.retiredFrom, today);
}

/** The day (yyyy-mm-dd) a number of days after another (before it when negative). */
export function addDays(day: string, days: number): string {
  const d = new Date(day + 'T00:00:00Z');
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** Whether a claim keeps its person active on the sheet's today: it is open (or pending), it
 *  counts in the live totals, and it is dated within the 30 days up to and including today. */
export function keepsActive(c: Claim, leave: Leave, today: string | null): boolean {
  return today !== null && isOpen(c) && countsLive(c, leave, today)
    && addDays(today, -29) <= c.date && c.date <= today;
}

/** A claim's part in its patron's backing: its frozen figure when it is settled and dated within
 *  the 90 days up to and including the sheet's today; null otherwise. */
export function backingFigure(c: Claim, today: string | null): string | null {
  return today !== null && c.status === 'SETTLED' && addDays(today, -89) <= c.date && c.date <= today
    ? c.settledDue : null;
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
 *  today, and it is not a claim open (or pending) now, unbound by a statement, of a person retired
 *  on the sheet's today. */
export function countsAsOf(c: Claim, leave: Leave, end: string, today: string | null): boolean {
  const then = statusOn(c, end);
  if (then === null || !(then === 'OPEN' || then === 'PENDING' || then === 'SETTLED')) return false;
  return !deferred(c, today) && !leftOut(c, leave, today);
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
  /** The day (yyyy-mm-dd) it is due by: fixed when it was made, the terms-th business day after its
   *  date by the terms of its person's grade in force on that date. */
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
  /** Who has the claim's editor open, locking it for every other user; null when no one has. */
  editLockedBy: string | null;
  /** Whether the claim was added while the period its due-by falls in was soft-closed. */
  lateEntry: boolean;
  /** The last day (yyyy-mm-dd) a reminder was written for it; null when never. */
  remindedOn: string | null;
  /** Its external reference; null when it has none. No two claims of a sheet share one, comparing
   *  without regard to case. */
  ref: string | null;
  /** Its tags, sorted and comma and space separated; empty when it has none. */
  tags: string;
  /** The reason from the sheet's list a void claim was voided with; null when none. */
  voidReason: string | null;
  /** Its comments, in the order they were written. */
  comments: ClaimComment[];
  /** The number of the bulk settle batch that settled it (or left it pending); null when it was
   *  settled one at a time, or is not settled or pending. */
  settleBatch: number | null;
  /** Its lines, in the order they were added; a claim with lines has their total as its amount. */
  lines: ClaimLine[];
  /** The claim's place in the order claims are added and forward-only rates saved: a forward-only
   *  rate applies to it only when it was added after the rate was saved (see {@link pctFor}). */
  added: number;
  /** Whether a steward waived its late charge: it bears none from then on. */
  waived: boolean;
  /** How many of its proofs are of each kind. While the sheet has proof kinds, one whose due is
   *  above the proof threshold cannot be settled or approved without a proof of each of them. */
  proofKinds: Record<ProofKind, number>;
  /** Whether it is held. A held claim still counts in every total. */
  held: boolean;
}

/** A line of a claim: its label, its quantity (a whole number) and its unit amount as text with
 *  two decimals. */
export interface ClaimLine {
  label: string;
  qty: number;
  unit: string;
}

/** A comment on a claim: who wrote it, the sheet's today then (yyyy-mm-dd), and its text. */
export interface ClaimComment {
  by: string;
  on: string;
  text: string;
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
  /** The external reference, its surrounding spaces removed; null for none. */
  ref: string | null;
  /** Comma separated tags (the store trims, lower-cases and drops repeats); empty for none. */
  tags: string;
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

/** Whether a claim is bound by its person's last statement now (a pending claim is too, showing
 *  and totalling the bound figure). */
function bound(c: Claim): boolean {
  return c.stated;
}

/** Whether a claim keeps a figure that rates and grades no longer change: settled or bound by a
 *  statement. */
export function fixed(c: Claim): boolean {
  return c.settledDue !== null || (bound(c) && (c.statedDue !== null || c.statedNa));
}

/** What a claim's late charge follows: the sheet's today and its late percents (each 30-day boundary
 *  takes the one in force on it), and the grace days of each grade; and the sheet's rounding modes and
 *  policy date, which decide how its live figures round. */
export interface Lateness {
  today: string | null;
  /** The late percents recorded, each from its from-date, earliest first. */
  pcts: DatedValue[];
  /** The grace days recorded for each grade. */
  graces: Graces;
  /** The policy date set (yyyy-mm-dd); null when blank, the sheet's today then stands in. */
  policyDate: string | null;
  /** The rounding modes recorded (null: blank, none), each from its from-date, earliest first. */
  roundings: DatedValue<RoundingMode | null>[];
}

/** Whether a claim's live figures round half up: by the rounding mode in force on its date, or,
 *  when none is, when the date it rounds by is before the policy date in force (the one set, or
 *  else the sheet's today); otherwise they round half to even. */
export function roundsHalfUp(c: Claim, late: Lateness): boolean {
  const mode = valueOn<RoundingMode | null>(late.roundings, c.date, null);
  if (mode !== null) return mode === 'half-up';
  const policy = late.policyDate ?? late.today;
  return policy !== null && c.roundsBy < policy;
}

/** A claim's due figure: the one fixed when it was settled, or else the one its person's last statement bound it to
 *  as it showed then, late charge included (n/a when it bound it to n/a), or else the greater of the sheet's floor in
 *  force on the claim's date and its rate figure, with the late charge on that floored figure on top while overdue. The
 *  rate figure is its live due at the rate in
 *  force on the claim's date that applies to it (see {@link pctFor}) for the grade (the person's grade in force on that date), rounded by the rounding mode or policy date (a
 *  settled claim's figure already holds the charge and the floor). A correction has no floor and no late charge. Null
 *  when there is none. */
export function claimDue(c: Claim, rates: Rate[], grade: Grade | undefined, floors: DatedValue[], late: Lateness): string | null {
  if (c.settledDue !== null) return c.settledDue;
  if (bound(c) && c.statedNa) return null;
  if (bound(c) && c.statedDue !== null) return c.statedDue;
  const figure = due(c.amount, grade === undefined ? undefined : pctFor(rates, grade, c), roundsHalfUp(c, late));
  if (figure === null) return null;
  const floored = correction(c) ? figure : atLeast(figure, valueOn(floors, c.date, STARTING_FLOOR));
  return sumAmounts([floored, lateCharge(c, floored, late, grade)]);
}

/** The late charge on an overdue claim (its grade being its person's grade in force on its date): for each full
 *  30 days it is overdue, the late percent in force on that boundary (its late-from plus 30, 60, 90 ... days) of its
 *  figure plus the charges of the boundaries before it (the charge compounds), rounded like a due, the charges added
 *  up (a boundary whose percent is 0.00 adds nothing); 0.00 when it is not overdue, on a correction, and when its
 *  late charge is waived. */
export function lateCharge(c: Claim, figure: string, late: Lateness, grade: Grade | undefined): string {
  if (correction(c) || c.waived) return '0.00';
  const grace = graceOn(late.graces, grade, c.date);
  if (!overdue(c, late.today, grace) || late.today === null) return '0.00';
  const from = Date.parse(lateFrom(c, grace));
  const days = (Date.parse(late.today) - from) / 86_400_000;
  const periods = Math.floor(days / 30);
  if (periods <= 0) return '0.00';
  const charges: string[] = [];
  for (let n = 1; n <= periods; n++) {
    const boundary = new Date(from + n * 30 * 86_400_000).toISOString().slice(0, 10);
    const pct = valueOn(late.pcts, boundary, STARTING_LATE_PCT);
    if (Number(pct) !== 0) charges.push(due(sumAmounts([figure, ...charges]), pct, roundsHalfUp(c, late)) ?? '0.00');
  }
  return sumAmounts(charges);
}

/** The day a claim's lateness counts from: its due-by plus its grace days (those in force on its date
 *  for its person's grade in force on that date). The due-by it shows is unchanged. */
export function lateFrom(c: Claim, grace: number): string {
  return grace === 0 ? c.dueBy : addDays(c.dueBy, grace);
}

/** Whether a claim in review is late: its review day (the sheet's today when it last went to review)
 *  plus the sheet's review SLA days is before the sheet's today; never while there is no SLA. */
export function reviewLate(c: Claim, today: string | null, sla: number | null): boolean {
  if (c.status !== 'REVIEW' || today === null || sla === null) return false;
  const day = c.log.filter((l) => l.status === 'REVIEW').at(-1)?.on;
  return day !== undefined && addDays(day, sla) < today;
}

/** Whether a claim is overdue: it is open (or pending), not deferred, and its due-by plus its grace
 *  days is before the sheet's today. */
export function overdue(c: Claim, today: string | null, grace = 0): boolean {
  return isOpen(c) && !deferred(c, today) && today !== null && lateFrom(c, grace) < today;
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

  /** The number of the sheet's last settle batch; null when there has been none. */
  async lastSettleBatch(sheetId: string): Promise<number | null> {
    const r = await firstValueFrom(this.http.get<{ batch: number | null }>(`${this.url(sheetId)}/claims/settle-batch`));
    return r.batch;
  }

  /** Every tag ever used on the sheet, in order. */
  tags(sheetId: string): Promise<string[]> {
    return firstValueFrom(this.http.get<string[]>(`${this.url(sheetId)}/tags`));
  }

  add(personId: number, form: ClaimForm): Promise<Claim> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/people/${personId}/claims`, form, { headers: { 'X-User': user } }),
    );
  }

  /** Add a draft claim of the amount, dated the sheet's today, for the person of the name, who is
   *  added at the sheet's default grade first when the name is not on the sheet. */
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

  /** Change only a draft's amount (the row's inline entry); refused as an edit is. */
  editAmount(id: number, amount: string): Promise<Claim> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.put<Claim>(`${this.url(sheetId)}/claims/${id}/amount`, { amount }, { headers: { 'X-User': user } }),
    );
  }

  /** Open the claim's editor, locking it for every other user until it is saved or cancelled or the
   *  sheet's today changes; refused (409) when it is not a draft or another user has it open, (423)
   *  when its period is closed, and (403) for a viewer. */
  openEditor(id: number): Promise<Claim> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/editor`, null, { headers: { 'X-User': user } }),
    );
  }

  /** Cancel the claim's editor the user has open, unlocking the claim. */
  cancelEditor(id: number): Promise<Claim> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/editor/cancel`, null, { headers: { 'X-User': user } }),
    );
  }

  /** Ask for a change of the claim's status; refused (409) when its status does not allow it. A
   *  reject carries its reason, and a void the reason chosen from the sheet's list (refused, 400,
   *  without one while the list is not empty). A settle carries the day it is settled as of (blank:
   *  the sheet's today), refused (400) when after the sheet's today or the claim did not exist then. */
  act(id: number, action: ClaimAction, reason?: string): Promise<Claim> {
    const { sheetId, user } = this.active();
    const body = action === 'reject' ? { reason: reason ?? '' }
      : action === 'void' ? { reason: reason || null }
      : action === 'settle' ? { on: reason || null } : null;
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
   *  is not open or pending, (423) when its period is closed, and (422) when the
   *  date is after the person's retirement. */
  redate(id: number, date: string): Promise<Claim> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/redate`, { date }, { headers: { 'X-User': user } }),
    );
  }

  /** Waive an open or pending claim's late charge; refused (403) when the user is not a steward, and
   *  (409) when it is not open or pending or is waived already. */
  waive(id: number): Promise<Claim> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/waive`, null, { headers: { 'X-User': user } }),
    );
  }

  /** Hold an open or pending claim, or release it when held; refused (409) when it is not open or
   *  pending. */
  toggleHold(id: number): Promise<Claim> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/hold`, null, { headers: { 'X-User': user } }),
    );
  }

  /** Raise a claim's proofs count by one, with a proof of a kind (null for none chosen: a
   *  receipt); refused (409) when it is settled or void, and (423) when its period is closed. */
  addProof(id: number, kind: ProofKind | null): Promise<Claim> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/proof`, { kind }, { headers: { 'X-User': user } }),
    );
  }

  /** Remind an open or pending claim on the sheet's today; refused (409) when it is in any other
   *  status, and (429) when it was already reminded today. */
  remind(id: number): Promise<Claim> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/remind`, null, { headers: { 'X-User': user } }),
    );
  }

  /** Comment a claim in any status, on the sheet's today; refused (400) when the text is empty. */
  comment(id: number, text: string): Promise<Claim> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/comments`, { text },
        { headers: { 'X-User': user } }),
    );
  }

  /** Add a line to a draft claim: the claim with its amount the lines' total. Refused (400) when
   *  the label is empty, the quantity not a whole number of at least 1 or the unit amount not
   *  above 0.00 with two decimals at most, (409) when it is not a draft or another user is editing
   *  it, and (423) when its period is closed. */
  addLine(id: number, label: string, qty: string, unit: string): Promise<Claim> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/lines`, { label, qty, unit },
        { headers: { 'X-User': user } }),
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

  /** Part-settle an open claim: split the amount off it and settle the new claim at once (left
   *  pending when over the clerk limit): the claim with the rest of its amount, and the new claim.
   *  Refused as a split or a settle of the original claim is, and then nothing changes. */
  settlePart(id: number, amount: string): Promise<{ original: Claim; split: Claim }> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<{ original: Claim; split: Claim }>(`${this.url(sheetId)}/claims/${id}/settle-part`, { amount },
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

  /** Move each of the selected claims that can be moved to the person, in list order, as moving
   *  one does; the rest are refused and left as they are. */
  reassignAll(claimIds: number[], personId: number): Promise<{ reassigned: number; refused: number }> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<{ reassigned: number; refused: number }>(`${this.url(sheetId)}/claims/reassign`,
        { claimIds, personId }, { headers: { 'X-User': user } }),
    );
  }

  /** Re-date each of the claims that can be re-dated, in list order, as re-dating one does; the
   *  rest are refused and left as they are. */
  redateAll(claimIds: number[], date: string): Promise<{ redated: number; refused: number }> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<{ redated: number; refused: number }>(`${this.url(sheetId)}/claims/redate`,
        { claimIds, date }, { headers: { 'X-User': user } }),
    );
  }

  /** Void each of the claims that can be voided, in list order, as voiding one does (with the
   *  reason, blank when none); the rest are refused and left as they are. */
  voidAll(claimIds: number[], reason: string): Promise<{ voided: number; refused: number }> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<{ voided: number; refused: number }>(`${this.url(sheetId)}/claims/void`,
        { claimIds, reason }, { headers: { 'X-User': user } }),
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
