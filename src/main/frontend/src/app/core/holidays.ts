import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

/** A holiday on the sheet: a claim's due-by fixed at its creation skips it when counting its terms in business days. */
export interface HolidayView {
  id: number;
  date: string;
}

// The holidays on the active sheet, earliest first.
@Injectable({ providedIn: 'root' })
export class Holidays {
  private readonly http = inject(HttpClient);
  private readonly list = signal<HolidayView[]>([]);
  readonly all = this.list.asReadonly();

  /** Re-read a sheet's holidays from the store; null clears them. */
  load(sheetId: string | null): Observable<HolidayView[]> | null {
    this.list.set([]);
    if (sheetId === null) return null;
    return this.http.get<HolidayView[]>(this.base(sheetId)).pipe(tap((list) => this.list.set(list)));
  }

  /** Add a holiday; refused when the date is already on the list. */
  add(sheetId: string, date: string): Observable<HolidayView> {
    return this.http.post<HolidayView>(this.base(sheetId), { date }).pipe(
      tap((saved) =>
        this.list.update((list) => [...list, saved].sort((a, b) => a.date.localeCompare(b.date))),
      ),
    );
  }

  private base(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/holidays`;
  }
}
