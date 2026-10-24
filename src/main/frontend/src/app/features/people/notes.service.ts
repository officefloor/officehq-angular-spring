import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A note on the sheet: the sheet's today when it was added (YYYY-MM-DD), who added it and its text. */
export interface Note {
  id: number;
  on: string;
  by: string;
  text: string;
}

// The notes on a sheet, read from and written to the server.
@Injectable({ providedIn: 'root' })
export class NotesService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The sheet's notes, newest first; under an as-of date, those added while its today was on or before it. */
  list(sheet: string): Promise<Note[]> {
    return firstValueFrom(this.http.get<Note[]>(this.url(sheet), { headers: this.session.headers() }));
  }

  /** Add a note. Refused (400) when its text is blank. Resolves to every note on the sheet. */
  add(sheet: string, text: string): Promise<Note[]> {
    return firstValueFrom(this.http.post<Note[]>(this.url(sheet), { text }, { headers: this.session.headers() }));
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/notes`;
  }
}
