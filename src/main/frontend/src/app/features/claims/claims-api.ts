import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session, otherError } from '../../core/session';
import type { Person } from '../people/people-api';

/** An amount of 0.00 or more with up to two decimals. */
export const AMOUNT_PATTERN = /^\d{1,13}(\.\d{1,2})?$/;

/** A claim form's amount: up to two decimals, negative for a correction. */
export const CLAIM_AMOUNT_PATTERN = /^-?\d{1,13}(\.\d{1,2})?$/;

/** DRAFT when added, then OPEN, then SETTLED; a clerk settling one above the clerk limit sends it to
 *  PENDING, which a steward approves (SETTLED) or rejects (back to OPEN). A DRAFT, OPEN or PENDING
 *  claim may be VOID, and a steward may void a SETTLED one within 7 days of its settlement. A DRAFT
 *  claim may be DELETED (counting nowhere) and restored to DRAFT on the sheet's today it was deleted.
 *  A claim whose due is above the review threshold goes to REVIEW (counting nowhere, unnumbered)
 *  when opened, until a steward reviews it (OPEN); a REVIEW claim may be VOID. */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'PENDING' | 'SETTLED' | 'VOID' | 'DELETED' | 'REVIEW';

/** A move of a claim's status, as the server names it. */
export type ClaimAction = 'open' | 'settle' | 'void' | 'approve' | 'review' | 'resettle' | 'delete' | 'restore';

export interface Claim {
  id: number;
  /** What the claim was added as against a forward-only rate's mark: its own id, or for a copy in a
   *  clone the claim it copies was added as. */
  added: number;
  personId: number;
  /** Always with two decimals, as the server formats it. */
  amount: string;
  date: string;
  note: string;
  status: ClaimStatus;
  /** The sheet's number for the claim, given when it is settled or approved and kept if it is voided; null while DRAFT, OPEN, PENDING or REVIEW.
   *  A sheet-wide number, or under period numbering one within its period (P1-1, X-1 in no period). */
  number: string | null;
  /** The due fixed when the claim was settled, with two decimals; null, so the due is live, for
   *  a claim not SETTLED or settled when its grade had no rate. */
  settledDue: string | null;
  /** The user who opened the claim, who cannot settle it; null while DRAFT. */
  openedBy: string | null;
  /** The user who last edited the claim, who like its opener cannot settle or approve it; null
   *  when never edited. */
  editedBy: string | null;
  /** Whether the person's last statement bound the claim: it then shows and totals statedDue
   *  (a PENDING one too). */
  stated: boolean;
  /** The due the claim was bound to, with two decimals; null when unbound or n/a then. */
  statedDue: string | null;
  /** The number of the SETTLED claim this correction (a negative amount) corrects; null for others. */
  corrects: string | null;
  /** Where the claim is listed, newest (highest) first: set when it is added, and again when it
   *  is reassigned, so a moved claim is its new person's newest. */
  listed: number;
  /** yyyy-mm-dd: fixed when the claim was created, its date plus its person's own terms, else
   *  their grade's, else the sheet's, each in force on the sheet's today when it was created; later changes to the terms or its date leave it. */
  dueBy: string;
  /** The claim's status log, oldest first: one line per status it has had. */
  log: ClaimLogLine[];
  /** yyyy-mm-dd: the date the claim was deferred until; null when it was never deferred. */
  deferredUntil: string | null;
  /** How many proofs the claim has. */
  proofs: number;
  /** Of the claim's proofs, how many are receipts. */
  receiptProofs: number;
  /** Of the claim's proofs, how many are photos. */
  photoProofs: number;
  /** The number of the claim this one was split from; null when not made by a split. */
  splitFrom: string | null;
  /** The user whose open editor locks the claim for everyone else; null while unlocked. */
  lockedBy: string | null;
  /** Whether the claim was added while its period was soft-closed. */
  lateEntry: boolean;
  /** yyyy-mm-dd: the last day (the sheet's today then) the claim was reminded; null when never reminded. */
  reminded: string | null;
  /** Whether a steward waived the claim's late charge: it bears none from then on. */
  waived: boolean;
  /** Whether the claim is held: held and open, unless bound by a statement, it counts in no live total. */
  held: boolean;
  /** yyyy-mm-dd: the claim's review day (the sheet's today when it went to REVIEW); null when it never did. */
  reviewOn: string | null;
  pendingOn: string | null;
  /** The claim's external reference, unique on its sheet without regard to case; null when none. */
  ref: string | null;
  /** The claim's tags: lower-case, sorted and comma separated; null when none. */
  tags: string | null;
  /** The reason a VOID claim was voided with, from the sheet's list; null when none. */
  voidReason: string | null;
  /** The comments on the claim, oldest first. */
  comments: ClaimComment[];
  /** The claim's lines, in the order added; with lines its amount is their total. */
  lines: ClaimLine[];
  /** The number of the bulk settle batch that settled the claim (or sent it to PENDING); null
   *  when it was settled one at a time or not at all. */
  batch: number | null;
  /** The people the claim is shared with, in the order added. */
  shares: ClaimShare[];
  /** The user a steward assigned the claim's review to; null when never assigned. */
  reviewAssignee: string | null;
}

/** A share of a claim: the person holding it and their share, a percent with two decimals. */
export interface ClaimShare {
  personId: number;
  share: string;
}

/** A comment on a claim: who wrote it, the sheet's today then (yyyy-mm-dd), and its text. */
export interface ClaimComment {
  by: string;
  on: string;
  text: string;
}

/** A comment as the screen shows it: <user> on <date>: <text>. */
export function commentText(c: ClaimComment): string {
  return `${c.by} on ${c.on}: ${c.text}`;
}

/** A line of a claim: a label, a whole quantity of at least 1 and a unit amount with two decimals. */
export interface ClaimLine {
  label: string;
  qty: number;
  unit: string;
}

/** A claim line as the screen shows it: <label> <qty> x <unit>. */
export function lineText(l: ClaimLine): string {
  return `${l.label} ${l.qty} x ${l.unit}`;
}

/** A line of a claim's status log: the status, who moved the claim there, the sheet's today then
 *  (yyyy-mm-dd), and the claim's due at that moment with two decimals (null: n/a). */
export interface ClaimLogLine {
  status: ClaimStatus;
  by: string;
  on: string;
  due: string | null;
}

/** A status log line as the screen shows it: <STATUS> by <user> on <date> due=<due>. */
export function logLineText(l: ClaimLogLine): string {
  return `${l.status} by ${l.by} on ${l.on} due=${l.due ?? 'n/a'}`;
}

/** The status a claim had at the end of a day (yyyy-mm-dd), as its status log records it; null
 *  when it was not yet added then. A claim with no log at all is taken in its status now. */
export function statusOn(c: Claim, day: string): ClaimStatus | null {
  if (c.log.length === 0) return c.status;
  let status: ClaimStatus | null = null;
  for (const l of c.log) {
    if (l.on <= day) status = l.status;
  }
  return status;
}

/** Claims as they are listed: newest (or most recently reassigned) first. */
export function byListed(a: Claim, b: Claim): number {
  return b.listed - a.listed;
}

/** Whether a claim is a correction: its amount is negative. */
export function isCorrection(c: Claim): boolean {
  return c.amount.startsWith('-');
}

/** Whether a claim is open: OPEN, or PENDING (handled like an OPEN one). */
export function isOpen(c: Claim): boolean {
  return c.status === 'OPEN' || c.status === 'PENDING';
}

/** Whether a claim's due is bound to statedDue: stated (a PENDING claim too). */
export function isBound(c: Claim): boolean {
  return c.stated;
}

/** Whether a claim is deferred on the sheet's today: OPEN, deferred until a date after it. Until
 *  then it counts in no total, is not overdue and cannot be settled. */
export function deferred(c: Claim, today: string | null): boolean {
  return c.status === 'OPEN' && c.deferredUntil !== null && today !== null && today < c.deferredUntil;
}

/** Whether a claim counts toward claimed and due totals on the sheet's today: only open (OPEN or
 *  PENDING) and SETTLED ones do, but not a deferred one. */
export function counts(c: Claim, today: string | null): boolean {
  return (isOpen(c) || c.status === 'SETTLED') && !deferred(c, today);
}

/** A claim's due-by (yyyy-mm-dd), fixed when it was created. */
export function dueBy(c: Claim): string {
  return c.dueBy;
}

/** The date a claim is overdue after: its due-by plus the grace days of its person's grade in
 *  force on its date (0: none). */
export function lateFrom(c: Claim, grace = 0): string {
  return grace === 0 ? dueBy(c) : plusDays(dueBy(c), grace);
}

/** Whether a claim is overdue: open (OPEN or PENDING), not deferred, with its due-by plus its
 *  grace days before the sheet's today. */
export function overdue(c: Claim, today: string | null, grace = 0): boolean {
  return isOpen(c) && today !== null && lateFrom(c, grace) < today && !deferred(c, today);
}

/** Whether a claim is late for review: it is REVIEW and its review day plus the sheet's review
 *  SLA in days (null: none, never late) is before the sheet's today. */
export function reviewLate(c: Claim, today: string | null, sla: number | null): boolean {
  return c.status === 'REVIEW' && sla !== null && today !== null && c.reviewOn !== null
    && plusDays(c.reviewOn, sla) < today;
}

/** Whether a claim is late pending: it is PENDING and its pending day (when it last went to
 *  PENDING) plus the sheet's pending SLA in days (null: none, never late) is before the sheet's
 *  today. */
export function pendingLate(c: Claim, today: string | null, sla: number | null): boolean {
  return c.status === 'PENDING' && sla !== null && today !== null && c.pendingOn !== null
    && plusDays(c.pendingOn, sla) < today;
}

/** A date (yyyy-mm-dd) moved on by whole days. */
export function plusDays(date: string, days: number): string {
  return new Date(Date.parse(date) + days * 86_400_000).toISOString().slice(0, 10);
}

/** Whether a claim's live figures round half up: by the sheet's rounding mode in force on its date
 *  (a correction's, the date of the claim of its person it corrects, among `theirs`) when there is
 *  one; else when that date is before the sheet's policy date (null: blank, the sheet's today),
 *  rounding half to even otherwise. */
export function roundsHalfUp(c: Claim, theirs: readonly Claim[], policyDate: string | null, today: string | null,
                             roundingOn: (date: string) => string | null = () => null): boolean {
  const on = c.corrects === null ? c.date : theirs.find((o) => o.number === c.corrects)?.date ?? c.date;
  const mode = roundingOn(on);
  if (mode === 'half-up') return true;
  if (mode === 'half-even') return false;
  const policy = policyDate ?? today;
  if (policy === null) return false;
  return on < policy;
}

export interface ClaimForm {
  amount: string;
  /** null takes the sheet's today. */
  date: string | null;
  note: string;
  /** The number of the claim a correction corrects; null when none is named. */
  corrects: string | null;
  /** The claim's external reference; null (or blank) for none. */
  ref: string | null;
  /** The claim's tags, comma separated; null (or blank) for none. */
  tags: string | null;
}

export interface QuickAddForm {
  name: string;
  amount: string;
}

/** A quick-add's result: the person (just added when personAdded) and their new DRAFT claim. */
export interface QuickAdd {
  person: Person;
  personAdded: boolean;
  claim: Claim;
}

// The claims kept on the active sheet, read and written through the server.
@Injectable({ providedIn: 'root' })
export class ClaimsApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** Every claim on the sheet, newest first. */
  list(sheetId: string): Promise<Claim[]> {
    return firstValueFrom(this.http.get<Claim[]>(`${this.url(sheetId)}/claims`, { headers: this.headers() }));
  }

  /** Every tag ever used on the sheet, in order. */
  tags(sheetId: string): Promise<string[]> {
    return firstValueFrom(this.http.get<string[]>(`${this.url(sheetId)}/tags`, { headers: this.headers() }));
  }

  add(sheetId: string, personId: number, form: ClaimForm): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/people/${personId}/claims`, form, { headers: this.headers() }),
    );
  }

  /** Add a DRAFT claim for the person with the amount and note the named template has now. */
  addFromTemplate(sheetId: string, personId: number, template: string): Promise<Claim> {
    return firstValueFrom(this.http.post<Claim>(`${this.url(sheetId)}/people/${personId}/claims/from-template`, { template },
      { headers: this.headers() }));
  }

  /** Add a DRAFT claim for the person with the name, adding them (grade A) first when new. */
  quickAdd(sheetId: string, form: QuickAddForm): Promise<QuickAdd> {
    return firstValueFrom(this.http.post<QuickAdd>(`${this.url(sheetId)}/quick-add`, form, { headers: this.headers() }));
  }

  /** Add a DRAFT claim for each pasted line of name,amount,date,note, in order; all or none. */
  paste(sheetId: string, text: string): Promise<Claim[]> {
    return firstValueFrom(this.http.post<Claim[]>(`${this.url(sheetId)}/paste-claims`, { text }, { headers: this.headers() }));
  }

  update(sheetId: string, id: number, form: ClaimForm): Promise<Claim> {
    return firstValueFrom(this.http.put<Claim>(`${this.url(sheetId)}/claims/${id}`, form, { headers: this.headers() }));
  }

  /** Open a DRAFT claim's editor, locking it for other users until it is saved or cancelled. */
  lock(sheetId: string, id: number): Promise<Claim> {
    return firstValueFrom(this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/edit`, null, { headers: this.headers() }));
  }

  /** Close a claim's editor without saving, releasing this user's lock on it. */
  unlock(sheetId: string, id: number): Promise<Claim> {
    return firstValueFrom(this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/cancel`, null, { headers: this.headers() }));
  }

  changeStatus(sheetId: string, id: number, action: ClaimAction): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/${action}`, null, { headers: this.headers() }),
    );
  }

  /** Settle a claim, as of a date on or before the sheet's today (null: now). */
  settle(sheetId: string, id: number, on: string | null): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/settle`, { on }, { headers: this.headers() }),
    );
  }

  /** Void a claim with a reason from the sheet's list (null: none). */
  voidClaim(sheetId: string, id: number, reason: string | null): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/void`, { reason }, { headers: this.headers() }),
    );
  }

  /** Defer an OPEN claim until a date after the sheet's today. */
  defer(sheetId: string, id: number, until: string): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/defer`, { until }, { headers: this.headers() }),
    );
  }

  /** Remind an OPEN or PENDING claim, once per sheet-today. */
  remind(sheetId: string, id: number): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/remind`, null, { headers: this.headers() }),
    );
  }

  /** A steward waives an OPEN or PENDING claim's late charge, once. */
  waive(sheetId: string, id: number): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/waive`, null, { headers: this.headers() }),
    );
  }

  /** A steward assigns a REVIEW claim's review to a user. */
  assignReview(sheetId: string, id: number, user: string): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/review-assignee`, { user }, { headers: this.headers() }),
    );
  }

  /** Hold an OPEN or PENDING claim, or release a held one. */
  toggleHold(sheetId: string, id: number): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/hold`, null, { headers: this.headers() }),
    );
  }

  /** The claim's timeline: the audit lines about it, newest first, as the file holds them. */
  timeline(sheetId: string, id: number): Promise<string[]> {
    return firstValueFrom(
      this.http.get<string[]>(`${this.url(sheetId)}/claims/${id}/timeline`, { headers: this.headers() }),
    );
  }

  /** Comment on a claim. */
  comment(sheetId: string, id: number, text: string): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/comments`, { text }, { headers: this.headers() }),
    );
  }

  /** Add a line to a DRAFT claim. */
  addLine(sheetId: string, id: number, line: { label: string; qty: string; unit: string }): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/lines`, line, { headers: this.headers() }),
    );
  }

  /** Re-date an OPEN or PENDING claim (a steward only). */
  share(sheetId: string, id: number, personId: number, share: string): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/shares`, { personId, share }, { headers: this.headers() }),
    );
  }

  redate(sheetId: string, id: number, date: string): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/redate`, { date }, { headers: this.headers() }),
    );
  }

  /** Add a proof of a kind (blank: none chosen, a receipt) to a claim that is neither SETTLED nor VOID. */
  addProof(sheetId: string, id: number, kind = ''): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/proofs`, { kind }, { headers: this.headers() }),
    );
  }

  /** Reject a PENDING claim with a reason: it goes back to OPEN. */
  reject(sheetId: string, id: number, reason: string): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/reject`, { reason }, { headers: this.headers() }),
    );
  }

  /** Move a DRAFT, OPEN or PENDING claim to another person on the sheet. */
  reassign(sheetId: string, id: number, personId: number): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/reassign`, { personId }, { headers: this.headers() }),
    );
  }

  /** Split an amount off an OPEN claim: the original keeps the rest, and a new OPEN claim takes the amount. */
  split(sheetId: string, id: number, amount: string): Promise<{ original: Claim; split: Claim }> {
    return firstValueFrom(
      this.http.post<{ original: Claim; split: Claim }>(`${this.url(sheetId)}/claims/${id}/split`, { amount },
        { headers: this.headers() }),
    );
  }

  /** Part-settle an OPEN claim: the amount is split off and the new claim settled at once. */
  settlePart(sheetId: string, id: number, amount: string): Promise<{ original: Claim; split: Claim }> {
    return firstValueFrom(
      this.http.post<{ original: Claim; split: Claim }>(`${this.url(sheetId)}/claims/${id}/settle-part`, { amount },
        { headers: this.headers() }),
    );
  }

  /** Open each of the claims, in list order; those that cannot be opened are left as they are. */
  bulkOpen(sheetId: string, ids: number[]): Promise<{ opened: number; refused: number }> {
    return firstValueFrom(this.http.post<{ opened: number; refused: number }>(`${this.url(sheetId)}/claims/bulk-open`, { ids },
      { headers: this.headers() }));
  }

  /** Settle each of the claims, in list order, as settling one would; those that cannot be settled are left as they are. */
  bulkSettle(sheetId: string, ids: number[]): Promise<{ settled: number; refused: number }> {
    return firstValueFrom(this.http.post<{ settled: number; refused: number }>(`${this.url(sheetId)}/claims/bulk-settle`, { ids },
      { headers: this.headers() }));
  }

  /** Move each of the claims to the person, in list order, as reassigning one would; those that cannot be moved are left as they are. */
  bulkReassign(sheetId: string, ids: number[], personId: number): Promise<{ reassigned: number; refused: number }> {
    return firstValueFrom(this.http.post<{ reassigned: number; refused: number }>(`${this.url(sheetId)}/claims/bulk-reassign`,
      { ids, personId }, { headers: this.headers() }));
  }

  /** Re-date each of the claims to the date, in list order, as re-dating one would; those that cannot be re-dated are left as they are. */
  bulkRedate(sheetId: string, ids: number[], date: string): Promise<{ redated: number; refused: number }> {
    return firstValueFrom(this.http.post<{ redated: number; refused: number }>(`${this.url(sheetId)}/claims/bulk-redate`,
      { ids, date }, { headers: this.headers() }));
  }

  /** Void each of the claims with the reason (null: none), in list order, as voiding one would; those that cannot be voided are left as they are. */
  bulkVoid(sheetId: string, ids: number[], reason: string | null): Promise<{ voided: number; refused: number }> {
    return firstValueFrom(this.http.post<{ voided: number; refused: number }>(`${this.url(sheetId)}/claims/bulk-void`,
      { ids, reason }, { headers: this.headers() }));
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}

/** Why a claim form was refused; `correction` says whether its amount was negative. */
export function claimError(e: unknown, correction = false, ref: 'none' | 'add' | 'edit' = 'none', lined = false): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter an amount with up to two decimals.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim or person is no longer on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 409 && ref === 'add') return 'That reference is already used on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 409 && ref === 'edit') {
    return 'That reference is already used on this sheet, or the claim is no longer a draft.';
  }
  if (e instanceof HttpErrorResponse && e.status === 409) return 'Only a draft claim can be edited; void it and add a new one.';
  if (e instanceof HttpErrorResponse && e.status === 423) return 'The claim would be due in a closed period.';
  if (e instanceof HttpErrorResponse && e.status === 422 && lined) return 'A claim with lines takes their total as its amount.';
  if (e instanceof HttpErrorResponse && e.status === 422) {
    return correction
      ? "A correction must name the number of a settled claim of this person, and be dated by the person's retirement date."
      : "The date is after the person's retirement date.";
  }
  return otherError(e);
}

export function quickAddError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a name and an amount of 0.00 or more with up to two decimals.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'That name was just added by someone else; try again.';
  if (e instanceof HttpErrorResponse && e.status === 423) return 'The claim would be due in a closed period.';
  if (e instanceof HttpErrorResponse && e.status === 422) return "The sheet's today is after that person's retirement date.";
  return otherError(e);
}

/** Why a paste of claims was refused: nothing was added. */
export function pasteError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) {
    return 'Paste lines of name,amount,date,note: an amount with up to two decimals and a date as yyyy-mm-dd or blank.';
  }
  if (e instanceof HttpErrorResponse && e.status === 422) {
    return "Every name must be on the sheet, and every date by that person's retirement date; an amount below zero is a correction, which the paste does not take; a claim whose due is above the note threshold needs a note to be opened.";
  }
  if (e instanceof HttpErrorResponse && e.status === 409) {
    return 'Every reference must be new to the sheet and given only once in the paste, a line matching a claim must match a DRAFT, and every claim must be openable within its period\'s cap.';
  }
  if (e instanceof HttpErrorResponse && e.status === 403) return 'Your role may not open claims, which the paste does.';
  if (e instanceof HttpErrorResponse && e.status === 423) return 'A claim would be due in a closed period.';
  return otherError(e);
}

/** Why opening a claim's editor was refused. */
export function editError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 409) return 'Only a draft claim that no one else is editing can be edited.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim is no longer on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 423) return 'This claim is due in a closed period.';
  return otherError(e);
}

/** Why a status change was refused; `opener` says whether the user is the one who opened or last edited the claim. */
export const VOID_REASON_NEEDED = 'Choose a reason from the sheet\'s list to void this claim.';

/** The message for a refused void: without a reason from the sheet's list while it has one (422),
 *  else as for any status change. */
export function voidError(e: unknown, opener: boolean): string {
  if (e instanceof HttpErrorResponse && e.status === 422) return VOID_REASON_NEEDED;
  return statusError(e, opener);
}

export function deleteError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 409) return 'Only a draft claim that no one else is editing can be deleted.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim is no longer on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 423) return 'This claim is due in a closed period.';
  return otherError(e);
}

export function restoreError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 409) return "Only a deleted claim can be restored, and only on the sheet's today it was deleted.";
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim is no longer on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 423) return 'This claim is due in a closed period.';
  return otherError(e);
}

/** Why a settle was refused: as {@link statusError} has it, but a date it is taken as of may be refused too. */
export function settleError(e: unknown, opener: boolean): string {
  if (e instanceof HttpErrorResponse && e.status === 422) {
    return 'Settle the claim as of a date on or before the sheet\'s today when the claim was already on the sheet; '
      + 'a claim whose due is above the proof threshold needs a proof (one of each proof kind, while the sheet has any) to be settled.';
  }
  return statusError(e, opener);
}

export function statusError(e: unknown, opener: boolean): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a reason for rejecting the claim.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'This claim cannot make that change from its status, is deferred, was settled more than 7 days ago, or its person already has the most open claims its period allows.';
  if (e instanceof HttpErrorResponse && e.status === 403) {
    return opener
      ? 'The user who opened or last edited this claim cannot settle or approve it, or your role may not make that change.'
      : 'Your role may not make that change to this claim.';
  }
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim is no longer on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 423) return 'This claim is due in a closed period.';
  if (e instanceof HttpErrorResponse && e.status === 422) {
    return 'This claim\'s due is above a threshold: it needs a note to be opened, or a proof (one of each proof kind, while the sheet has any) to be settled or approved.';
  }
  return otherError(e);
}

/** The kinds a proof is: a receipt (when none is chosen) or a photo. */
export const PROOF_KINDS = ['receipt', 'photo'] as const;

/** Why adding a proof was refused. */
export function proofError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 409) return 'A settled or void claim takes no more proofs.';
  if (e instanceof HttpErrorResponse && e.status === 422) return 'A proof is a receipt or a photo.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim is no longer on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 423) return 'This claim is due in a closed period.';
  return otherError(e);
}

/** Why a reminder was refused. */
export function remindError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 409) return "Only an open or pending claim not yet reminded on the sheet's today can be reminded.";
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim is no longer on this sheet.';
  return otherError(e);
}

/** Why assigning a claim's review was refused. */
export function assignReviewError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 403) return 'Only a steward can assign a review.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'Only a claim in review can have its review assigned.';
  if (e instanceof HttpErrorResponse && (e.status === 422 || e.status === 400)) return 'Enter the user to assign the review to.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim is no longer on this sheet.';
  return otherError(e);
}

/** Why waiving a claim's late charge was refused. */
export function waiveError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 403) return "Only a steward can waive a claim's late charge.";
  if (e instanceof HttpErrorResponse && e.status === 409) return "Only an open or pending claim not already waived can have its late charge waived.";
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim is no longer on this sheet.';
  return otherError(e);
}

/** Why holding or releasing a claim was refused. */
export function holdClaimError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 409) return 'Only an open or pending claim can be held or released.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim is no longer on this sheet.';
  return otherError(e);
}

/** Why a comment was refused. */
export function commentError(e: unknown): string {
  if (e instanceof HttpErrorResponse && (e.status === 400 || e.status === 422)) return 'Enter some text to comment.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim is no longer on this sheet.';
  return otherError(e);
}

/** Why a line was refused. */
export function lineError(e: unknown): string {
  if (e instanceof HttpErrorResponse && (e.status === 400 || e.status === 422)) {
    return 'Enter a label, a whole quantity of at least 1 and a unit amount with up to two decimals.';
  }
  if (e instanceof HttpErrorResponse && e.status === 409) return 'Only a draft claim that no one else is editing can have lines added.';
  if (e instanceof HttpErrorResponse && e.status === 423) return 'This claim is due in a closed period.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim is no longer on this sheet.';
  return otherError(e);
}

/** Why a deferral was refused. */
export function deferError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a date as yyyy-mm-dd.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'Only an open claim can be deferred.';
  if (e instanceof HttpErrorResponse && e.status === 422) return "Defer the claim until a date after the sheet's today.";
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim is no longer on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 423) return 'This claim is due in a closed period.';
  return otherError(e);
}

/** Why a share was refused. */
export function shareError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a share as a percent with up to two decimals.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'A settled, void or deleted claim cannot be shared, nor a share held twice.';
  if (e instanceof HttpErrorResponse && e.status === 422) return "Share with another person, more than 0.00, the claim's shares totalling at most 100.00.";
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim or person is no longer on this sheet.';
  return otherError(e);
}

/** Why a re-dating was refused. */
export function redateError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a date as yyyy-mm-dd.';
  if (e instanceof HttpErrorResponse && e.status === 403) return 'Only a steward can re-date a claim.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'Only an open or pending claim can be re-dated.';
  if (e instanceof HttpErrorResponse && e.status === 422) return "The new date is after the person's retirement date.";
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim is no longer on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 423) return 'This claim is due in a closed period.';
  return otherError(e);
}

/** Why a re-settle was refused. */
export function resettleError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 403) return 'Only a steward can re-settle a claim.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'Only a settled claim can be re-settled.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim is no longer on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 423) return 'This claim is due in a closed period.';
  return otherError(e);
}

/** Why a reassignment was refused. */
export function reassignError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 403) return 'Your role may not reassign a claim.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'Only a draft, open or pending claim can move to another person.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim or person is no longer on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 423) return 'This claim is due in a closed period.';
  if (e instanceof HttpErrorResponse && e.status === 422) return "The claim's date is after that person's retirement date.";
  return otherError(e);
}

/** Why a split was refused. */
export function splitError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter an amount with up to two decimals.';
  if (e instanceof HttpErrorResponse && e.status === 403) return 'Your role may not open a claim, so it may not split one.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'Only an open claim that is not deferred can be split.';
  if (e instanceof HttpErrorResponse && e.status === 422) return "Split off an amount more than 0.00 and less than the claim's.";
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim is no longer on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 423) return 'This claim is due in a closed period.';
  return otherError(e);
}

/** Why a part-settle was refused. */
export function settlePartError(e: unknown, opener: boolean): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter an amount with up to two decimals.';
  if (e instanceof HttpErrorResponse && e.status === 403) {
    return opener
      ? 'The user who opened or last edited this claim cannot settle part of it, or your role may not split or settle it.'
      : 'Your role may not split or settle this claim.';
  }
  if (e instanceof HttpErrorResponse && e.status === 409) return 'Only an open claim that is not deferred can be part-settled.';
  if (e instanceof HttpErrorResponse && e.status === 422) {
    return "Settle part of an amount more than 0.00 and less than the claim's; a claim whose due is above the proof threshold needs a proof first.";
  }
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim is no longer on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 423) return 'This claim is due in a closed period.';
  return otherError(e);
}

/** An amount in whole cents (negative for a negative amount). */
export function cents(amount: string): number {
  const negative = amount.startsWith('-');
  const [whole, frac = ''] = (negative ? amount.slice(1) : amount).split('.');
  const abs = Number(whole) * 100 + Number((frac + '00').slice(0, 2));
  return negative ? -abs : abs;
}

/** Whole cents as an amount with two decimals, a minus sign before a negative one. */
export function money(cents: number): string {
  const abs = Math.abs(cents);
  return `${cents < 0 ? '-' : ''}${Math.floor(abs / 100)}.${String(abs % 100).padStart(2, '0')}`;
}

/** A claim's tags, each on its own; none when it has none. */
export function tagsOf(c: Claim): string[] {
  return c.tags ? c.tags.split(',') : [];
}
