import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { RateView, dueCents, rateInForce, ratesAppliedTo } from '../rates/rates-api';

export type ClaimStatus = 'DRAFT' | 'OPEN' | 'PENDING' | 'SETTLED' | 'VOID' | 'DELETED' | 'REVIEW';
export type ClaimAction = 'open' | 'settle' | 'void' | 'approve' | 'reject' | 'resettle' | 'delete' | 'restore' | 'review';

/** An amount as a form takes it: 0.00 or more, with up to two decimals. */
/** The kinds a proof can be; a proof added with none chosen is a receipt. */
export const PROOF_KINDS = ['receipt', 'photo'] as const;
export type ProofKind = (typeof PROOF_KINDS)[number];

export const AMOUNT = /^\d+(\.\d{1,2})?$/;

/** An amount as the claim form takes it: as AMOUNT, or negative for a correction. */
export const CLAIM_AMOUNT = /^-?\d+(\.\d{1,2})?$/;

/** A claim number as a form takes it: a sheet-wide whole number of 1 or more, or a number within a
 *  period, <period name>-<n> (X-<n> for a claim in no period), or within a fiscal year, FY<year>-<n>. */
export const CLAIM_NUMBER = /^([1-9]\d{0,8}|.{1,200}-[1-9]\d{0,8})$/;

/** Whether the claim is a correction: its amount is negative. */
export function isCorrection(claim: ClaimView): boolean {
  return cents(claim.amount) < 0;
}

/** Whether the claim is open: OPEN, or PENDING (a clerk's settling above the clerk limit, waiting
 *  for a steward), which is handled as open. */
export function isOpen(claim: ClaimView): boolean {
  return claim.status === 'OPEN' || claim.status === 'PENDING';
}

/** Whether the claim is bound to the figure its person's last statement stated (a PENDING claim
 *  included, like any other claim that counts). */
export function isBound(claim: ClaimView): boolean {
  return claim.stated;
}

/** Whether the claim is deferred on the sheet's today: OPEN, with today before its deferred-until date.
 *  A deferred claim counts in no total, is not overdue, cannot be settled and no statement binds it. */
export function isDeferred(claim: ClaimView, today: string | undefined): boolean {
  return claim.deferredUntil !== null && claim.status === 'OPEN' && today !== undefined && today < claim.deferredUntil;
}

/** Only open (PENDING included) and settled claims count toward what is claimed and due. */
export function counts(claim: ClaimView): boolean {
  return isOpen(claim) || claim.status === 'SETTLED';
}

/** The status the claim had at the end of the day `date` (yyyy-mm-dd), as its status log records it;
 *  null when it was not yet added then. */
export function statusAsOf(claim: ClaimView, date: string): ClaimStatus | null {
  let status: ClaimStatus | null = null;
  for (const line of claim.log) if (line.on <= date) status = line.status;
  return status;
}

export interface ClaimView {
  id: number;
  personId: number;
  /** The amount with two decimals, as the store holds it. */
  amount: string;
  /** yyyy-mm-dd */
  date: string;
  note: string;
  status: ClaimStatus;
  /** The claim's number, given when it is opened and kept once void; null while a draft. FY<year>-<n>
   *  within its fiscal year (or the sheet-wide 1, 2, ... given before), or while period numbering is in
   *  force <period name>-<n>, or X-<n> in no period. */
  number: string | null;
  /** The due fixed when the claim was settled, with two decimals; null while its due is live. */
  settledDue: string | null;
  /** Who opened the claim; null when it was never opened. */
  openedBy: string | null;
  /** Whether the person's last statement bound the claim to a figure. */
  stated: boolean;
  /** The figure the last statement bound the claim to, with two decimals; null when unbound or n/a then. */
  statedDue: string | null;
  /** The number of the settled claim a correction corrects; null when the claim is not a correction. */
  corrects: string | null;
  /** yyyy-mm-dd: the date the claim is due by, fixed when it was added from the terms of its person's
   *  grade in force on its date. */
  dueBy: string;
  /** The claim's status log, oldest first: a line for each status it has had. */
  log: ClaimLogLine[];
  /** yyyy-mm-dd: the date the claim was last deferred until; null when it never was. */
  deferredUntil: string | null;
  /** How many proofs the claim has had added; a claim due above the proof threshold needs one to be settled. */
  proofs: number;
  /** How many of the claim's proofs are receipts (a proof added with no kind chosen is one). */
  proofsReceipt: number;
  /** How many of the claim's proofs are photos. */
  proofsPhoto: number;
  /** The number of the claim this one was split off from; null when it was not split off. */
  splitFrom: string | null;
  /** Whether the claim was added while the period its due-by falls in was soft-closed. */
  lateEntry: boolean;
  /** yyyy-mm-dd: the last day (the sheet's today then) a reminder was written for the claim; null when never. */
  reminded: string | null;
  /** The claim's external reference; null when it has none. */
  ref: string | null;
  /** The claim's tags, lower-cased and sorted; empty when it has none. */
  tags: string[];
  /** The reason the claim was voided with, or null. */
  voidReason: string | null;
  /** The claim's comments, oldest first. */
  comments: ClaimComment[];
  /** Who last edited the claim through its form or its row's amount entry; null when it never was. */
  editedBy: string | null;
  /** The number of the bulk settle batch that settled the claim; null when settled one at a time or never. */
  settleBatch: number | null;
  /** The claim's lines, in the order they were added; while it has any, its amount is their total. */
  lines: ClaimLine[];
  /** Whether a steward waived the claim's late charge; a waived claim bears none from then on. */
  waived: boolean;
  /** Whether the claim is held; a held claim still counts in every total. */
  held: boolean;
  /** The claim's shares, in the order they were given. */
  shares: ClaimShare[];
  /** The user a steward assigned the claim's review to; null until one is assigned. */
  reviewAssignee: string | null;
}

/** A share of a claim: the person on the sheet holding it and their percent of its due (two decimals). */
export interface ClaimShare {
  personId: number;
  share: string;
}

/** A holder's part of a claim's due in cents: the due times their share divided by 100, rounded half to even. */
export function sharePartCents(dueCents: number, share: string): number {
  const scaled = dueCents * cents(share);
  const whole = Math.floor(scaled / 10000);
  const rest = scaled - whole * 10000;
  return rest > 5000 || (rest === 5000 && whole % 2 !== 0) ? whole + 1 : whole;
}

/** A line of a claim: its label, whole quantity and unit amount (two decimals). */
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

/** What splitting a claim did: the claim split, with the rest of its amount, and the claim split off it. */
export interface SplitView {
  original: ClaimView;
  split: ClaimView;
}

/** A line of a claim's status log: the status, who gave it, the sheet's today then (yyyy-mm-dd), and
 *  the claim's due as worked out at that moment, with two decimals; null when it was n/a. */
export interface ClaimLogLine {
  status: ClaimStatus;
  by: string;
  on: string;
  due: string | null;
}

/** What opening selected claims together did: how many were opened and refused, and the claims opened. */
export interface BulkOpenView {
  opened: number;
  refused: number;
  claims: ClaimView[];
}

/** What a paste added: how many claims, and the claims, in the order of their lines. */
export interface PasteView {
  added: number;
  claims: ClaimView[];
}

export interface BulkSettleView {
  /** Claims settled, those left pending included. */
  settled: number;
  refused: number;
  claims: ClaimView[];
  /** The number of the batch the settle made; null when it settled none. */
  batch: number | null;
}

/** What moving selected claims together did: how many were moved and refused, and the claims moved,
 *  in the order they were moved. */
export interface BulkReassignView {
  reassigned: number;
  refused: number;
  claims: ClaimView[];
}

/** What re-dating selected claims together did: how many were re-dated and refused, and the claims
 *  re-dated, in the order they were re-dated. */
/** What a bulk void did: the claims it voided, in list order, and how many were voided and refused. */
export interface BulkVoidView {
  voided: number;
  refused: number;
  claims: ClaimView[];
}

export interface BulkRedateView {
  redated: number;
  refused: number;
  claims: ClaimView[];
}

export interface ClaimForm {
  amount: string;
  /** null takes the sheet's today. */
  date: string | null;
  note: string;
  /** The number of the settled claim of the same person a correction corrects; null for none. */
  corrects: string | null;
  /** The claim's external reference, trimmed; null for none. */
  ref: string | null;
  /** The claim's tags, comma separated as typed; the store trims, lower-cases and sorts them, dropping repeats. */
  tags: string;
}

/** Whether the claim keeps a figure whatever the rates: one fixed when it was settled, or bound by a statement. */
export function keepsFigure(claim: ClaimView): boolean {
  return claim.settledDue !== null || (isBound(claim) && claim.statedDue !== null);
}

/** An amount in whole cents, so sums of amounts are exact. */
export function cents(amount: string): number {
  return Math.round(Number(amount) * 100);
}

/** Cents as the screen shows an amount: two decimals. */
export function money(total: number): string {
  return (total / 100).toFixed(2);
}

/** Whether the claim's live figures round half up: by the rounding mode in force (`rounding`) on the date
 *  that decides it (for a correction, the date of the claim of `claims` it corrects; else its own) when there
 *  is one, else when that date is before the policy date. Otherwise they round half to even. */
export function roundsHalfUp(claim: ClaimView, claims: readonly ClaimView[], policyDate: string | undefined,
  rounding: (date: string) => string | null = () => null): boolean {
  const corrected = isCorrection(claim) && claim.corrects !== null
    ? claims.find((c) => c.personId === claim.personId && c.number === claim.corrects)
    : undefined;
  const date = (corrected ?? claim).date;
  const mode = rounding(date);
  if (mode !== null) return mode === 'half-up';
  if (policyDate === undefined) return false;
  return date < policyDate;
}

/** The figure under the claim's due, in cents: the due fixed when it was settled, else the figure its
 *  person's last statement bound it to (PENDING included), else at the rate of `gradeRates` (its person's grade's rates)
 *  in force on the claim's date, of the versions that apply to the claim, rounded half up when `halfUp` (else half to even); no floor is applied to it.
 *  Null when there is none. */
export function claimFigureCents(claim: ClaimView, gradeRates: readonly RateView[], halfUp = false): number | null {
  if (claim.settledDue !== null) return cents(claim.settledDue);
  if (isBound(claim)) return claim.statedDue === null ? null : cents(claim.statedDue);
  const rate = rateInForce(ratesAppliedTo(gradeRates, claim.id), claim.date);
  if (rate === null) return null;
  return dueCents(cents(claim.amount), rate.pct, halfUp);
}

/** Days since 1970-01-01 of a yyyy-mm-dd date, worked out from the calendar, not a clock. */
function epochDay(date: string): number {
  const [y0, m, d] = date.split('-').map(Number);
  const y = m <= 2 ? y0 - 1 : y0;
  const era = Math.floor(y / 400);
  const yoe = y - era * 400;
  const doy = Math.floor((153 * (m + (m > 2 ? -3 : 9)) + 2) / 5) + d - 1;
  const doe = yoe * 365 + Math.floor(yoe / 4) - Math.floor(yoe / 100) + doy;
  return era * 146097 + doe - 719468;
}

/** The yyyy-mm-dd date of a count of days since 1970-01-01, worked out from the calendar, not a clock. */
function dateOfEpochDay(day: number): string {
  const z = day + 719468;
  const era = Math.floor(z / 146097);
  const doe = z - era * 146097;
  const yoe = Math.floor((doe - Math.floor(doe / 1460) + Math.floor(doe / 36524) - Math.floor(doe / 146096)) / 365);
  const doy = doe - (365 * yoe + Math.floor(yoe / 4) - Math.floor(yoe / 100));
  const mp = Math.floor((5 * doy + 2) / 153);
  const d = doy - Math.floor((153 * mp + 2) / 5) + 1;
  const m = mp < 10 ? mp + 3 : mp - 9;
  const y = yoe + era * 400 + (m <= 2 ? 1 : 0);
  return `${String(y).padStart(4, '0')}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
}

/** The date the claim's lateness counts from: its due-by plus `graceDays` (those of its person's grade in
 *  force on its date); the due-by shown is unchanged. */
export function lateFrom(claim: ClaimView, graceDays: number): string {
  return dateOfEpochDay(epochDay(claim.dueBy) + graceDays);
}

/** Whether the claim is overdue: open and not deferred, with its due-by plus `graceDays` before the sheet's today. */
export function isOverdue(claim: ClaimView, today: string | undefined, graceDays = 0): boolean {
  return isOpen(claim) && !isDeferred(claim, today) && today !== undefined && lateFrom(claim, graceDays) < today;
}

/** The day a REVIEW claim went to REVIEW: the sheet's today then, as its status log records it; null
 *  when the claim is not REVIEW. */
export function reviewDay(claim: ClaimView): string | null {
  if (claim.status !== 'REVIEW') return null;
  let day: string | null = null;
  for (const line of claim.log) if (line.status === 'REVIEW') day = line.on;
  return day;
}

/** The day a PENDING claim went to PENDING: the sheet's today then, the latest time its status log
 *  records it; null when the claim is not PENDING. */
export function pendingDay(claim: ClaimView): string | null {
  if (claim.status !== 'PENDING') return null;
  let day: string | null = null;
  for (const line of claim.log) if (line.status === 'PENDING') day = line.on;
  return day;
}

/** Whether the claim is pending-late: PENDING, with its pending day plus the pending SLA's `slaDays` before
 *  the sheet's today; never when there is no SLA (null). */
export function isPendingLate(claim: ClaimView, today: string | undefined, slaDays: number | null): boolean {
  const day = pendingDay(claim);
  return day !== null && slaDays !== null && today !== undefined && dateOfEpochDay(epochDay(day) + slaDays) < today;
}

/** Whether the claim is review-late: REVIEW, with its review day plus the review SLA's `slaDays` before
 *  the sheet's today; never when there is no SLA (null). */
export function isReviewLate(claim: ClaimView, today: string | undefined, slaDays: number | null): boolean {
  const day = reviewDay(claim);
  return day !== null && slaDays !== null && today !== undefined && dateOfEpochDay(epochDay(day) + slaDays) < today;
}

/** The days a claim must be overdue for each step of the late charge. */
export const DAYS_PER_CHARGE = 30;

/** What the late charge depends on: the sheet's today and its late percent in force on a date; the grace
 *  days of a claim (its person's grade's, in force on its date); and the policy date in force (the sheet's,
 *  else its today) and the rounding mode in force on a date (null: none) that decide how a claim's figures round. */
export interface Lateness {
  today: string | undefined;
  latePct: (date: string) => string;
  grace: (claim: ClaimView) => number;
  policyDate: string | undefined;
  rounding: (date: string) => string | null;
}

/** The late charge on the claim of `figureCents`, in cents: for every full 30 days the claim is overdue, the
 *  late percent in force on that boundary (its due-by plus its grace days plus 30, 60, 90 ... days) of the figure plus the
 *  charges of the boundaries before it (compounding), rounded like its due, added up; none unless it is overdue, and none on a correction or a waived claim. */
export function lateChargeCents(claim: ClaimView, figureCents: number, late: Lateness, halfUp = false): number {
  const grace = late.grace(claim);
  if (late.today === undefined || isCorrection(claim) || claim.waived || !isOverdue(claim, late.today, grace)) return 0;
  const dueBy = epochDay(claim.dueBy) + grace;
  const steps = Math.floor((epochDay(late.today) - dueBy) / DAYS_PER_CHARGE);
  let charge = 0;
  for (let step = 1; step <= steps; step++) {
    charge += dueCents(figureCents + charge, late.latePct(dateOfEpochDay(dueBy + step * DAYS_PER_CHARGE)), halfUp);
  }
  return charge;
}

/** What is due on the claim now, in cents: the due fixed when it was settled; else the due as shown that its
 *  person's last statement bound it to, late charge and floor included; else its figure (see
 *  claimFigureCents) held to at least `floorCents` (the floor in force on its date), with the late charge on that floored figure while it is open and overdue; a correction
 *  has no floor and no charge. Both round as roundsHalfUp decides by the rounding mode and the policy date, `claims` holding the
 *  claim a correction corrects. Null when the figure is n/a. */
export function claimOwedCents(claim: ClaimView, gradeRates: readonly RateView[], floorCents: number, late: Lateness,
  claims: readonly ClaimView[]): number | null {
  if (claim.settledDue !== null) return cents(claim.settledDue);
  if (isBound(claim)) return claim.statedDue === null ? null : cents(claim.statedDue);
  const halfUp = roundsHalfUp(claim, claims, late.policyDate, late.rounding);
  const figure = claimFigureCents(claim, gradeRates, halfUp);
  if (figure === null) return null;
  if (isCorrection(claim)) return figure;
  const floored = Math.max(floorCents, figure);
  return floored + lateChargeCents(claim, floored, late, halfUp);
}

// The claims on a sheet, as the store holds them.
@Injectable({ providedIn: 'root' })
export class ClaimsApi {
  private readonly http = inject(HttpClient);

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }

  /** The sheet's claims, newest first. */
  list(sheetId: string): Promise<ClaimView[]> {
    return firstValueFrom(this.http.get<ClaimView[]>(`${this.url(sheetId)}/claims`));
  }

  /** Every tag ever used on the sheet, by name. */
  tags(sheetId: string): Promise<string[]> {
    return firstValueFrom(this.http.get<string[]>(`${this.url(sheetId)}/tags`));
  }

  add(sheetId: string, personId: number, form: ClaimForm): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheetId)}/people/${personId}/claims`, form));
  }

  /** Open a claim's editor, locking the claim for other users until it is saved or cancelled, or the
   *  sheet's today changes: refused (423) while another user's editor locks it, and refused for a claim
   *  that cannot be edited (not a DRAFT, or due by a date inside a closed period) and for a viewer (403). */
  lock(sheetId: string, id: number): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheetId)}/claims/${id}/lock`, {}));
  }

  /** Cancel a claim's editor, releasing the user's own lock on it. */
  unlock(sheetId: string, id: number): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheetId)}/claims/${id}/unlock`, {}));
  }

  /** Save a claim's editor: refused (423) while another user's editor locks the claim. */
  update(sheetId: string, id: number, form: ClaimForm): Promise<ClaimView> {
    return firstValueFrom(this.http.put<ClaimView>(`${this.url(sheetId)}/claims/${id}`, form));
  }

  /** Move a claim to its next status: refused when its status does not allow it, and settling or
   *  approving is refused (403) to the user who opened or last edited the claim; approving and rejecting (with a
   *  reason) a PENDING claim, and re-settling a SETTLED one, are refused (403) to all but a steward. A DRAFT
   *  is deleted, and a DELETED claim restored as a DRAFT only on the sheet's today it was deleted. Opening a
   *  claim whose due is above the review threshold leaves it REVIEW, which a steward's review makes OPEN
   *  (refused, 403, to all but a steward). */
  act(sheetId: string, id: number, action: ClaimAction, reason?: string): Promise<ClaimView> {
    const body = action === 'reject' || action === 'void' ? { reason: reason ?? '' }
      : action === 'settle' && reason ? { on: reason } : {};
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheetId)}/claims/${id}/${action}`, body));
  }

  /** Defer an open claim until a date after the sheet's today: refused for a claim that is not OPEN,
   *  a date not after today, and a claim whose due-by is inside a closed period. */
  defer(sheetId: string, id: number, until: string): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheetId)}/claims/${id}/defer`, { until }));
  }

  /** Re-date an OPEN or PENDING claim: refused (403) to all but a steward, and refused for another status,
   *  a claim whose due-by is inside a closed period, and a new date after its person's retirement. */
  redate(sheetId: string, id: number, date: string): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheetId)}/claims/${id}/redate`, { date }));
  }

  /** Split an amount (more than 0.00, less than the claim's amount) off an OPEN claim into a new OPEN
   *  claim: refused for a claim that is not OPEN, a deferred claim, and a claim whose due-by is inside a closed period. */
  split(sheetId: string, id: number, amount: string): Promise<SplitView> {
    return firstValueFrom(this.http.post<SplitView>(`${this.url(sheetId)}/claims/${id}/split`, { amount }));
  }

  /** Part-settle an OPEN claim: split the amount off it and settle the new claim at once (PENDING above a
   *  clerk's limit); refused whole when the split or the settle would be, the four-eyes and proof rules
   *  judged on the claim split. */
  settlePart(sheetId: string, id: number, amount: string): Promise<SplitView> {
    return firstValueFrom(this.http.post<SplitView>(`${this.url(sheetId)}/claims/${id}/settle-part`, { amount }));
  }

  /** Raise a claim's proofs count by one, of the kind given (null: none chosen, a receipt): refused for a
   *  SETTLED or VOID claim, and a claim dated inside a closed period. */
  addProof(sheetId: string, id: number, kind: ProofKind | null = null): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheetId)}/claims/${id}/proof`, { kind }));
  }

  /** Comment a claim: refused for an empty comment. */
  comment(sheetId: string, id: number, text: string): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheetId)}/claims/${id}/comments`, { text }));
  }

  /** Add a line to a draft claim, its amount becoming the total of its lines: refused for a claim that is
   *  not a DRAFT, one whose due-by is inside a closed period and one another user's editor locks. */
  addLine(sheetId: string, id: number, line: { label: string; qty: number; unit: string }): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheetId)}/claims/${id}/lines`, line));
  }

  /** The claim's timeline: the audit lines about it, newest first, as the file holds them. */
  timeline(sheetId: string, id: number): Promise<string[]> {
    return firstValueFrom(this.http.get<string[]>(`${this.url(sheetId)}/claims/${id}/timeline`));
  }

  /** Remind an open or pending claim: refused for a claim in any other status, and for one already
   *  reminded on the sheet's today. */
  remind(sheetId: string, id: number): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheetId)}/claims/${id}/remind`, {}));
  }

  /** Waive an open or pending claim's late charge: refused (403) to all but a steward, and refused for
   *  a claim in any other status or one already waived. */
  waive(sheetId: string, id: number): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheetId)}/claims/${id}/waive`, {}));
  }

  /** Share a claim with another person on the sheet: refused for its own person, a person already
   *  holding a share, shares totalling more than 100.00, and a settled, void or deleted claim. */
  share(sheetId: string, id: number, personId: number, share: string): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheetId)}/claims/${id}/shares`, { personId, share }));
  }

  /** Assign a REVIEW claim's review to a user: refused (403) to all but a steward, and refused for a
   *  blank user and a claim in any other status. */
  assignReview(sheetId: string, id: number, user: string): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheetId)}/claims/${id}/review-assignee`, { user }));
  }

  /** Hold an open or pending claim, or release a held one: refused for a claim in any other status. */
  hold(sheetId: string, id: number): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheetId)}/claims/${id}/hold`, {}));
  }

  /** Move a draft, open or pending claim to another person, as their newest claim: refused (403) to
   *  all but a steward, and refused when its status does not allow it. */
  reassign(sheetId: string, id: number, personId: number): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheetId)}/claims/${id}/reassign`, { personId }));
  }

  /** Open the claims of these ids together in list order; each that cannot be opened is left as it is. */
  openSelected(sheetId: string, ids: readonly number[]): Promise<BulkOpenView> {
    return firstValueFrom(this.http.post<BulkOpenView>(`${this.url(sheetId)}/claims/open`, { ids }));
  }

  settleSelected(sheetId: string, ids: readonly number[]): Promise<BulkSettleView> {
    return firstValueFrom(this.http.post<BulkSettleView>(`${this.url(sheetId)}/claims/settle`, { ids }));
  }

  /** Move the claims of these ids to the person together in list order; each that cannot be moved is left as it is. */
  reassignSelected(sheetId: string, ids: readonly number[], personId: number): Promise<BulkReassignView> {
    return firstValueFrom(this.http.post<BulkReassignView>(`${this.url(sheetId)}/claims/reassign`, { ids, personId }));
  }

  /** Re-date the claims of these ids together in list order; each that cannot be re-dated is left as it is. */
  redateSelected(sheetId: string, ids: readonly number[], date: string): Promise<BulkRedateView> {
    return firstValueFrom(this.http.post<BulkRedateView>(`${this.url(sheetId)}/claims/redate`, { ids, date }));
  }

  /** Void the claims of these ids together in list order, with the reason (blank: none); each that
   *  cannot be voided is left as it is. */
  voidSelected(sheetId: string, ids: readonly number[], reason: string): Promise<BulkVoidView> {
    return firstValueFrom(this.http.post<BulkVoidView>(`${this.url(sheetId)}/claims/void`,
      { ids, reason: reason === '' ? null : reason }));
  }

  /** Add a draft claim per line of name,amount,date,note, in order; refused as a whole, adding
   *  nothing, when any line is refused. */
  paste(sheetId: string, text: string): Promise<PasteView> {
    return firstValueFrom(this.http.post<PasteView>(`${this.url(sheetId)}/claims/paste`, { text }));
  }
}
