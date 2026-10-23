import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** A note on the sheet. */
export interface NoteView {
  id: number;
  /** yyyy-mm-dd: the sheet's today when the note was added. */
  date: string;
  /** Who added it. */
  user: string;
  text: string;
}

// The sheet's notes, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class NotesApi {
  private readonly http = inject(HttpClient);

  /** The sheet's notes, newest first. */
  list(sheet: string): Promise<NoteView[]> {
    return firstValueFrom(this.http.get<NoteView[]>(this.url(sheet)));
  }

  /** Add a note; a blank one is refused. */
  add(sheet: string, text: string): Promise<NoteView> {
    return firstValueFrom(this.http.post<NoteView>(this.url(sheet), { text }));
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/notes`;
  }
}
