import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/** A note of the sheet: the sheet's today when it was added (yyyy-mm-dd), who added it, its text. */
export interface Note {
  id: number;
  addedOn: string;
  user: string;
  text: string;
}

// The notes of the active sheet.
@Injectable({ providedIn: 'root' })
export class Notes {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The notes of the sheet, newest first. */
  list(sheetId: string): Promise<Note[]> {
    return firstValueFrom(this.http.get<Note[]>(this.url(sheetId)));
  }

  /** Add a note to the sheet; refused (400) for a blank one. */
  add(text: string): Promise<Note> {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    return firstValueFrom(this.http.post<Note>(this.url(sheetId), { text }, { headers: { 'X-User': user } }));
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/notes`;
  }
}
