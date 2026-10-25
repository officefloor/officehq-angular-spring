import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

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

  /** Move a claim to its next status: refused when its status does not allow it. */
  act(sheetId: string, id: number, action: ClaimAction): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheetId)}/claims/${id}/${action}`, {}));
  }
}
