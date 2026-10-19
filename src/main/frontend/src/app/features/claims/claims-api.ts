import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Session } from '../../core/session';

export interface Claim {
  id: number;
  personId: number;
  /** With two decimals, e.g. "10.00". */
  amount: string;
  /** yyyy-mm-dd */
  date: string;
  note: string;
  status: ClaimStatus;
}

/** A claim starts as DRAFT, is opened and then settled; a DRAFT or OPEN claim can be voided. */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'SETTLED' | 'VOID';

/** The status changes a person can ask for on a claim. */
export type ClaimAction = 'open' | 'settle' | 'void';

/** Only OPEN and SETTLED claims count toward what is claimed and due. */
export function counts(claim: Claim): boolean {
  return claim.status === 'OPEN' || claim.status === 'SETTLED';
}

export interface ClaimForm {
  amount: string;
  /** Null for the sheet's today. */
  date: string | null;
  note: string;
}

/** The sum of claim amounts with two decimals, added up in cents so no rounding creeps in. */
export function sumAmounts(amounts: string[]): string {
  const cents = amounts.reduce((sum, a) => {
    const [whole, frac = ''] = a.split('.');
    return sum + Number(whole) * 100 + Number(frac.padEnd(2, '0'));
  }, 0);
  return `${Math.floor(cents / 100)}.${String(cents % 100).padStart(2, '0')}`;
}

// The claims people make on the active sheet, read and changed through the server.
@Injectable({ providedIn: 'root' })
export class ClaimsApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  list(sheetId: string): Observable<Claim[]> {
    return this.http.get<Claim[]>(`${this.url(sheetId)}/claims`, { headers: this.headers() });
  }

  add(sheetId: string, personId: number, form: ClaimForm): Observable<Claim> {
    return this.http.post<Claim>(`${this.url(sheetId)}/people/${personId}/claims`, form, {
      headers: this.headers(),
    });
  }

  update(sheetId: string, id: number, form: ClaimForm): Observable<Claim> {
    return this.http.put<Claim>(`${this.url(sheetId)}/claims/${id}`, form, { headers: this.headers() });
  }

  changeStatus(sheetId: string, id: number, action: ClaimAction): Observable<Claim> {
    return this.http.post<Claim>(`${this.url(sheetId)}/claims/${id}/${action}`, null, { headers: this.headers() });
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}
