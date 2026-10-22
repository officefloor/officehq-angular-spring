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
  /** The sheet's number given when the claim was opened; null while DRAFT, and kept once VOID. */
  number: number | null;
  /**
   * The due fixed when the claim was settled, with two decimals; null when it is not fixed (not
   * settled, or the grade had no rate then), so its due follows the current rate.
   */
  settledDue: string | null;
  /** The user who opened the claim; null until it is opened. The opener cannot settle it. */
  openedBy: string | null;
  /** Whether the claim is bound by its person's last statement to the figure it had then. */
  stated: boolean;
  /** The figure the statement bound the claim to, with two decimals; null when unbound, or when it was n/a. */
  statedDue: string | null;
}

/** Whether a claim counts toward what is claimed and due: only OPEN and SETTLED claims do. */
export function counts(claim: ClaimView): boolean {
  return claim.status === 'OPEN' || claim.status === 'SETTLED';
}

/** A claim's due-by (yyyy-mm-dd): its date plus the sheet's terms in days. */
export function dueBy(claim: ClaimView, terms: number): string {
  const [y, m, d] = claim.date.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + terms)).toISOString().slice(0, 10);
}

/** Whether a claim is overdue: it is OPEN and its due-by is before the sheet's today. */
export function overdue(claim: ClaimView, terms: number, today: string): boolean {
  return claim.status === 'OPEN' && today !== '' && dueBy(claim, terms) < today;
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

  /**
   * Move a claim's status; refused (409) when its status does not allow the move,
   * or when the user settling it is the one who opened it.
   */
  act(sheet: string, id: number, action: ClaimAction): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheet)}/claims/${id}/${action}`, null));
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}`;
  }
}
