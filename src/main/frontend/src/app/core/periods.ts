import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

export interface Period {
  id: number;
  /** Unique on the sheet. */
  name: string;
  /** The first day (yyyy-mm-dd) of the period. */
  from: string;
  /** The last day (yyyy-mm-dd) of the period. */
  to: string;
  /** Whether the period is closed: no claim dated inside it can be changed. */
  closed: boolean;
  /** Whether the period is soft-closed: its due-totals are fixed as for a closed one, but claims
   *  dated inside it can still be changed. */
  softClosed: boolean;
  /** The due-total fixed when it was closed or soft-closed, as text with two decimals; null while it is open. */
  closedTotal: string | null;
  /** Each person's due-total for the period fixed when it was closed, by the person's id, as text
   *  with two decimals; empty while it is open. A person missing from it has 0.00. */
  closedPersonTotals: Record<number, string>;
  /** The fiscal year its from-date falls in (FYyyyy). */
  fy: string;
  /** The most open or pending claims a person may have in the period; null for no cap. */
  cap: number | null;
}

export interface PeriodForm {
  name: string;
  from: string;
  to: string;
  /** The most open or pending claims a person may have in the period (1 or more); null for no cap. */
  cap: number | null;
}

/** The period whose days contain the day, or undefined when there is none. */
export function periodOf(periods: Period[], day: string): Period | undefined {
  return periods.find((p) => p.from <= day && day <= p.to);
}

// The periods on the active sheet.
@Injectable({ providedIn: 'root' })
export class Periods {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The periods on the sheet, earliest first. */
  list(sheetId: string): Promise<Period[]> {
    return firstValueFrom(this.http.get<Period[]>(this.url(sheetId)));
  }

  /** Add a period; refused when it ends before it starts, its name is in use or it overlaps another. */
  add(form: PeriodForm): Promise<Period> {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    return firstValueFrom(this.http.post<Period>(this.url(sheetId), form, { headers: { 'X-User': user } }));
  }

  /** Generate one period per calendar month from the from-date's month to the to-date's month,
   *  leaving out a month whose name is in use or that overlaps a period; the from-date is the
   *  sheet's today when blank. Refused when the to-date is before the from-date. */
  generate(from: string, to: string): Promise<Period[]> {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    return firstValueFrom(
      this.http.post<Period[]>(`${this.url(sheetId)}/generate`, { from: from || null, to },
        { headers: { 'X-User': user } }),
    );
  }

  /** Generate the twelve monthly periods of the fiscal year named by the year (yyyy), as
   *  {@link generate} does. */
  generateFy(year: string): Promise<Period[]> {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    return firstValueFrom(
      this.http.post<Period[]>(`${this.url(sheetId)}/generate-fy`, { year }, { headers: { 'X-User': user } }),
    );
  }

  /** Close a period, fixing its due-total; refused when it is already closed. */
  close(id: number): Promise<Period> {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    return firstValueFrom(
      this.http.post<Period>(`${this.url(sheetId)}/${id}/close`, null, { headers: { 'X-User': user } }),
    );
  }

  /** Soft-close a period, fixing its due-totals while its claims stay open to change; refused when
   *  it is already closed or soft-closed. */
  softClose(id: number): Promise<Period> {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    return firstValueFrom(
      this.http.post<Period>(`${this.url(sheetId)}/${id}/soft-close`, null, { headers: { 'X-User': user } }),
    );
  }

  /** Reopen a closed or soft-closed period, making its due-total live again; refused when the user is not a
   *  steward or the period is not closed. */
  reopen(id: number): Promise<Period> {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    return firstValueFrom(
      this.http.post<Period>(`${this.url(sheetId)}/${id}/reopen`, null, { headers: { 'X-User': user } }),
    );
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/periods`;
  }
}
