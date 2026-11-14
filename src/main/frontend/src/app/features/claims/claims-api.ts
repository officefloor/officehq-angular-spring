import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** An amount of 0.00 or more with up to two decimals. */
export const AMOUNT_PATTERN = /^\d{1,13}(\.\d{1,2})?$/;

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
  /** The sheet's number for the claim, given when it is opened; null while DRAFT or once VOID. */
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
}

/** Whether a claim counts toward claimed and due totals: only OPEN and SETTLED ones do. */
export function counts(c: Claim): boolean {
  return c.status === 'OPEN' || c.status === 'SETTLED';
}

export interface ClaimForm {
  amount: string;
  /** null takes the sheet's today. */
  date: string | null;
  note: string;
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

export function claimError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter an amount of 0.00 or more with up to two decimals.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim or person is no longer on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 423) return 'The date is in a closed period.';
  return 'Something went wrong; try again.';
}

export function statusError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 409) return 'This claim cannot make that change from its status.';
  if (e instanceof HttpErrorResponse && e.status === 403) return 'The user who opened this claim cannot settle it.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That claim is no longer on this sheet.';
  if (e instanceof HttpErrorResponse && e.status === 423) return 'This claim is dated in a closed period.';
  return 'Something went wrong; try again.';
}

/** An amount in whole cents. */
export function cents(amount: string): number {
  const [whole, frac = ''] = amount.split('.');
  return Number(whole) * 100 + Number((frac + '00').slice(0, 2));
}

/** Whole cents as an amount with two decimals. */
export function money(cents: number): string {
  return `${Math.floor(cents / 100)}.${String(cents % 100).padStart(2, '0')}`;
}
