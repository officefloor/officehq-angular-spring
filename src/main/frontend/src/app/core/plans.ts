import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/** A person's plan: a draft claim of its amount on its start and every so many days after, up to
 *  its end; with how many claims it has made so far. */
export interface Plan {
  id: number;
  personId: number;
  amount: string;
  start: string;
  every: number;
  end: string;
  generated: number;
}

/** The plan form as typed: a blank start takes the sheet's today. */
export interface PlanFields {
  amount: string;
  start: string;
  every: string;
  end: string;
}

// The plans of the people on the active sheet, and the generate that makes their claims.
@Injectable({ providedIn: 'root' })
export class Plans {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The plans on the sheet in the order they were added. */
  list(sheetId: string): Promise<Plan[]> {
    return firstValueFrom(this.http.get<Plan[]>(`/api/sheets/${encodeURIComponent(sheetId)}/plans`));
  }

  /** Add a plan for the person; refused for a bad amount or interval, or an end before the start. */
  add(personId: number, f: PlanFields): Promise<Plan> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Plan>(`/api/sheets/${encodeURIComponent(sheetId)}/people/${personId}/plans`,
        { amount: f.amount, start: f.start || null, every: f.every, end: f.end || null },
        { headers: { 'X-User': user } }),
    );
  }

  /** Make the claims every plan on the sheet is due: how many were made. */
  generate(): Promise<{ made: number }> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<{ made: number }>(`/api/sheets/${encodeURIComponent(sheetId)}/plans/generate`, null,
        { headers: { 'X-User': user } }),
    );
  }

  private active(): { sheetId: string; user: string } {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    return { sheetId, user };
  }
}
