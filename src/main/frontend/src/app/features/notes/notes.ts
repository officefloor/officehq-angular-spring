import { HttpClient } from '@angular/common/http';
import { Component, Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export interface Note {
  id: number;
  /** The sheet's today (yyyy-mm-dd) when the note was added. */
  addedOn: string;
  /** The user who added it. */
  by: string;
  text: string;
}

// The active sheet's notes, the newest first, re-read whenever a sheet is loaded or the as-of date changes.
@Injectable({ providedIn: 'root' })
export class NoteStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly notes = signal<Note[]>([]);

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      const load = this.session.loads();
      this.notes.set([]);
      if (sheet) void this.read(sheet, load);
    });
  }

  /** Add a note to the active sheet; the server refuses a blank text. */
  async add(text: string): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.post<Note>(`/api/sheets/${encodeURIComponent(sheet)}/notes`, { text }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.notes.update((list) => [saved, ...list.filter((n) => n.id !== saved.id)]);
  }

  private async read(sheet: string, load: number): Promise<void> {
    const list = await firstValueFrom(
      this.http.get<Note[]>(`/api/sheets/${encodeURIComponent(sheet)}/notes`),
    );
    if (this.session.loads() !== load) return;
    this.notes.set(list);
  }
}

// The sheet's notes, the newest first, and the entry that adds one.
@Component({
  selector: 'app-notes',
  template: `
    <section aria-labelledby="notes-heading">
      <h2 id="notes-heading">Notes</h2>
      <form (submit)="submit($event)">
        <label for="note-text">Note</label>
        <input
          id="note-text"
          type="text"
          data-testid="note-text"
          [value]="text()"
          (input)="text.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="note-add">Add note</button>
        @if (error(); as message) {
          <p role="alert" data-testid="note-error">{{ message }}</p>
        }
      </form>
      <ul>
        @for (n of store.notes(); track n.id) {
          <li data-testid="note-line">{{ n.addedOn }} {{ n.by }}: {{ n.text }}</li>
        }
      </ul>
    </section>
  `,
})
export class Notes {
  protected readonly store = inject(NoteStore);
  protected readonly text = signal('');
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer; a second submit meanwhile is dropped. */
  private readonly busy = signal(false);

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  /** Add the typed text as a note; a blank text, or one the sheet refuses, shows beside the entry. */
  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy()) return;
    this.error.set(null);
    const text = this.text().trim();
    if (!text) {
      this.error.set('Type a text for the note.');
      return;
    }
    this.busy.set(true);
    try {
      await this.store.add(text);
      this.text.set('');
    } catch {
      this.error.set('The note could not be added.');
    } finally {
      this.busy.set(false);
    }
  }
}
