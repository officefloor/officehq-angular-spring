import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

export interface PeriodView {
  id: number;
  /** Unique on its sheet. */
  name: string;
  /** The first day of the period (yyyy-mm-dd), inclusive. */
  from: string;
  /** The last day of the period (yyyy-mm-dd), inclusive. */
  to: string;
  /** Whether the period is closed: no claim dated inside it can be added, edited or moved. */
  closed: boolean;
  /** The due-total fixed when the period was closed, with exactly two decimals; null while open. */
  closedTotal: string | null;
}

/** A period as the form submits it: a blank from-date takes the sheet's today. */
export interface PeriodForm {
  name: string;
  from: string | null;
  to: string;
}

/** The period whose dates contain a date (yyyy-mm-dd); undefined when none does. */
export function periodOn(periods: readonly PeriodView[], date: string): PeriodView | undefined {
  return periods.find((p) => p.from <= date && date <= p.to);
}

// The periods of the active sheet, by from-date; no two of them overlap.
@Injectable({ providedIn: 'root' })
export class Periods {
  private readonly http = inject(HttpClient);
  private readonly periods = signal<PeriodView[]>([]);
  readonly list = this.periods.asReadonly();

  /** Re-read a sheet's periods from the store; null clears them. */
  load(sheetId: string | null): Observable<PeriodView[]> | null {
    this.periods.set([]);
    if (sheetId === null) return null;
    return this.http.get<PeriodView[]>(this.base(sheetId)).pipe(tap((periods) => this.periods.set(periods)));
  }

  /** Add a period; refused when its name is in use, it ends before it starts, or it overlaps another. */
  add(sheetId: string, period: PeriodForm): Observable<PeriodView> {
    return this.http
      .post<PeriodView>(this.base(sheetId), period)
      .pipe(tap((saved) => this.periods.update((list) => [...list, saved].sort((a, b) => (a.from < b.from ? -1 : 1)))));
  }

  /** Close a period, fixing its due-total; refused when it is already closed. */
  close(sheetId: string, name: string): Observable<PeriodView> {
    return this.http
      .post<PeriodView>(`${this.base(sheetId)}/${encodeURIComponent(name)}/close`, {})
      .pipe(tap((saved) => this.periods.update((list) => list.map((p) => (p.id === saved.id ? saved : p)))));
  }

  /** Reopen a closed period, making its due-total live again; only a steward may. */
  reopen(sheetId: string, name: string): Observable<PeriodView> {
    return this.http
      .post<PeriodView>(`${this.base(sheetId)}/${encodeURIComponent(name)}/reopen`, {})
      .pipe(tap((saved) => this.periods.update((list) => list.map((p) => (p.id === saved.id ? saved : p)))));
  }

  private base(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/periods`;
  }
}
