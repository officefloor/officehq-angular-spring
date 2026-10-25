import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export interface HolidayView {
  id: number;
  /** The holiday (yyyy-mm-dd). */
  date: string;
}

// The holidays of a sheet, as the store holds them.
@Injectable({ providedIn: 'root' })
export class HolidaysApi {
  private readonly http = inject(HttpClient);

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/holidays`;
  }

  /** The sheet's holidays in date order. */
  list(sheetId: string): Promise<HolidayView[]> {
    return firstValueFrom(this.http.get<HolidayView[]>(this.url(sheetId)));
  }

  /** Add a holiday: refused when the date is already one. */
  add(sheetId: string, date: string): Promise<HolidayView> {
    return firstValueFrom(this.http.post<HolidayView>(this.url(sheetId), { date }));
  }
}
