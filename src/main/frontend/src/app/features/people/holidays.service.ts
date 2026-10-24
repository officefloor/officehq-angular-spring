import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A holiday on the sheet (YYYY-MM-DD): not a business day, so no claim's terms count it and none is due by it. */
export interface Holiday {
  id: number;
  date: string;
}

// The holidays on a sheet, read from and written to the server.
@Injectable({ providedIn: 'root' })
export class HolidaysService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  list(sheet: string): Promise<Holiday[]> {
    return firstValueFrom(this.http.get<Holiday[]>(this.url(sheet), { headers: this.session.headers() }));
  }

  /** Add a holiday. Refused (409) when the date is already on the list. Resolves to every holiday on the sheet. */
  add(sheet: string, date: string): Promise<Holiday[]> {
    return firstValueFrom(this.http.post<Holiday[]>(this.url(sheet), { date }, { headers: this.session.headers() }));
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/holidays`;
  }
}
