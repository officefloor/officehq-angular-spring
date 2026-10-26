import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export interface PeriodView {
  id: number;
  name: string;
  /** The first day of the period (yyyy-mm-dd), inclusive. */
  from: string;
  /** The last day of the period (yyyy-mm-dd), inclusive. */
  to: string;
  /** Whether the period is closed: no claim dated in it can then change. */
  closed: boolean;
  /** Whether the period is soft-closed: its figures are fixed, but claims dated in it can still change. */
  softClosed: boolean;
  /** The due-total fixed when the period was closed or soft-closed, with two decimals; null while it is open. */
  closedTotal: string | null;
  /** Each person's due-total fixed when the period was closed, with two decimals, by person id; empty
   *  while it is open. A person missing from it (added after the close) has 0.00. */
  closedPersonTotals: Record<string, string>;
  /** How many OPEN or PENDING claims each person may have in the period; null for no cap. */
  cap: number | null;
  /** Each person's carried figure fixed when the period was closed or soft-closed, with two decimals, by
   *  person id; empty while it is open. A person missing from it (added after the close) has 0.00. */
  closedPersonCarried: Record<string, string>;
  /** Each person's opening balance for the period, with two decimals, by person id: part of their carried
   *  figure for it. A person missing from it has none. */
  personOpenings: Record<string, string>;
  /** Whether a steward has locked the period: a locked period cannot be reopened. */
  locked: boolean;
}

/** What pasting opening balances did: how many lines went through, and the sheet's periods now. */
export interface OpeningsView {
  added: number;
  periods: PeriodView[];
}

export interface PeriodForm {
  name: string;
  from: string;
  to: string;
  /** The cap on each person's open claims in the period; null for none. */
  cap: number | null;
}

/** The period whose dates contain the date, or null when none does; periods never overlap. */
export function periodOf(periods: readonly PeriodView[], date: string): PeriodView | null {
  return periods.find((p) => p.from <= date && date <= p.to) ?? null;
}

// The periods of a sheet, as the store holds them.
@Injectable({ providedIn: 'root' })
export class PeriodsApi {
  private readonly http = inject(HttpClient);

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/periods`;
  }

  /** The sheet's periods in date order. */
  list(sheetId: string): Promise<PeriodView[]> {
    return firstValueFrom(this.http.get<PeriodView[]>(this.url(sheetId)));
  }

  /** Add a period: refused when its name is in use, its to-date is before its from-date, or it
   *  overlaps another period. */
  add(sheetId: string, form: PeriodForm): Promise<PeriodView> {
    return firstValueFrom(this.http.post<PeriodView>(this.url(sheetId), form));
  }

  /** Generate one period per calendar month between two dates (a blank one is the sheet's today),
   *  leaving out months whose name is in use or that overlap a period: the periods made. Refused
   *  when the to-date is before the from-date. */
  generate(sheetId: string, from: string | null, to: string | null): Promise<PeriodView[]> {
    return firstValueFrom(this.http.post<PeriodView[]>(`${this.url(sheetId)}/generate`, { from, to }));
  }

  /** Generate the twelve monthly periods of the fiscal year starting in a calendar year, as the monthly
   *  generator does: the periods made. */
  generateFiscalYear(sheetId: string, year: number): Promise<PeriodView[]> {
    return firstValueFrom(this.http.post<PeriodView[]>(`${this.url(sheetId)}/generate-fy`, { year }));
  }

  /** Add each pasted line of name,period,amount to the person's opening balance for the period, all or
   *  none: refused when any line names no person or period on the sheet, or its amount is not 0.00 or more
   *  with two decimals. */
  pasteOpenings(sheetId: string, text: string): Promise<OpeningsView> {
    return firstValueFrom(this.http.post<OpeningsView>(`${this.url(sheetId)}/openings`, { text }));
  }

  /** Close a period, fixing its due-total: refused when it is already closed. */
  close(sheetId: string, id: number): Promise<PeriodView> {
    return firstValueFrom(this.http.post<PeriodView>(`${this.url(sheetId)}/${id}/close`, {}));
  }

  /** Soft-close a period, fixing its figures while its claims can still change: refused when it is
   *  closed or soft-closed already. */
  softClose(sheetId: string, id: number): Promise<PeriodView> {
    return firstValueFrom(this.http.post<PeriodView>(`${this.url(sheetId)}/${id}/soft-close`, {}));
  }

  /** Reopen a closed or soft-closed period, making its due-total live again: refused unless the user
   *  is a steward and the period is closed or soft-closed. */
  reopen(sheetId: string, id: number): Promise<PeriodView> {
    return firstValueFrom(this.http.post<PeriodView>(`${this.url(sheetId)}/${id}/reopen`, {}));
  }

  /** Lock a period so it cannot be reopened: refused unless the user is a steward and the period is not locked. */
  lock(sheetId: string, id: number): Promise<PeriodView> {
    return firstValueFrom(this.http.post<PeriodView>(`${this.url(sheetId)}/${id}/lock`, {}));
  }

  /** Unlock a locked period: refused unless the user is a steward and the period is locked. */
  unlock(sheetId: string, id: number): Promise<PeriodView> {
    return firstValueFrom(this.http.post<PeriodView>(`${this.url(sheetId)}/${id}/unlock`, {}));
  }
}
