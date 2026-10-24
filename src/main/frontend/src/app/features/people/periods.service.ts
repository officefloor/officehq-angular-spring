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
  /** The most OPEN or PENDING claims each person may have in the period; null for no cap. */
  cap: number | null;
  /** Whether the period is closed: no claim dated in it can change. */
  closed: boolean;
  /** Whether the closed period is only soft-closed: its figures are fixed, but claims dated in it can
   *  still change. */
  soft: boolean;
  /** The due-total fixed when the period was closed, with two decimals; null while it is open. */
  closedTotal: string | null;
  /** Each person's due-total for the period fixed when it was closed, with two decimals, by person
   *  id; empty while it is open. A person not in it (added later) has 0.00 for the closed period. */
  closedPersonTotals: Record<string, string>;
}

export interface PeriodFields {
  name: string;
  from: string;
  to: string;
  /** The cap on each person's OPEN or PENDING claims in the period: a whole number, blank for none. */
  cap: string;
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

  /** Make one period per month from the from-date's month to the to-date's month (a blank date is the
   *  sheet's today), leaving out a month whose name is taken or whose days overlap a period: those made. */
  generate(sheet: string, from: string | null, to: string | null): Promise<Period[]> {
    return firstValueFrom(
      this.http.post<Period[]>(`${this.url(sheet)}/generate`, { from, to }, { headers: this.session.headers() }),
    );
  }

  /** Make the twelve monthly periods of the fiscal year FYyyyy (the one whose first day falls in the
   *  year), as {@link generate} does: those made. */
  generateFiscalYear(sheet: string, year: string): Promise<Period[]> {
    return firstValueFrom(
      this.http.post<Period[]>(`${this.url(sheet)}/generate-fy`, { year }, { headers: this.session.headers() }),
    );
  }

  /** Close the period, fixing its due-total now. */
  close(sheet: string, id: number): Promise<Period> {
    return firstValueFrom(
      this.http.post<Period>(`${this.url(sheet)}/${id}/close`, null, { headers: this.session.headers() }),
    );
  }

  /** Soft-close the period, fixing its due-total now while claims dated in it can still change. */
  softClose(sheet: string, id: number): Promise<Period> {
    return firstValueFrom(
      this.http.post<Period>(`${this.url(sheet)}/${id}/soft-close`, null, { headers: this.session.headers() }),
    );
  }

  /** Reopen the closed period, making its due-total live again. */
  reopen(sheet: string, id: number): Promise<Period> {
    return firstValueFrom(
      this.http.post<Period>(`${this.url(sheet)}/${id}/reopen`, null, { headers: this.session.headers() }),
    );
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/periods`;
  }
}
