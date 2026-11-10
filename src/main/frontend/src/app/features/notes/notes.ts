import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// The sheet's notes, newest first, each shown as "<the sheet's today when added> <user>: <text>",
// and the entry that adds one; a blank note is refused. Shown as of a date, the list holds the
// notes added while the sheet's today was on or before that date.
export interface Note {
  id: number;
  text: string;
  user: string;
  date: string;
}

@Component({
  selector: 'app-notes',
  template: `
    <section aria-labelledby="notes-heading">
      <h2 id="notes-heading">Notes</h2>
      <form aria-label="Note" (submit)="add($event)">
        <label for="note-text">Note</label>
        <input
          id="note-text"
          data-testid="note-text"
          [value]="textInput()"
          (input)="textInput.set($any($event.target).value); error.set(null)"
        />
        <button type="submit" data-testid="note-add">Add note</button>
        @if (error(); as err) {
          <p role="alert" data-testid="note-error">{{ err }}</p>
        }
      </form>
      <ul aria-label="Notes">
        @for (n of notes(); track n.id) {
          <li data-testid="note-line">{{ n.date }} {{ n.user }}: {{ n.text }}</li>
        }
      </ul>
    </section>
  `,
})
export class Notes {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly notes = signal<Note[]>([]);
  protected readonly textInput = signal('');
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;

  constructor() {
    // Re-read the sheet's notes from the store every time a sheet is loaded.
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.notes.set([]);
      this.textInput.set('');
      this.error.set(null);
      if (sheet) {
        void this.fetch(sheet);
      }
    });
  }

  private async fetch(sheet: string): Promise<void> {
    try {
      const list = await firstValueFrom(this.http.post<Note[]>('/api/notes/list', { sheet }));
      if (this.session.sheet() === sheet) this.notes.set(list);
    } catch {
      this.error.set('Could not read the notes on this sheet.');
    }
  }

  protected async add(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    const text = this.textInput().trim();
    if (!text) {
      this.error.set('Enter the note to add.');
      return;
    }
    this.submitting = true;
    try {
      const added = await firstValueFrom(
        this.http.post<Note>('/api/notes/add', { sheet, user: this.session.user(), text }),
      );
      if (this.session.sheet() !== sheet) return;
      this.notes.update((list) => [added, ...list]);
      this.textInput.set('');
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 403
          ? 'Your role may not add notes.'
          : e instanceof HttpErrorResponse && e.status === 423
            ? 'This sheet may not be changed.'
            : 'Could not add this note.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
