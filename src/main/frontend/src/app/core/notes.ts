import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/** A note on the sheet: the sheet's today when it was added (YYYY-MM-DD), who added it, and its text. */
export interface NoteView {
  id: number;
  date: string;
  user: string;
  text: string;
}

// The active sheet's notes, re-read from the server each time the sheet is loaded; as of a date, only those
// added while the sheet's today was on or before it.
@Injectable({ providedIn: 'root' })
export class Notes {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** Newest first. */
  readonly notes = signal<NoteView[]>([]);
  private loads = 0;

  /** Re-read the active sheet's notes from the server; an error message when that failed. */
  async load(): Promise<string | null> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    const load = ++this.loads;
    this.notes.set([]);
    if (!user || !sid) return null;
    try {
      const notes = await firstValueFrom(this.http.get<NoteView[]>(this.url(sid), { headers: { 'X-User': user } }));
      if (load === this.loads) this.notes.set(notes);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not load notes (${e.status}).` : 'Could not load notes.';
    }
  }

  /** Add a note to the sheet; an error message when refused. */
  async add(text: string): Promise<string | null> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    const t = text.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (t === '') return "Enter the note's text.";
    try {
      const saved = await firstValueFrom(
        this.http.post<NoteView>(this.url(sid), { text: t }, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) {
        this.notes.update((ns) => [saved, ...ns.filter((n) => n.id !== saved.id)]);
      }
      return null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && typeof e.error?.message === 'string') return e.error.message;
      return e instanceof HttpErrorResponse ? `Could not add note (${e.status}).` : 'Could not add note.';
    }
  }

  private url(sid: string): string {
    return `/api/sheets/${encodeURIComponent(sid)}/notes`;
  }
}
