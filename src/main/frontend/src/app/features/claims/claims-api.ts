import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** An amount of 0.00 or more with up to two decimals. */
export const AMOUNT_PATTERN = /^\d{1,13}(\.\d{1,2})?$/;

export interface Claim {
  id: number;
  personId: number;
  /** Always with two decimals, as the server formats it. */
  amount: string;
  date: string;
  note: string;
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
