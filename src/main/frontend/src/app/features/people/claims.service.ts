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
}

export interface ClaimFields {
  amount: string;
  /** null takes the sheet's today. */
  date: string | null;
  note: string;
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

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}`;
  }
}
