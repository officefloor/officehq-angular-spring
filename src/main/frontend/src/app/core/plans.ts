import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/** A person's plan: a draft claim of its amount on its start and every so many days after, or
 *  monthly on a day of the month (the other null), up to its end; with how many claims it has made
 *  so far, its next dates (the first five after the sheet's today, on or before its end, leaving out
 *  those a pause skips) and whether it is paused. */
export interface Plan {
  id: number;
  personId: number;
  amount: string;
  start: string;
  every: number | null;
  day: number | null;
  end: string;
  generated: number;
  nextDates: string[];
  paused: boolean;
  /** The changes of its amount, oldest first. */
  changes: PlanChange[];
  /** The descendant sheet its claims are made on; null for its own sheet. */
  sheet: string | null;
}

/** A change of a plan's amount: the sheet's today it was changed on and the new amount. */
export interface PlanChange {
  on: string;
  amount: string;
}

/** The plan form as typed: a blank start takes the sheet's today; a blank sheet is its own. */
export interface PlanFields {
  amount: string;
  start: string;
  every: string;
  day: string;
  end: string;
  sheet: string;
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

  /** Add a plan for the person; refused for a bad amount, interval or day, an end before the start,
   *  or a sheet that is not a descendant. */
  add(personId: number, f: PlanFields): Promise<Plan> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Plan>(`/api/sheets/${encodeURIComponent(sheetId)}/people/${personId}/plans`,
        { amount: f.amount, start: f.start || null, every: f.every || null, day: f.day || null, end: f.end || null,
          sheet: f.sheet || null },
        { headers: { 'X-User': user } }),
    );
  }

  /** Pause the plan from the sheet's today (true) or resume it (false); refused for pausing a paused
   *  plan or resuming a running one. */
  setPaused(planId: number, paused: boolean): Promise<Plan> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Plan>(`/api/sheets/${encodeURIComponent(sheetId)}/plans/${planId}/${paused ? 'pause' : 'resume'}`,
        null, { headers: { 'X-User': user } }),
    );
  }

  /** Change the plan's amount for the claims it makes from now on; refused for a bad amount. */
  changeAmount(planId: number, amount: string): Promise<Plan> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Plan>(`/api/sheets/${encodeURIComponent(sheetId)}/plans/${planId}/amount`,
        { amount }, { headers: { 'X-User': user } }),
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
