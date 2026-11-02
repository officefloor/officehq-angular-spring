import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/** A holiday of the sheet: its day (yyyy-mm-dd). */
export interface Holiday {
  id: number;
  date: string;
}

// The holidays of the active sheet: a claim's due-by fixed at its creation is moved past them.
@Injectable({ providedIn: 'root' })
export class Holidays {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The holidays of the sheet, earliest first. */
  list(sheetId: string): Promise<Holiday[]> {
    return firstValueFrom(this.http.get<Holiday[]>(this.url(sheetId)));
  }

  /** Add a day to the sheet's holidays; refused (409) for a day already on the list. */
  add(date: string): Promise<Holiday> {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    return firstValueFrom(this.http.post<Holiday>(this.url(sheetId), { date }, { headers: { 'X-User': user } }));
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/holidays`;
  }
}
