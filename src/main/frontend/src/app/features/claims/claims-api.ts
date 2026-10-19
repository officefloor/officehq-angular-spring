import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Session } from '../../core/session';
import { Grade } from '../people/people-api';
import { NO_RATE, Rate, dueOf, rateOn } from '../rates/rates-api';

export interface Claim {
  id: number;
  personId: number;
  /** With two decimals, e.g. "10.00". */
  amount: string;
  /** yyyy-mm-dd */
  date: string;
  note: string;
  status: ClaimStatus;
  /** The sheet's number for the claim, given when it is opened; null while a draft or once voided. */
  number: number | null;
  /** What was due when the claim was settled, with two decimals; null while it is live (not settled, or
   * settled when its grade had no rate). */
  due: string | null;
  /** Who opened the claim; null while it is a draft. */
  openedBy: string | null;
  /** Whether the claim is bound to the figure it had at its person's last statement. */
  stated: boolean;
  /** That bound figure, with two decimals; null when it was n/a or the claim is not bound. */
  statedDue: string | null;
}

/** A claim starts as DRAFT, is opened and then settled; a DRAFT or OPEN claim can be voided. */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'SETTLED' | 'VOID';

/** The status changes a person can ask for on a claim. */
export type ClaimAction = 'open' | 'settle' | 'void';

/** Only OPEN and SETTLED claims count toward what is claimed and due. */
export function counts(claim: Claim): boolean {
  return claim.status === 'OPEN' || claim.status === 'SETTLED';
}

/** A settled claim's due is the figure it was settled at; a claim bound by its person's last statement shows
 * the figure it was bound to; any other is due at the rate its grade has now in force on the claim's date (n/a
 * when there is none). */
export function claimDue(claim: Claim, rates: readonly Rate[], grade: Grade): string {
  if (claim.due !== null) return claim.due;
  if (claim.stated) return claim.statedDue ?? NO_RATE;
  return dueOf(claim.amount, rateOn(rates, grade, claim.date));
}

/** Whether a claim keeps a figure whatever the rates: settled at one, or bound to one by a statement. */
export function keepsFigure(claim: Claim): boolean {
  return claim.due !== null || claim.stated;
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
