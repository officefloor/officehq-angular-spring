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
  /**
   * Soft-closed: the due-total and each person's figure are fixed as on a close, but claims dated in
   * the period can still be added and changed. A soft-closed period is not closed.
   */
  softClosed: boolean;
  /** The most OPEN or PENDING claims due by in the period a person may have; null for no cap. */
  cap: number | null;
}

/** Whether a period's figures are fixed: it is closed or soft-closed. */
export function fixed(period: PeriodView): boolean {
  return period.closed || period.softClosed;
}

export interface PeriodForm {
  name: string;
  from: string;
  to: string;
  /** A whole number of at least 1; null for no cap. */
  cap: number | null;
}

/** A period's claim cap as the form takes it: a whole number of at least 1. */
export const PERIOD_CAP = /^[1-9]\d{0,5}$/;

/** A period's date as the form takes it: yyyy-mm-dd. */
export const PERIOD_DATE = /^\d{4}-\d{2}-\d{2}$/;

/** The period whose dates (inclusive) contain a date (yyyy-mm-dd), or null when none does. */
export function periodOn(periods: PeriodView[], date: string): PeriodView | null {
  return periods.find((p) => p.from <= date && date <= p.to) ?? null;
}

/** A fiscal year as the generate form takes it: the calendar year its first day falls in, four digits. */
export const FISCAL_YEAR = /^\d{4}$/;

/**
 * The name of the fiscal year a date (yyyy-mm-dd) falls in, FY<yyyy> by the calendar year its first
 * day falls in, for a fiscal year starting on the first of a month (1 to 12).
 */
export function fiscalYearOf(date: string, startMonth: number): string {
  const year = Number(date.slice(0, 4));
  const month = Number(date.slice(5, 7));
  return `FY${month >= startMonth ? year : year - 1}`;
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

  /** Generate the fiscal year's twelve monthly periods as generate does; the periods made. */
  generateFiscalYear(sheet: string, year: string): Promise<PeriodView[]> {
    return firstValueFrom(this.http.post<PeriodView[]>(`${this.url(sheet)}/generate-fy`, { year }));
  }

  /** Close a period, fixing its due-total; refused when it is already closed. */
  close(sheet: string, id: number): Promise<PeriodView> {
    return firstValueFrom(this.http.post<PeriodView>(`${this.url(sheet)}/${id}/close`, {}));
  }

  /** Soft-close a period, fixing its due-total; refused when it is already closed or soft-closed. */
  softClose(sheet: string, id: number): Promise<PeriodView> {
    return firstValueFrom(this.http.post<PeriodView>(`${this.url(sheet)}/${id}/soft-close`, {}));
  }

  /** Reopen a closed or soft-closed period, making its due-total live again; refused for anyone but a steward. */
  reopen(sheet: string, id: number): Promise<PeriodView> {
    return firstValueFrom(this.http.post<PeriodView>(`${this.url(sheet)}/${id}/reopen`, {}));
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/periods`;
  }
}
