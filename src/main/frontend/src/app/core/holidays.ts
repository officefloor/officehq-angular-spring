import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** A holiday on the sheet: a claim's due-by falling on one is moved to the next day that is not one. */
export interface HolidayView {
  id: number;
  /** yyyy-mm-dd; on the sheet's list at most once. */
  date: string;
}

// The sheet's holidays, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class HolidaysApi {
  private readonly http = inject(HttpClient);

  /** The sheet's holidays by date. */
  list(sheet: string): Promise<HolidayView[]> {
    return firstValueFrom(this.http.get<HolidayView[]>(this.url(sheet)));
  }

  /** Add a holiday; refused when the date is already on the list. */
  add(sheet: string, date: string): Promise<HolidayView> {
    return firstValueFrom(this.http.post<HolidayView>(this.url(sheet), { date }));
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/holidays`;
  }
}
