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
 *  claim may be VOID. */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'PENDING' | 'SETTLED' | 'VOID';

/** A move of a claim's status, as the server names it. */
export type ClaimAction = 'open' | 'settle' | 'void' | 'approve' | 'resettle';

export interface Claim {
  id: number;
  personId: number;
  /** Always with two decimals, as the server formats it. */
  amount: string;
  date: string;
  note: string;
  status: ClaimStatus;
  /** The sheet's number for the claim, given when it is opened and kept if it is voided; null while DRAFT.
   *  A sheet-wide number, or under period numbering one within its period (P1-1, X-1 in no period). */
  number: string | null;
  /** The due fixed when the claim was settled, with two decimals; null, so the due is live, for
   *  a claim not SETTLED or settled when its grade had no rate. */
  settledDue: string | null;
  /** The user who opened the claim, who cannot settle it; null while DRAFT. */
  openedBy: string | null;
  /** Whether the person's last statement bound the claim: it then shows and totals statedDue,
   *  except while PENDING, when it shows and totals its live figure. */
  stated: boolean;
  /** The due the claim was bound to, with two decimals; null when unbound or n/a then. */
  statedDue: string | null;
  /** The number of the SETTLED claim this correction (a negative amount) corrects; null for others. */
  corrects: string | null;
  /** Where the claim is listed, newest (highest) first: set when it is added, and again when it
   *  is reassigned, so a moved claim is its new person's newest. */
  listed: number;
  /** yyyy-mm-dd: fixed when the claim was created, its date plus the terms of its person's grade
   *  in force on that date; later changes to the terms or its date leave it. */
  dueBy: string;
  /** The claim's status log, oldest first: one line per status it has had. */
  log: ClaimLogLine[];
  /** yyyy-mm-dd: the date the claim was deferred until; null when it was never deferred. */
  deferredUntil: string | null;
  /** How many proofs the claim has. */
  proofs: number;
  /** The number of the claim this one was split from; null when not made by a split. */
  splitFrom: string | null;
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

/** Whether a claim's due is bound to statedDue: stated and not PENDING. */
export function isBound(c: Claim): boolean {
  return c.stated && c.status !== 'PENDING';
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

/** Whether a claim is overdue: open (OPEN or PENDING), not deferred, with its due-by before the sheet's today. */
export function overdue(c: Claim, today: string | null): boolean {
  return isOpen(c) && today !== null && dueBy(c) < today && !deferred(c, today);
}

/** Whether a claim's live figures round half up: its date (a correction's, the date of the claim
 *  of its person it corrects, among `theirs`) is before the sheet's policy date (null: blank, the
 *  sheet's today); else they round half to even. */
export function roundsHalfUp(c: Claim, theirs: readonly Claim[], policyDate: string | null, today: string | null): boolean {
  const policy = policyDate ?? today;
  if (policy === null) return false;
  const on = c.corrects === null ? c.date : theirs.find((o) => o.number === c.corrects)?.date ?? c.date;
  return on < policy;
}

export interface ClaimForm {
  amount: string;
  /** null takes the sheet's today. */
  date: string | null;
  note: string;
  /** The number of the claim a correction corrects; null when none is named. */
  corrects: string | null;
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

  add(sheetId: string, personId: number, form: ClaimForm): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/people/${personId}/claims`, form, { headers: this.headers() }),
    );
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

  changeStatus(sheetId: string, id: number, action: ClaimAction): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/${action}`, null, { headers: this.headers() }),
    );
  }

  /** Defer an OPEN claim until a date after the sheet's today. */
  defer(sheetId: string, id: number, until: string): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/defer`, { until }, { headers: this.headers() }),
    );
  }

  /** Add a proof to a claim that is neither SETTLED nor VOID. */
  addProof(sheetId: string, id: number): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/proofs`, null, { headers: this.headers() }),
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

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}

/** Why a claim form was refused; `correction` says whether its amount was negative. */
export function claimError(e: unknown, correction = false): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter an amount with up to two decimals.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim or person is no longer on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'Only a draft claim can be edited; void it and add a new one.';
  if (e instanceof HttpErrorResponse && e.status === 423) return 'The date is in a closed period.';
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
  if (e instanceof HttpErrorResponse && e.status === 423) return "The sheet's today is in a closed period.";
  if (e instanceof HttpErrorResponse && e.status === 422) return "The sheet's today is after that person's retirement date.";
  return otherError(e);
}

/** Why a paste of claims was refused: nothing was added. */
export function pasteError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) {
    return 'Paste lines of name,amount,date,note: an amount with up to two decimals and a date as yyyy-mm-dd or blank.';
  }
  if (e instanceof HttpErrorResponse && e.status === 422) {
    return "Every name must be on the sheet, and every date by that person's retirement date; an amount below zero is a correction, which the paste does not take.";
  }
  if (e instanceof HttpErrorResponse && e.status === 423) return 'A date is in a closed period.';
  return otherError(e);
}

/** Why a status change was refused; `opener` says whether the user is the one who opened the claim. */
export function statusError(e: unknown, opener: boolean): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a reason for rejecting the claim.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'This claim cannot make that change from its status, or is deferred.';
  if (e instanceof HttpErrorResponse && e.status === 403) {
    return opener
      ? 'The user who opened this claim cannot settle or approve it, or your role may not make that change.'
      : 'Your role may not make that change to this claim.';
  }
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim is no longer on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 423) return 'This claim is dated in a closed period.';
  if (e instanceof HttpErrorResponse && e.status === 422) return 'This claim\'s due is above the proof threshold; add a proof first.';
  return otherError(e);
}

/** Why adding a proof was refused. */
export function proofError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 409) return 'A settled or void claim takes no more proofs.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim is no longer on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 423) return 'This claim is dated in a closed period.';
  return otherError(e);
}

/** Why a deferral was refused. */
export function deferError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a date as yyyy-mm-dd.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'Only an open claim can be deferred.';
  if (e instanceof HttpErrorResponse && e.status === 422) return "Defer the claim until a date after the sheet's today.";
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim is no longer on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 423) return 'This claim is dated in a closed period.';
  return otherError(e);
}

/** Why a re-settle was refused. */
export function resettleError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 403) return 'Only a steward can re-settle a claim.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'Only a settled claim can be re-settled.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim is no longer on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 423) return 'This claim is dated in a closed period.';
  return otherError(e);
}

/** Why a reassignment was refused. */
export function reassignError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 403) return 'Your role may not reassign a claim.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'Only a draft, open or pending claim can move to another person.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim or person is no longer on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 423) return 'This claim is dated in a closed period.';
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
  if (e instanceof HttpErrorResponse && e.status === 423) return 'This claim is dated in a closed period.';
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
