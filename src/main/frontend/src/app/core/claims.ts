import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import type { DatedValue } from './session';

/**
 * DRAFT when added; DRAFT → OPEN → SETTLED, and DRAFT, OPEN or PENDING → VOID (SETTLED → VOID by a
 * steward within 7 days of its settlement). A clerk settling a claim
 * above the clerk limit sends it PENDING; a steward approves it (SETTLED) or rejects it (OPEN again).
 * A DRAFT may be DELETED, and restored to a DRAFT on the sheet's today it was deleted.
 */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'PENDING' | 'SETTLED' | 'VOID' | 'DELETED';

/**
 * A move of a claim's status: open, settle, approve, reject, void, re-settle a SETTLED one, delete a
 * DRAFT, or restore a DELETED one.
 */
export type ClaimAction = 'open' | 'settle' | 'approve' | 'reject' | 'void' | 'resettle' | 'delete' | 'restore';

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
  /** yyyy-mm-dd: the date the claim is due by: the terms-th business day after its date, skipping weekends and the sheet's holidays. */
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
  /** The user who last edited the claim; null (or absent, as kept by an old snapshot) when never. Like the opener, they cannot settle or approve it. */
  editedBy?: string | null;
  /** The number of the bulk settle batch that settled the claim; null (or absent) when none did. */
  settleBatch?: number | null;
  /** Whether the claim is bound by its person's last statement to the figure it had then. */
  stated: boolean;
  /** The figure the statement bound the claim to, with two decimals; null when unbound, or when it was n/a. */
  statedDue: string | null;
  /** The number of the SETTLED claim this one corrects; null unless it is a correction. */
  corrects: string | null;
  /** The claim's external reference; null when it has none. */
  ref: string | null;
  /** The claim's tags, sorted (trimmed, lower case, no repeats); empty (or absent, as kept by an old snapshot) for none. */
  tags?: string[] | null;
  /** Every tag the claim has ever carried, sorted; empty (or absent, as kept by an old snapshot) for none. */
  tagsUsed?: string[] | null;
  /** The claim's status log, oldest first: one line per status it has had. */
  log: ClaimLogLine[];
  /** yyyy-mm-dd: the date the claim is deferred until; null when it was never deferred. */
  deferredUntil: string | null;
  /** How many proofs the claim has. Above the proof threshold, a claim with none cannot be settled or approved. */
  proofs: number;
  /** The number of the claim this one was split from; null unless it was split off another. */
  splitFrom: string | null;
  /** The user whose open editor locks the claim for everyone else; null when none does. */
  lockedBy: string | null;
  /** Whether the claim was added while the period it is dated in was soft-closed. */
  lateEntry: boolean;
  /** yyyy-mm-dd: the last day (the sheet's today) a reminder was written for the claim; null when never. */
  remindedOn: string | null;
  /** The reason from the sheet's list the claim was voided with; null (or absent, as kept by an old snapshot) when none. */
  voidReason?: string | null;
  /** The claim's comments, oldest first; empty (or absent, as kept by an old snapshot) for none. */
  comments?: ClaimComment[] | null;
}

/** A comment on a claim: by whom, on the sheet's today then, and its text. */
export interface ClaimComment {
  by: string;
  /** yyyy-mm-dd */
  on: string;
  text: string;
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

/** A claim's tags as it shows them: sorted, comma and space separated. */
export function tagsText(claim: ClaimView): string {
  return (claim.tags ?? []).join(', ');
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
 * The date (yyyy-mm-dd) a claim's lateness counts from: its due-by plus the grace days its grade has
 * in force on the claim's date (0 when none). The due-by shown is unchanged.
 */
export function lateFrom(claim: ClaimView, grace = 0): string {
  if (grace === 0) return dueBy(claim);
  return new Date(Date.parse(dueBy(claim)) + grace * 86_400_000).toISOString().slice(0, 10);
}

/**
 * Whether a claim is overdue: it is OPEN or PENDING, its due-by plus its grace days is before the
 * sheet's today, and it is not deferred on it.
 */
export function overdue(claim: ClaimView, today: string, grace = 0): boolean {
  return isOpen(claim) && today !== '' && lateFrom(claim, grace) < today && !deferred(claim, today);
}

/** What a late charge is worked from: the sheet's late percent by from-date and its today. */
export interface LateTerms {
  /** The late percent's values by from-date (see valueOn). */
  history: DatedValue[];
  /** The sheet's today (yyyy-mm-dd); empty when none is loaded. */
  today: string;
  /** The claim's grace days (see lateFrom); 0 or absent when none. */
  grace?: number;
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

/**
 * How many full 30 days an overdue claim is past its due-by plus its grace (see lateFrom) on the
 * sheet's today; 0 when not overdue.
 */
export function latePeriods(claim: ClaimView, late: LateTerms): number {
  if (!overdue(claim, late.today, late.grace)) return 0;
  const days = (Date.parse(late.today) - Date.parse(lateFrom(claim, late.grace))) / 86_400_000;
  return Math.floor(days / 30);
}

/**
 * The 30-day boundary dates (yyyy-mm-dd) an overdue claim has passed on the sheet's today: its due-by
 * plus its grace (see lateFrom), plus 30, 60, 90 ... days, one for every full 30 days it is overdue.
 */
export function lateBoundaries(claim: ClaimView, late: LateTerms): string[] {
  const from = Date.parse(lateFrom(claim, late.grace));
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
  /** The number of the settle batch it was; null when it settled none. */
  batch: number | null;
}

/** What a bulk reassign did: the claims reassigned, as they are now, and how many were refused. */
export interface BulkReassignView {
  reassigned: ClaimView[];
  refused: number;
}

export interface ClaimForm {
  amount: string;
  /** Empty means the sheet's today (or, on an edit, the claim's own date). */
  date: string | null;
  note: string;
  /** The number of the claim a correction corrects; null on any other claim. */
  corrects: string | null;
  /** The claim's external reference, used at most once on the sheet; blank for none. */
  ref: string;
  /** The claim's tags, comma separated; blank for none (and on an edit, left out to keep the claim's own). */
  tags?: string;
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

// The claim each person's form is editing, by user, sheet and person: kept across loads of the sheet,
// so an editor left open is still open when the sheet is shown again.
@Injectable({ providedIn: 'root' })
export class ClaimEditors {
  private readonly editing = signal<Record<string, number>>({});

  /** The id of the claim open in the form, or null when the form adds a new claim. */
  of(key: string): number | null {
    return this.editing()[key] ?? null;
  }

  set(key: string, id: number | null): void {
    this.editing.update((all) => {
      const { [key]: _, ...rest } = all;
      return id === null ? rest : { ...rest, [key]: id };
    });
  }
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
   * Open a claim's editor, locking the claim for every other user until it is saved or cancelled or
   * the sheet's today changes; refused (409) when the claim cannot be edited, (423) when another
   * user's editor holds it, and (403) for a viewer.
   */
  lock(sheet: string, id: number): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheet)}/claims/${id}/lock`, null));
  }

  /** Cancel this user's editor of a claim, releasing its lock. */
  unlock(sheet: string, id: number): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheet)}/claims/${id}/unlock`, null));
  }

  /**
   * Move a claim's status; refused (409) when its status does not allow the move, or when the user
   * settling or approving it is the one who opened it or last edited it, and (403) when approving or rejecting it is
   * not a steward. A reject carries its reason. Re-settling is a steward's (403 otherwise), of a
   * SETTLED claim (409 otherwise). A void carries the reason chosen from the sheet's list (409 when
   * the list is not empty and none, or one not on it, is given).
   */
  act(sheet: string, id: number, action: ClaimAction, reason?: string): Promise<ClaimView> {
    const body = action === 'reject' ? { reason: reason ?? '' } : action === 'void' ? { reason: reason || null } : null;
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheet)}/claims/${id}/${action}`, body));
  }

  /**
   * Defer an OPEN claim until a date; refused (409) when the claim is not OPEN, the date is not after
   * the sheet's today, or the claim is dated inside a closed period.
   */
  /** Remind an OPEN or PENDING claim; refused when it was already reminded on the sheet's today. */
  remind(sheet: string, id: number): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheet)}/claims/${id}/remind`, {}));
  }

  /** Comment on a claim; refused (400) when the comment is blank. */
  comment(sheet: string, id: number, text: string): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheet)}/claims/${id}/comments`, { text }));
  }

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
   * Re-date an OPEN or PENDING claim; refused (403) when the user is not a steward, and (409) when the
   * claim is not OPEN or PENDING, either date is inside a closed period, or the new date is after its
   * person's retirement.
   */
  redate(sheet: string, id: number, date: string): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheet)}/claims/${id}/redate`, { date }));
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

  /**
   * Reassign the selected claims to a person together, in list order, each as its Reassign button
   * would; those that cannot be moved are refused and left as they are.
   */
  reassignAll(sheet: string, claimIds: number[], personId: number): Promise<BulkReassignView> {
    return firstValueFrom(
      this.http.post<BulkReassignView>(`${this.url(sheet)}/claims/reassign`, { claimIds, personId }),
    );
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}`;
  }
}
