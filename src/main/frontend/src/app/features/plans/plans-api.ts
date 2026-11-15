import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session, otherError } from '../../core/session';
import { plusDays, type Claim } from '../claims/claims-api';

/** A person's plan: a DRAFT claim of its amount (two decimals) for each plan date, its start and
 *  then every so many days, or its day of the month in every month from the start's (the other
 *  null), up to its end; with how many claims it has made so far. */
export interface Plan {
  id: number;
  personId: number;
  amount: string;
  start: string;
  every: number | null;
  day: number | null;
  end: string;
  /** The descendant sheet it makes its claims on, for the person of the same name there; null: its own. */
  sheet: string | null;
  generated: number;
  /** The sheet's today the plan was paused on; null while it runs. */
  pausedOn: string | null;
  /** Its past pauses: from the day it was paused (inclusive) to the day it was resumed (exclusive). */
  pauses: { from: string; to: string }[];
  /** Its amount changes, oldest first: the sheet's today each was made on and the new amount. */
  changes: { on: string; amount: string }[];
}

/** A plan as the form submits it: a blank start takes the sheet's today; one of every and day. */
export interface PlanForm {
  amount: string;
  start: string | null;
  every: number | null;
  day: number | null;
  end: string;
  /** One of the sheet's descendants to make its claims on; null: the sheet itself. */
  sheet: string | null;
}

/** A generate's result: the claims made on the sheet, in the order they were made; how many were
 *  made in all, counting those made on its descendants; and the sheet's plans now. */
export interface Generated {
  claims: Claim[];
  made: number;
  plans: Plan[];
}

@Injectable({ providedIn: 'root' })
export class PlansApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The sheet's plans, in the order they were added. */
  list(sheetId: string): Promise<Plan[]> {
    return firstValueFrom(this.http.get<Plan[]>(`${this.url(sheetId)}/plans`, { headers: this.headers() }));
  }

  add(sheetId: string, personId: number, form: PlanForm): Promise<Plan> {
    return firstValueFrom(this.http.post<Plan>(`${this.url(sheetId)}/people/${personId}/plans`, form, { headers: this.headers() }));
  }

  /** Pause the plan (or, with `on` false, resume it) on the sheet's today. */
  setPaused(sheetId: string, planId: number, on: boolean): Promise<Plan> {
    return firstValueFrom(this.http.post<Plan>(`${this.url(sheetId)}/plans/${planId}/${on ? 'pause' : 'resume'}`, null, { headers: this.headers() }));
  }

  /** Change the plan's amount on the sheet's today; the claims it makes from then on take it. */
  changeAmount(sheetId: string, planId: number, amount: string): Promise<Plan> {
    return firstValueFrom(this.http.post<Plan>(`${this.url(sheetId)}/plans/${planId}/amount`, { amount }, { headers: this.headers() }));
  }

  /** Make the claims every plan on the sheet is due by the sheet's today. */
  generate(sheetId: string): Promise<Generated> {
    return firstValueFrom(this.http.post<Generated>(`${this.url(sheetId)}/plans/generate`, null, { headers: this.headers() }));
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}

export function planError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) {
    return 'Enter an amount of 0.00 or more with up to two decimals, a whole number of days of 1 or more or a day of the month of 1 to 31, an end date, and a sheet id without spaces.';
  }
  if (e instanceof HttpErrorResponse && e.status === 422) {
    return 'Give an end on or after the start, repeat either every 1 day or more or monthly on a day of 1 to 31, and name only a sheet below this one.';
  }
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That person is no longer on the sheet.';
  return otherError(e);
}

export function pauseError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 409) return 'Only a running plan can be paused, and only a paused one resumed.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That plan is no longer on the sheet.';
  return otherError(e);
}

export function amountError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter an amount of 0.00 or more with up to two decimals.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That plan is no longer on the sheet.';
  return otherError(e);
}

/** Whether the plan skips a date: it falls on or after the day a pause began and before the day
 *  it was resumed, or on or after the day of the pause still in force. */
export function skips(plan: Plan, date: string): boolean {
  if (plan.pausedOn !== null && date >= plan.pausedOn) return true;
  return plan.pauses.some((p) => date >= p.from && date < p.to);
}

/** The plan's first `count` dates after the date given and on or before its end, in order: its
 *  start and then every so many days; or its day of the month (on the month's last day when the
 *  month is shorter) in every month from the start's, leaving out those before the start and
 *  those it skips for a pause. */
export function nextDates(plan: Plan, after: string, count = 5): string[] {
  const out: string[] = [];
  if (plan.day !== null) {
    let year = Number(plan.start.slice(0, 4));
    let month = Number(plan.start.slice(5, 7));
    while (out.length < count) {
      const first = `${year}-${pad(month)}-01`;
      if (first > plan.end) break;
      const length = new Date(Date.UTC(year, month, 0)).getUTCDate();
      const d = `${year}-${pad(month)}-${pad(Math.min(plan.day, length))}`;
      if (d >= plan.start && d > after && d <= plan.end && !skips(plan, d)) out.push(d);
      month += 1;
      if (month > 12) {
        month = 1;
        year += 1;
      }
    }
    return out;
  }
  const every = plan.every ?? 1;
  let d = plan.start;
  if (d <= after) {
    const gap = Math.round((Date.parse(after) - Date.parse(d)) / 86_400_000);
    d = plusDays(d, (Math.floor(gap / every) + 1) * every);
  }
  for (; out.length < count && d <= plan.end; d = plusDays(d, every)) {
    if (!skips(plan, d)) out.push(d);
  }
  return out;
}

function pad(n: number): string {
  return String(n).padStart(2, '0');
}
