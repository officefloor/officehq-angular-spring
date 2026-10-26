import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export interface NoteView {
  id: number;
  addedOn: string;
  addedBy: string;
  text: string;
}

// The notes of a sheet, as the store holds them.
@Injectable({ providedIn: 'root' })
export class NotesApi {
  private readonly http = inject(HttpClient);

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/notes`;
  }

  /** The sheet's notes, newest first. */
  list(sheetId: string): Promise<NoteView[]> {
    return firstValueFrom(this.http.get<NoteView[]>(this.url(sheetId)));
  }

  /** Add a note: refused when it is blank. */
  add(sheetId: string, text: string): Promise<NoteView> {
    return firstValueFrom(this.http.post<NoteView>(this.url(sheetId), { text }));
  }
}

// The active sheet's notes, newest first. Each load of a sheet (and each change of its as-of date)
// re-reads them from the store.
@Injectable({ providedIn: 'root' })
export class Notes {
  private readonly session = inject(Session);
  private readonly api = inject(NotesApi);

  private readonly state = signal<NoteView[]>([]);
  readonly notes = this.state.asReadonly();
  /** The refusal of the last loading of the notes, or blank. */
  readonly loadError = signal('');

  constructor() {
    effect(() => {
      const sheet = this.session.loaded();
      this.state.set([]);
      this.loadError.set('');
      if (sheet) void this.load(sheet.id);
    });
  }

  private async load(sheetId: string): Promise<void> {
    try {
      const list = await this.api.list(sheetId);
      if (this.session.sheet()?.id === sheetId) this.state.set(list);
    } catch {
      if (this.session.sheet()?.id === sheetId) this.loadError.set('The notes could not be loaded.');
    }
  }

  /** Add a note to the active sheet; the store's answer goes to the top of the list. */
  async add(sheetId: string, text: string): Promise<void> {
    const added = await this.api.add(sheetId, text);
    if (this.session.sheet()?.id === sheetId) this.state.update((list) => [added, ...list]);
  }
}
