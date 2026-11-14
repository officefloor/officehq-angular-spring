import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import type { Person } from '../people/people-api';

/** An amount of 0.00 or more with up to two decimals. */
export const AMOUNT_PATTERN = /^\d{1,13}(\.\d{1,2})?$/;

/** A claim form's amount: up to two decimals, negative for a correction. */
export const CLAIM_AMOUNT_PATTERN = /^-?\d{1,13}(\.\d{1,2})?$/;

/** DRAFT when added, then OPEN, then SETTLED; a DRAFT or OPEN claim may be VOID. */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'SETTLED' | 'VOID';

/** A move of a claim's status, as the server names it. */
export type ClaimAction = 'open' | 'settle' | 'void';

export interface Claim {
  id: number;
  personId: number;
  /** Always with two decimals, as the server formats it. */
  amount: string;
  date: string;
  note: string;
  status: ClaimStatus;
  /** The sheet's number for the claim, given when it is opened and kept if it is voided; null while DRAFT. */
  number: number | null;
  /** The due fixed when the claim was settled, with two decimals; null, so the due is live, for
   *  a claim not SETTLED or settled when its grade had no rate. */
  settledDue: string | null;
  /** The user who opened the claim, who cannot settle it; null while DRAFT. */
  openedBy: string | null;
  /** Whether the person's last statement bound the claim: it then shows and totals statedDue. */
  stated: boolean;
  /** The due the claim was bound to, with two decimals; null when unbound or n/a then. */
  statedDue: string | null;
  /** The number of the SETTLED claim this correction (a negative amount) corrects; null for others. */
  corrects: number | null;
}

/** Whether a claim is a correction: its amount is negative. */
export function isCorrection(c: Claim): boolean {
  return c.amount.startsWith('-');
}

/** Whether a claim counts toward claimed and due totals: only OPEN and SETTLED ones do. */
export function counts(c: Claim): boolean {
  return c.status === 'OPEN' || c.status === 'SETTLED';
}

/** A claim's due-by (yyyy-mm-dd): its date plus the sheet's terms in days. */
export function dueBy(c: Claim, terms: number): string {
  const [y, m, d] = c.date.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + terms)).toISOString().slice(0, 10);
}

/** Whether a claim is overdue: OPEN with its due-by before the sheet's today. */
export function overdue(c: Claim, terms: number, today: string | null): boolean {
  return c.status === 'OPEN' && today !== null && dueBy(c, terms) < today;
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
  corrects: number | null;
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

  update(sheetId: string, id: number, form: ClaimForm): Promise<Claim> {
    return firstValueFrom(this.http.put<Claim>(`${this.url(sheetId)}/claims/${id}`, form, { headers: this.headers() }));
  }

  changeStatus(sheetId: string, id: number, action: ClaimAction): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/${action}`, null, { headers: this.headers() }),
    );
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
  return 'Something went wrong; try again.';
}

export function quickAddError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a name and an amount of 0.00 or more with up to two decimals.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'That name was just added by someone else; try again.';
  if (e instanceof HttpErrorResponse && e.status === 423) return "The sheet's today is in a closed period.";
  if (e instanceof HttpErrorResponse && e.status === 422) return "The sheet's today is after that person's retirement date.";
  return 'Something went wrong; try again.';
}

/** Why a status change was refused; `opener` says whether the user is the one who opened the claim. */
export function statusError(e: unknown, opener: boolean): string {
  if (e instanceof HttpErrorResponse && e.status === 409) return 'This claim cannot make that change from its status.';
  if (e instanceof HttpErrorResponse && e.status === 403) {
    return opener ? 'The user who opened this claim cannot settle it.' : "This claim's due is above the clerk limit; a steward must settle it.";
  }
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim is no longer on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 423) return 'This claim is dated in a closed period.';
  return 'Something went wrong; try again.';
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
