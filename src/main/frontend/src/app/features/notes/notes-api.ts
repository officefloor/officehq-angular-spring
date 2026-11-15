import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session, otherError } from '../../core/session';

/** A sheet note: the sheet's today when it was added, the user who added it and its text. */
export interface SheetNote {
  id: number;
  on: string;
  by: string;
  text: string;
}

@Injectable({ providedIn: 'root' })
export class NotesApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The sheet's notes, newest first. */
  list(sheetId: string): Promise<SheetNote[]> {
    return firstValueFrom(this.http.get<SheetNote[]>(this.url(sheetId), { headers: this.headers() }));
  }

  /** Add a note; resolves to the sheet's notes with it. */
  add(sheetId: string, text: string): Promise<SheetNote[]> {
    return firstValueFrom(this.http.post<SheetNote[]>(this.url(sheetId), { text }, { headers: this.headers() }));
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/notes`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}

export function noteError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter the text of the note.';
  return otherError(e);
}
