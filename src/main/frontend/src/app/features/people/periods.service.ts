import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export interface Period {
  id: number;
  name: string;
  /** The first day of the period (YYYY-MM-DD). */
  from: string;
  /** The last day of the period (YYYY-MM-DD). */
  to: string;
}

export interface PeriodFields {
  name: string;
  from: string;
  to: string;
}

/** The period whose dates, both inclusive, contain the date; null when none does. */
export function periodOn(periods: Period[], date: string): Period | null {
  return periods.find((p) => p.from <= date && date <= p.to) ?? null;
}

// The periods of a sheet, read from and written to the server.
@Injectable({ providedIn: 'root' })
export class PeriodsService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The sheet's periods, by from-date. */
  list(sheet: string): Promise<Period[]> {
    return firstValueFrom(this.http.get<Period[]>(this.url(sheet), { headers: this.session.headers() }));
  }

  add(sheet: string, fields: PeriodFields): Promise<Period> {
    return firstValueFrom(this.http.post<Period>(this.url(sheet), fields, { headers: this.session.headers() }));
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/periods`;
  }
}
