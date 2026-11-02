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
}

export interface PeriodForm {
  name: string;
  from: string;
  to: string;
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

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/periods`;
  }
}
