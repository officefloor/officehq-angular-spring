import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import type { DatedValue } from './session';

/**
 * DRAFT when added; DRAFT → OPEN → SETTLED, and DRAFT, OPEN or PENDING → VOID (SETTLED → VOID by a
 * steward within 7 days of its settlement). A clerk settling a claim
 * above the clerk limit sends it PENDING; a steward approves it (SETTLED) or rejects it (OPEN again).
 */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'PENDING' | 'SETTLED' | 'VOID';

/** A move of a claim's status: open, settle, approve, reject, void, or re-settle a SETTLED one. */
export type ClaimAction = 'open' | 'settle' | 'approve' | 'reject' | 'void' | 'resettle';

/** Whether a claim is open: OPEN, or PENDING a steward's approval. */
export function isOpen(claim: ClaimView): boolean {
  return claim.status === 'OPEN' || claim.status === 'PENDING';
}

export interface ClaimView {
  id: number;
  personId: number;
  /** The amount with two decimals, e.g. "10.25". */
  amount: string;
  /** yyyy-mm-dd */
  date: string;
  /** yyyy-mm-dd: the date the claim is due by, fixed when it was added from its person's grade's terms. */
  dueBy: string;
  note: string;
  status: ClaimStatus;
  /**
   * The number given when the claim was opened: sheet-wide (e.g. "3"), or under period numbering
   * within its period (e.g. "P1-2", or "X-1" in no period); null while DRAFT, and kept once VOID.
   */
  number: string | null;
  /**
   * The due fixed when the claim was settled, with two decimals; null when it is not fixed (not
   * settled, or the grade had no rate then), so its due follows the current rate.
   */
  settledDue: string | null;
  /** The user who opened the claim; null until it is opened. The opener cannot settle it. */
  openedBy: string | null;
  /** Whether the claim is bound by its person's last statement to the figure it had then. */
  stated: boolean;
  /** The figure the statement bound the claim to, with two decimals; null when unbound, or when it was n/a. */
  statedDue: string | null;
  /** The number of the SETTLED claim this one corrects; null unless it is a correction. */
  corrects: string | null;
  /** The claim's status log, oldest first: one line per status it has had. */
  log: ClaimLogLine[];
  /** yyyy-mm-dd: the date the claim is deferred until; null when it was never deferred. */
  deferredUntil: string | null;
  /** How many proofs the claim has. Above the proof threshold, a claim with none cannot be settled or approved. */
  proofs: number;
  /** The number of the claim this one was split from; null unless it was split off another. */
  splitFrom: string | null;
}

/** What a split did: the original claim, as it is now, and the new claim split off it. */
export interface SplitView {
  original: ClaimView;
  split: ClaimView;
}

/** A status a claim moved to, by whom, on the sheet's today then, with its due as computed then. */
export interface ClaimLogLine {
  status: ClaimStatus;
  by: string;
  /** yyyy-mm-dd */
  on: string;
  /** The due with two decimals, or n/a. */
  due: string;
}

/** Whether a claim is a correction: its amount is below 0.00. It has no floor and no late charge. */
export function isCorrection(claim: ClaimView): boolean {
  return claim.amount.startsWith('-') && cents(claim.amount) !== 0;
}

/**
 * The status a claim had at the end of a day (yyyy-mm-dd), as its status log records it: the last line
 * dated on or before it, or null when the claim was added after it. A claim with no log (added before
 * the log) has its current status.
 */
export function statusAt(claim: ClaimView, day: string): ClaimStatus | null {
  if (claim.log.length === 0) return claim.status;
  let then: ClaimStatus | null = null;
  for (const line of claim.log) {
    if (line.on <= day) then = line.status;
  }
  return then;
}

/** Whether a claim counts toward what is claimed and due: only OPEN, PENDING and SETTLED claims do. */
export function counts(claim: ClaimView): boolean {
  return isOpen(claim) || claim.status === 'SETTLED';
}

/** A claim's due-by (yyyy-mm-dd), fixed when it was added. */
export function dueBy(claim: ClaimView): string {
  return claim.dueBy;
}

/**
 * Whether a claim is deferred on the sheet's today: it is OPEN and today has not yet reached the date
 * it is deferred until. Until then it counts in no total, is not overdue and cannot be settled.
 */
export function deferred(claim: ClaimView, today: string): boolean {
  return claim.status === 'OPEN' && claim.deferredUntil !== null && today !== '' && today < claim.deferredUntil;
}

/**
 * Whether a claim is overdue: it is OPEN or PENDING, its due-by is before the sheet's today, and it is
 * not deferred on it.
 */
export function overdue(claim: ClaimView, today: string): boolean {
  return isOpen(claim) && today !== '' && dueBy(claim) < today && !deferred(claim, today);
}

/** What a late charge is worked from: the sheet's late percent by from-date and its today. */
export interface LateTerms {
  /** The late percent's values by from-date (see valueOn). */
  history: DatedValue[];
  /** The sheet's today (yyyy-mm-dd); empty when none is loaded. */
  today: string;
}

/**
 * Whether a claim's live figures round half up (else half to even): when the date it rounds by is
 * before the policy date, or the sheet's today when no policy date is set. A correction rounds by the
 * date of the claim it corrects, found by number among its person's claims (its own when not found).
 */
export function roundsHalfUp(claim: ClaimView, claims: ClaimView[], policyDate: string, today: string): boolean {
  const corrected = claim.corrects === null
    ? undefined
    : claims.find((c) => c.personId === claim.personId && c.number === claim.corrects);
  const date = corrected?.date ?? claim.date;
  const policy = policyDate || today;
  return policy !== '' && date < policy;
}

/** How many full 30 days an overdue claim is past its due-by on the sheet's today; 0 when not overdue. */
export function latePeriods(claim: ClaimView, late: LateTerms): number {
  if (!overdue(claim, late.today)) return 0;
  const days = (Date.parse(late.today) - Date.parse(dueBy(claim))) / 86_400_000;
  return Math.floor(days / 30);
}

/**
 * The 30-day boundary dates (yyyy-mm-dd) an overdue claim has passed on the sheet's today: its due-by
 * plus 30, 60, 90 ... days, one for every full 30 days it is overdue.
 */
export function lateBoundaries(claim: ClaimView, late: LateTerms): string[] {
  const from = Date.parse(dueBy(claim));
  return Array.from({ length: latePeriods(claim, late) }, (_, i) =>
    new Date(from + (i + 1) * 30 * 86_400_000).toISOString().slice(0, 10));
}

/** What a bulk open did: the claims opened, as they are now, and how many were refused. */
export interface BulkOpenView {
  opened: ClaimView[];
  refused: number;
}

/** What a bulk settle did: the claims settled (or sent PENDING), as they are now, and how many were refused. */
export interface BulkSettleView {
  settled: ClaimView[];
  refused: number;
}

export interface ClaimForm {
  amount: string;
  /** Empty means the sheet's today (or, on an edit, the claim's own date). */
  date: string | null;
  note: string;
  /** The number of the claim a correction corrects; null on any other claim. */
  corrects: string | null;
}

/** An amount as a claim takes it: up to two decimals, below 0.00 only on a correction. */
export const AMOUNT = /^-?\d{1,12}(\.\d{1,2})?$/;

/** A claim number as the form takes it: sheet-wide (e.g. "3") or within a period (e.g. "P1-2", "X-1"). */
export const CLAIM_NUMBER = /^([1-9]\d{0,8}|\S.{0,200}-[1-9]\d{0,8})$/;

/** A two-decimal amount in whole cents, so sums are exact. */
export function cents(amount: string): number {
  const negative = amount.startsWith('-');
  const [whole, frac = ''] = (negative ? amount.slice(1) : amount).split('.');
  const abs = Number(whole) * 100 + Number(frac.padEnd(2, '0'));
  return negative ? -abs : abs;
}

/** Whole cents shown with two decimals. */
export function money(cents: number): string {
  const abs = Math.abs(cents);
  return `${cents < 0 ? '-' : ''}${Math.trunc(abs / 100)}.${String(abs % 100).padStart(2, '0')}`;
}

// The claims people on a sheet make, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class ClaimsApi {
  private readonly http = inject(HttpClient);

  /** The sheet's claims, newest first. */
  list(sheet: string): Promise<ClaimView[]> {
    return firstValueFrom(this.http.get<ClaimView[]>(`${this.url(sheet)}/claims`));
  }

  add(sheet: string, personId: number, form: ClaimForm): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheet)}/people/${personId}/claims`, form));
  }

  /** Add a DRAFT claim per pasted line of name,amount,date,note, in order; refused (400) as a whole. */
  paste(sheet: string, text: string): Promise<ClaimView[]> {
    return firstValueFrom(this.http.post<ClaimView[]>(`${this.url(sheet)}/claims/paste`, { text }));
  }

  edit(sheet: string, id: number, form: ClaimForm): Promise<ClaimView> {
    return firstValueFrom(this.http.put<ClaimView>(`${this.url(sheet)}/claims/${id}`, form));
  }

  /**
   * Move a claim's status; refused (409) when its status does not allow the move, or when the user
   * settling or approving it is the one who opened it, and (403) when approving or rejecting it is
   * not a steward. A reject carries its reason. Re-settling is a steward's (403 otherwise), of a
   * SETTLED claim (409 otherwise).
   */
  act(sheet: string, id: number, action: ClaimAction, reason?: string): Promise<ClaimView> {
    const body = action === 'reject' ? { reason: reason ?? '' } : null;
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheet)}/claims/${id}/${action}`, body));
  }

  /**
   * Defer an OPEN claim until a date; refused (409) when the claim is not OPEN, the date is not after
   * the sheet's today, or the claim is dated inside a closed period.
   */
  /** Raise a claim's proofs count by one; refused for a SETTLED or VOID claim. */
  addProof(sheet: string, id: number): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheet)}/claims/${id}/proof`, {}));
  }

  defer(sheet: string, id: number, until: string): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheet)}/claims/${id}/defer`, { until }));
  }

  /**
   * Split an amount (above 0.00, below the claim's amount) off an OPEN claim into a new OPEN claim;
   * refused (409) when the claim is not OPEN, is deferred, or is dated inside a closed period.
   */
  split(sheet: string, id: number, amount: string): Promise<SplitView> {
    return firstValueFrom(this.http.post<SplitView>(`${this.url(sheet)}/claims/${id}/split`, { amount }));
  }

  /**
   * Move a DRAFT, OPEN or PENDING claim to another person on the sheet; refused (403) when the user is
   * not a steward, and (409) when the claim is SETTLED or VOID or cannot go to that person.
   */
  reassign(sheet: string, id: number, personId: number): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheet)}/claims/${id}/reassign`, { personId }));
  }

  /**
   * Open the selected claims together, in list order (people in the order they were added, each
   * person's claims newest first); those that cannot be opened are refused and left as they are.
   */
  openAll(sheet: string, claimIds: number[]): Promise<BulkOpenView> {
    return firstValueFrom(this.http.post<BulkOpenView>(`${this.url(sheet)}/claims/open`, { claimIds }));
  }

  settleAll(sheet: string, claimIds: number[]): Promise<BulkSettleView> {
    return firstValueFrom(this.http.post<BulkSettleView>(`${this.url(sheet)}/claims/settle`, { claimIds }));
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}`;
  }
}
