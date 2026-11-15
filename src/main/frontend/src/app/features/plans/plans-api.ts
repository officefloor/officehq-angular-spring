import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session, otherError } from '../../core/session';
import type { Claim } from '../claims/claims-api';

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
  generated: number;
}

/** A plan as the form submits it: a blank start takes the sheet's today; one of every and day. */
export interface PlanForm {
  amount: string;
  start: string | null;
  every: number | null;
  day: number | null;
  end: string;
}

/** A generate's result: the claims made, in the order they were made, and the sheet's plans now. */
export interface Generated {
  claims: Claim[];
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
    return 'Enter an amount of 0.00 or more with up to two decimals, a whole number of days of 1 or more or a day of the month of 1 to 31, and an end date.';
  }
  if (e instanceof HttpErrorResponse && e.status === 422) {
    return 'Give an end on or after the start, and repeat either every 1 day or more or monthly on a day of 1 to 31.';
  }
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That person is no longer on the sheet.';
  return otherError(e);
}
