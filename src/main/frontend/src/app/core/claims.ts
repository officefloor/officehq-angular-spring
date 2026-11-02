import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/** Where a claim is in its life: DRAFT, then OPEN, then SETTLED; a DRAFT or OPEN claim may be VOID. */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'SETTLED' | 'VOID';

/** A change of status a claim can be asked for. */
export type ClaimAction = 'open' | 'settle' | 'void';

/** Whether a claim at this status counts toward claimed, due-total and every total. */
export function counts(c: Claim): boolean {
  return c.status === 'OPEN' || c.status === 'SETTLED';
}

export interface Claim {
  id: number;
  personId: number;
  /** The amount as text with two decimals. */
  amount: string;
  date: string;
  note: string;
  status: ClaimStatus;
  /** The claim's number on the sheet, given when it is opened; null for a draft or a void claim. */
  number: number | null;
}

export interface ClaimForm {
  amount: string;
  /** yyyy-mm-dd, or null for the sheet's today. */
  date: string | null;
  note: string;
}

/** An amount with up to two decimals, 0.00 or more. */
export const AMOUNT = /^\d{1,12}(\.\d{1,2})?$/;

/** The sum of amounts (two-decimal text), added in cents so no rounding creeps in. */
export function sumAmounts(amounts: string[]): string {
  const cents = amounts.reduce((sum, a) => sum + Math.round(Number(a) * 100), 0);
  return (cents / 100).toFixed(2);
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

  edit(id: number, form: ClaimForm): Promise<Claim> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.put<Claim>(`${this.url(sheetId)}/claims/${id}`, form, { headers: { 'X-User': user } }),
    );
  }

  /** Ask for a change of the claim's status; refused (409) when its status does not allow it. */
  act(id: number, action: ClaimAction): Promise<Claim> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/${action}`, null, { headers: { 'X-User': user } }),
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
