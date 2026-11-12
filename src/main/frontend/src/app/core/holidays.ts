import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session, isDate } from './session';

/** A holiday on the sheet's list (YYYY-MM-DD). */
export interface HolidayView {
  id: number;
  date: string;
}

// The active sheet's holidays, re-read from the server each time the sheet is loaded. A claim added whose
// date plus its terms falls on one is due by the next day that is not one; a holiday added later leaves the
// due-by dates already fixed as they are.
@Injectable({ providedIn: 'root' })
export class Holidays {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** Earliest first. */
  readonly holidays = signal<HolidayView[]>([]);
  private loads = 0;

  /** Re-read the active sheet's holidays from the server; an error message when that failed. */
  async load(): Promise<string | null> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    const load = ++this.loads;
    this.holidays.set([]);
    if (!user || !sid) return null;
    try {
      const holidays = await firstValueFrom(
        this.http.get<HolidayView[]>(this.url(sid), { headers: { 'X-User': user } }),
      );
      if (load === this.loads) this.holidays.set(holidays);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not load holidays (${e.status}).` : 'Could not load holidays.';
    }
  }

  /** Add a date to the sheet's holidays; an error message when refused. */
  async add(date: string): Promise<string | null> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    const d = date.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (!isDate(d)) return 'Enter the date as YYYY-MM-DD.';
    try {
      const saved = await firstValueFrom(
        this.http.post<HolidayView>(this.url(sid), { date: d }, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) {
        this.holidays.update((hs) => [...hs.filter((h) => h.date !== saved.date), saved]
          .sort((a, b) => a.date.localeCompare(b.date)));
      }
      return null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && typeof e.error?.message === 'string') return e.error.message;
      if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter the date as YYYY-MM-DD.';
      return e instanceof HttpErrorResponse ? `Could not add holiday (${e.status}).` : 'Could not add holiday.';
    }
  }

  private url(sid: string): string {
    return `/api/sheets/${encodeURIComponent(sid)}/holidays`;
  }
}
