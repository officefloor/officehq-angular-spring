import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export interface PeriodView {
  id: number;
  /** Unique on the sheet, ignoring case. */
  name: string;
  /** yyyy-mm-dd, inclusive. */
  from: string;
  /** yyyy-mm-dd, inclusive. */
  to: string;
  /** Closed periods keep the due-total they had when closed; no claim dated in one can change. */
  closed: boolean;
  /** The due-total fixed when the period was closed, with two decimals; null while open. */
  closedTotal: string | null;
  /**
   * Each person's due-total for the period fixed when it was closed, by person id, with two decimals;
   * empty while open. A person added since has none: 0.00.
   */
  personTotals: Record<string, string>;
}

export interface PeriodForm {
  name: string;
  from: string;
  to: string;
}

/** A period's date as the form takes it: yyyy-mm-dd. */
export const PERIOD_DATE = /^\d{4}-\d{2}-\d{2}$/;

/** The period whose dates (inclusive) contain a date (yyyy-mm-dd), or null when none does. */
export function periodOn(periods: PeriodView[], date: string): PeriodView | null {
  return periods.find((p) => p.from <= date && date <= p.to) ?? null;
}

// The periods on a sheet, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class PeriodsApi {
  private readonly http = inject(HttpClient);

  /** The sheet's periods by from-date. */
  list(sheet: string): Promise<PeriodView[]> {
    return firstValueFrom(this.http.get<PeriodView[]>(this.url(sheet)));
  }

  /** Add a period; refused when its dates are reversed, its name is taken, or it overlaps another. */
  add(sheet: string, form: PeriodForm): Promise<PeriodView> {
    return firstValueFrom(this.http.post<PeriodView>(this.url(sheet), form));
  }

  /**
   * Generate one period per calendar month from the from-date's month to the to-date's month, named
   * yyyy-mm; months whose name is taken or whose days overlap a period are left out. The periods made.
   * Refused when the to-date is before the from-date.
   */
  generate(sheet: string, from: string, to: string): Promise<PeriodView[]> {
    return firstValueFrom(this.http.post<PeriodView[]>(`${this.url(sheet)}/generate`, { from, to }));
  }

  /** Close a period, fixing its due-total; refused when it is already closed. */
  close(sheet: string, id: number): Promise<PeriodView> {
    return firstValueFrom(this.http.post<PeriodView>(`${this.url(sheet)}/${id}/close`, {}));
  }

  /** Reopen a closed period, making its due-total live again; refused for anyone but a steward. */
  reopen(sheet: string, id: number): Promise<PeriodView> {
    return firstValueFrom(this.http.post<PeriodView>(`${this.url(sheet)}/${id}/reopen`, {}));
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/periods`;
  }
}
