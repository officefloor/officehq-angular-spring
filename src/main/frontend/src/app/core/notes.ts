import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/** A note on the sheet: the sheet's today when it was added, as yyyy-mm-dd, and who added it. */
export interface NoteView {
  id: number;
  date: string;
  user: string;
  text: string;
}

// The active sheet's notes, newest first, re-read whenever a sheet is loaded.
@Injectable({ providedIn: 'root' })
export class Notes {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly list = signal<NoteView[]>([]);
  private reads = 0;

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loaded();
      untracked(() => {
        this.list.set([]);
        if (sheet) void this.read(sheet);
      });
    });
  }

  /** Add a note (its text already trimmed) to the active sheet; throws when the store refuses it. */
  async add(text: string): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.post<NoteView>(`/api/sheets/${encodeURIComponent(sheet)}/notes`, { text }, this.session.headers()),
    );
    if (this.session.sheet() === sheet) this.list.update((list) => [saved, ...list]);
  }

  private async read(sheet: string): Promise<void> {
    const read = ++this.reads;
    const list = await firstValueFrom(
      this.http.get<NoteView[]>(`/api/sheets/${encodeURIComponent(sheet)}/notes`, this.session.headers()),
    ).catch(() => null);
    // A later read wins over a slower earlier one.
    if (list && read === this.reads && this.session.sheet() === sheet) {
      this.list.set(list);
    }
  }
}
