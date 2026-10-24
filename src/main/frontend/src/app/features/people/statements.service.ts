import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Claim } from './claims.service';

export interface Statement {
  id: number;
  personId: number;
  /** The sheet's today when it was taken (YYYY-MM-DD). */
  date: string;
  /** The person's due-total then, with two decimals, as text. */
  dueTotal: string;
  /** How many of the person's claims counted toward it. */
  claims: number;
}

/** A statement taken, with the person's claims as it left them, newest first. */
export interface Taken {
  statement: Statement;
  claims: Claim[];
}

// The statements taken of the people on a sheet, read from and written to the server.
@Injectable({ providedIn: 'root' })
export class StatementsService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The sheet's statements in the order they were taken. */
  list(sheet: string): Promise<Statement[]> {
    return firstValueFrom(
      this.http.get<Statement[]>(`${this.url(sheet)}/statements`, { headers: this.session.headers() }),
    );
  }

  take(sheet: string, personId: number): Promise<Taken> {
    return firstValueFrom(
      this.http.post<Taken>(`${this.url(sheet)}/people/${personId}/statements`, null, {
        headers: this.session.headers(),
      }),
    );
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}`;
  }
}
