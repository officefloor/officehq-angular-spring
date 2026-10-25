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
  /** The due-total fixed when the period was closed, with two decimals; null while it is open. */
  closedTotal: string | null;
}

export interface PeriodForm {
  name: string;
  from: string;
  to: string;
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

  /** Close a period, fixing its due-total: refused when it is already closed. */
  close(sheetId: string, id: number): Promise<PeriodView> {
    return firstValueFrom(this.http.post<PeriodView>(`${this.url(sheetId)}/${id}/close`, {}));
  }
}
