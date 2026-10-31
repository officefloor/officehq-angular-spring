import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** DRAFT when added, then OPEN, then SETTLED; a DRAFT or OPEN claim can be VOID. */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'SETTLED' | 'VOID';

export interface ClaimView {
  id: number;
  personId: number;
  /** The amount with exactly two decimals, e.g. "10.50". */
  amount: string;
  /** yyyy-mm-dd */
  date: string;
  note: string;
  status: ClaimStatus;
  /** The claim's number on its sheet, given when opened; null while a draft or once void. */
  number: number | null;
  /** The due fixed when the claim was settled, with exactly two decimals; null while it is not
   *  settled, or if its grade had no rate then (its due then follows the rate as an open one does). */
  settledDue: string | null;
}

/** Only OPEN and SETTLED claims count toward what is claimed and due. */
export function counts(c: ClaimView): boolean {
  return c.status === 'OPEN' || c.status === 'SETTLED';
}

/** A claim as the form submits it: a blank date takes the sheet's today. */
export interface ClaimForm {
  amount: string;
  date: string | null;
  note: string;
}

/** An amount the form accepts: 0.00 or more, with up to two decimals. */
export const AMOUNT_PATTERN = /^\d+(\.\d{1,2})?$/;

/** An amount ("12.3", "4") in whole cents, so sums are exact. */
export function cents(amount: string): number {
  const [whole, frac = ''] = amount.split('.');
  return Number(whole) * 100 + Number(frac.padEnd(2, '0').slice(0, 2));
}

/** Whole cents as an amount with two decimals. */
export function money(total: number): string {
  return `${Math.trunc(total / 100)}.${String(total % 100).padStart(2, '0')}`;
}

// The claims people on a sheet have made, listed newest first.
@Injectable({ providedIn: 'root' })
export class Claims {
  private readonly http = inject(HttpClient);

  list(sheetId: string): Observable<ClaimView[]> {
    return this.http.get<ClaimView[]>(`${this.base(sheetId)}/claims`);
  }

  add(sheetId: string, personId: number, claim: ClaimForm): Observable<ClaimView> {
    return this.http.post<ClaimView>(`${this.base(sheetId)}/people/${personId}/claims`, claim);
  }

  update(sheetId: string, id: number, claim: ClaimForm): Observable<ClaimView> {
    return this.http.put<ClaimView>(`${this.base(sheetId)}/claims/${id}`, claim);
  }

  changeStatus(sheetId: string, id: number, status: ClaimStatus): Observable<ClaimView> {
    return this.http.put<ClaimView>(`${this.base(sheetId)}/claims/${id}/status`, { status });
  }

  private base(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }
}
