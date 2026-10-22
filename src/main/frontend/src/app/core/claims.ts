import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** DRAFT when added; DRAFT → OPEN → SETTLED, and DRAFT or OPEN → VOID. */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'SETTLED' | 'VOID';

/** A move of a claim's status: open, settle or void. */
export type ClaimAction = 'open' | 'settle' | 'void';

export interface ClaimView {
  id: number;
  personId: number;
  /** The amount with two decimals, e.g. "10.25". */
  amount: string;
  /** yyyy-mm-dd */
  date: string;
  note: string;
  status: ClaimStatus;
}

/** Whether a claim counts toward what is claimed and due: only OPEN and SETTLED claims do. */
export function counts(claim: ClaimView): boolean {
  return claim.status === 'OPEN' || claim.status === 'SETTLED';
}

export interface ClaimForm {
  amount: string;
  /** Empty means the sheet's today (or, on an edit, the claim's own date). */
  date: string | null;
  note: string;
}

/** An amount as a claim takes it: 0.00 or more, with up to two decimals. */
export const AMOUNT = /^\d{1,12}(\.\d{1,2})?$/;

/** A two-decimal amount in whole cents, so sums are exact. */
export function cents(amount: string): number {
  const [whole, frac = ''] = amount.split('.');
  return Number(whole) * 100 + Number(frac.padEnd(2, '0'));
}

/** Whole cents shown with two decimals. */
export function money(cents: number): string {
  return `${Math.trunc(cents / 100)}.${String(cents % 100).padStart(2, '0')}`;
}

// The claims people on a sheet make, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class ClaimsApi {
  private readonly http = inject(HttpClient);

  /** The sheet's claims, newest first. */
  list(sheet: string): Promise<ClaimView[]> {
    return firstValueFrom(this.http.get<ClaimView[]>(`${this.url(sheet)}/claims`));
  }

  add(sheet: string, personId: number, form: ClaimForm): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheet)}/people/${personId}/claims`, form));
  }

  edit(sheet: string, id: number, form: ClaimForm): Promise<ClaimView> {
    return firstValueFrom(this.http.put<ClaimView>(`${this.url(sheet)}/claims/${id}`, form));
  }

  /** Move a claim's status; refused (409) when its status does not allow the move. */
  act(sheet: string, id: number, action: ClaimAction): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheet)}/claims/${id}/${action}`, null));
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}`;
  }
}
