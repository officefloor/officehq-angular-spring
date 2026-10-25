import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { RateView, dueCents, rateInForce } from '../rates/rates-api';

export type ClaimStatus = 'DRAFT' | 'OPEN' | 'SETTLED' | 'VOID';
export type ClaimAction = 'open' | 'settle' | 'void';

/** Only open and settled claims count toward what is claimed and due. */
export function counts(claim: ClaimView): boolean {
  return claim.status === 'OPEN' || claim.status === 'SETTLED';
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
  /** The sheet's number for the claim, given when it is opened; null while a draft or once void. */
  number: number | null;
  /** The due fixed when the claim was settled, with two decimals; null while its due is live. */
  settledDue: string | null;
  /** Who opened the claim; null when it was never opened. */
  openedBy: string | null;
}

export interface ClaimForm {
  amount: string;
  /** null takes the sheet's today. */
  date: string | null;
  note: string;
}

/** An amount in whole cents, so sums of amounts are exact. */
export function cents(amount: string): number {
  return Math.round(Number(amount) * 100);
}

/** Cents as the screen shows an amount: two decimals. */
export function money(total: number): string {
  return (total / 100).toFixed(2);
}

/** What is due on the claim, in cents: the figure fixed when it was settled, else at the rate of
 *  `gradeRates` (its person's grade's rates) in force on the claim's date; null when there is none. */
export function claimDueCents(claim: ClaimView, gradeRates: readonly RateView[]): number | null {
  if (claim.settledDue !== null) return cents(claim.settledDue);
  const rate = rateInForce(gradeRates, claim.date);
  return rate === null ? null : dueCents(cents(claim.amount), rate.pct);
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

  add(sheetId: string, personId: number, form: ClaimForm): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheetId)}/people/${personId}/claims`, form));
  }

  update(sheetId: string, id: number, form: ClaimForm): Promise<ClaimView> {
    return firstValueFrom(this.http.put<ClaimView>(`${this.url(sheetId)}/claims/${id}`, form));
  }

  /** Move a claim to its next status: refused when its status does not allow it, and settling is
   *  refused (403) to the user who opened the claim. */
  act(sheetId: string, id: number, action: ClaimAction): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheetId)}/claims/${id}/${action}`, {}));
  }
}
