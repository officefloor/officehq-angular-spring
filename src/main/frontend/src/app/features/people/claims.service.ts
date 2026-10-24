import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export interface Claim {
  id: number;
  personId: number;
  /** The amount with two decimals, as text (e.g. "10.50"). */
  amount: string;
  /** YYYY-MM-DD */
  date: string;
  note: string;
  status: ClaimStatus;
  /** The claim's number on the sheet, given when it is opened; null while DRAFT and once VOID. */
  number: number | null;
  /** What was due on the claim when it was settled, with two decimals; null otherwise, and when its
   *  person's grade had no rate then. */
  settledDue: string | null;
  /** The user who opened the claim; null until it is opened. */
  openedBy: string | null;
  /** Whether the claim is bound to the figure it had at its person's last statement. */
  stated: boolean;
  /** The figure it is bound to, with two decimals; null while unbound, and when it had no figure
   *  (no rate) then. */
  statedDue: string | null;
  /** On a correction (a claim with a negative amount), the number of the SETTLED claim of the same
   *  person it corrects; null on any other claim. */
  corrects: number | null;
}

/** Added as DRAFT, then OPEN, then SETTLED; a DRAFT or OPEN claim can be VOID. */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'SETTLED' | 'VOID';

/** A status change asked of a claim. */
export type ClaimAction = 'open' | 'settle' | 'void';

/** Whether a claim in this status counts toward what is claimed and due. */
export function counts(claim: Claim): boolean {
  return claim.status === 'OPEN' || claim.status === 'SETTLED';
}

/** The date (YYYY-MM-DD) a claim is due by: its date plus the sheet's terms, in days. */
export function dueByOf(claim: Claim, terms: number): string {
  const [y, m, d] = claim.date.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + terms)).toISOString().slice(0, 10);
}

/** Whether a claim is overdue: OPEN, and due by a date before the sheet's today. */
export function overdue(claim: Claim, terms: number, today: string | null): boolean {
  return claim.status === 'OPEN' && today !== null && dueByOf(claim, terms) < today;
}

export interface ClaimFields {
  amount: string;
  /** null takes the sheet's today. */
  date: string | null;
  note: string;
  /** The number of the claim a correction corrects; null on a claim that is not a correction. */
  corrects: number | null;
}

/** Whether a claim is a correction: one with a negative amount. */
export function isCorrection(claim: Claim): boolean {
  return cents(claim.amount) < 0;
}

/** Amounts in whole cents, so sums of two-decimal amounts are exact. */
export function cents(amount: string): number {
  return Math.round(Number(amount) * 100);
}

export function money(cents: number): string {
  return (cents / 100).toFixed(2);
}

// The claims kept on a sheet, read from and written to the server.
@Injectable({ providedIn: 'root' })
export class ClaimsService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The sheet's claims, newest first. */
  list(sheet: string): Promise<Claim[]> {
    return firstValueFrom(
      this.http.get<Claim[]>(`${this.url(sheet)}/claims`, { headers: this.session.headers() }),
    );
  }

  add(sheet: string, personId: number, fields: ClaimFields): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheet)}/people/${personId}/claims`, fields, {
        headers: this.session.headers(),
      }),
    );
  }

  update(sheet: string, id: number, fields: ClaimFields): Promise<Claim> {
    return firstValueFrom(
      this.http.put<Claim>(`${this.url(sheet)}/claims/${id}`, fields, {
        headers: this.session.headers(),
      }),
    );
  }

  /** Change the claim's status; refused (409) when its status does not allow the change, and (403)
   *  when the user who opened it asks to settle it. */
  act(sheet: string, id: number, action: ClaimAction): Promise<Claim> {
    return firstValueFrom(
      this.http.post<Claim>(`${this.url(sheet)}/claims/${id}/${action}`, null, {
        headers: this.session.headers(),
      }),
    );
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}`;
  }
}
