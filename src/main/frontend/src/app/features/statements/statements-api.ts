import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ClaimView } from '../claims/claims-api';

/** A person's statements: how many were taken, and the last one's due-total and date. */
export interface StatementView {
  personId: number;
  count: number;
  /** The person's due-total when the last statement was taken, with two decimals, or n/a. */
  lastDueTotal: string;
  /** yyyy-mm-dd: the sheet's today when the last statement was taken. */
  lastDate: string;
}

/** A statement just taken: the person's statements after it, and their claims as it bound them. */
export interface TakenView {
  statement: StatementView;
  claims: ClaimView[];
}

/** A statement just cancelled: the person's statements after it (null when none is left), and their
 *  claims, live again. */
export interface CancelledView {
  statement: StatementView | null;
  claims: ClaimView[];
}

// The statements taken of the people on a sheet, as the store holds them.
@Injectable({ providedIn: 'root' })
export class StatementsApi {
  private readonly http = inject(HttpClient);

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }

  /** The statements of each person who has had one taken. */
  list(sheetId: string): Promise<StatementView[]> {
    return firstValueFrom(this.http.get<StatementView[]>(`${this.url(sheetId)}/statements`));
  }

  /** Take a person's statement, binding their counting claims to the figures they show now. */
  take(sheetId: string, personId: number): Promise<TakenView> {
    return firstValueFrom(this.http.post<TakenView>(`${this.url(sheetId)}/people/${personId}/statements`, {}));
  }

  /** Cancel a person's last statement, making the claims it bound live again. */
  cancel(sheetId: string, personId: number): Promise<CancelledView> {
    return firstValueFrom(this.http.delete<CancelledView>(`${this.url(sheetId)}/people/${personId}/statements/last`));
  }
}
