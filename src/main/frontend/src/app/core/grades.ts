import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Grade } from './people';
import { Session } from './session';

/** A grade of the sheet: its code, and whether it is retired (no longer chosen for anyone; the
 *  people on it keep it). */
export interface GradeState {
  grade: Grade;
  retired: boolean;
}

// The grades of the active sheet and which of them are retired.
@Injectable({ providedIn: 'root' })
export class Grades {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** Every grade of the sheet, in order. */
  list(sheetId: string): Promise<GradeState[]> {
    return firstValueFrom(this.http.get<GradeState[]>(this.url(sheetId)));
  }

  /** Retire a grade; refused (409) when it is retired already. Every grade, as it is now. */
  retire(grade: string): Promise<GradeState[]> {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    return firstValueFrom(
      this.http.post<GradeState[]>(`${this.url(sheetId)}/retire`, { grade }, { headers: { 'X-User': user } }),
    );
  }

  /** Merge a grade into another: everyone on it is given the other from the sheet's today. Refused
   *  (403) unless the user is a steward, (400) when the grades are the same. How many it changed. */
  async merge(from: string, into: string): Promise<number> {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    const res = await firstValueFrom(
      this.http.post<{ merged: number }>(`${this.url(sheetId)}/merge`, { from, into }, { headers: { 'X-User': user } }),
    );
    return res.merged;
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/grades`;
  }
}
