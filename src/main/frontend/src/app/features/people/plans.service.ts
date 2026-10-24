import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Claim } from './claims.service';

/** A person's plan: a claim of `amount` on every plan date (the start, then every `every` days; or,
 *  monthly, `day` of every month from the start's month, the month's last day when it is shorter, those
 *  before the start left out) up to the end, how many claims it has made so far, and its next dates (the
 *  first five plan dates after the sheet's today and on or before the end, comma and space separated). */
export interface Plan {
  id: number;
  personId: number;
  amount: string;
  start: string;
  every: number | null;
  day: number | null;
  end: string;
  generated: number;
  nextDates: string;
}

/** A plan asked: amount, start and end as typed, and either the interval in whole days or the day of
 *  the month (the other blank). */
export interface PlanFields {
  amount: string;
  start: string;
  every: string;
  day: string;
  end: string;
}

/** How a generate went: how many claims it made, the claims, and every plan as it stands after. */
export interface Generated {
  made: number;
  claims: Claim[];
  plans: Plan[];
}

// The plans on a sheet, read from and written to the server.
@Injectable({ providedIn: 'root' })
export class PlansService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  list(sheet: string): Promise<Plan[]> {
    return firstValueFrom(this.http.get<Plan[]>(this.url(sheet), { headers: this.session.headers() }));
  }

  /** Add a plan for the person. Refused (400, 422) when the amount, interval or day is bad,
   *  both or neither of interval and day are given, or the end is
   *  before the start. */
  add(sheet: string, personId: number, fields: PlanFields): Promise<Plan> {
    return firstValueFrom(
      this.http.post<Plan>(this.url(sheet), { personId, ...fields }, { headers: this.session.headers() }),
    );
  }

  /** Make the DRAFT claims every plan on the sheet is due up to its end and the sheet's today. */
  generate(sheet: string): Promise<Generated> {
    return firstValueFrom(
      this.http.post<Generated>(`${this.url(sheet)}/generate`, null, { headers: this.session.headers() }),
    );
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/plans`;
  }
}
