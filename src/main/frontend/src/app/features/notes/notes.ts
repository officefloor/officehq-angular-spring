import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A note on the sheet: the sheet's today when it was added, who added it and its text. */
interface Note {
  on: string;
  by: string;
  text: string;
}

// The sheet's notes, newest first, and the entry that adds one; a blank note is refused. Re-read on every sheet
// load, so under an as-of date only the notes added while the sheet's today was on or before it show.
@Component({
  selector: 'app-notes',
  template: `
    <section aria-label="Sheet notes">
      <h2>Notes</h2>
      <ul>
        @for (n of notes(); track $index) {
          <li data-testid="note-line">{{ n.on }} {{ n.by }}: {{ n.text }}</li>
        }
      </ul>
      <form (submit)="add($event)" aria-label="Add note">
        <label for="note-text">Note</label>
        <input
          id="note-text"
          data-testid="note-text"
          [value]="text()"
          (input)="text.set(value($event)); error.set('')"
        />
        <button type="submit" data-testid="note-add">Add note</button>
        @if (error()) {
          <p role="alert" data-testid="note-error">{{ error() }}</p>
        }
      </form>
    </section>
  `,
})
export class Notes {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly notes = signal<Note[]>([]);
  protected readonly text = signal('');
  protected readonly error = signal('');
  /** An add still waiting for its answer: another press meanwhile is ignored. */
  private busy = false;

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.notes.set([]);
      this.text.set('');
      this.error.set('');
      if (sheet) void this.reload(sheet);
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async add(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.error.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    if (this.text().trim() === '') {
      this.error.set("Enter the note's text.");
      return;
    }
    this.busy = true;
    try {
      const notes = await firstValueFrom(
        this.http.post<Note[]>(
          `/api/sheets/${encodeURIComponent(sheet)}/notes`,
          { text: this.text() },
          { headers: { 'X-User': this.session.user() ?? '' } },
        ),
      );
      if (this.session.sheet() === sheet) this.notes.set(notes);
      this.text.set('');
    } catch (err) {
      const answer = err instanceof HttpErrorResponse ? err : null;
      this.error.set(answer?.error?.message ?? 'Could not add the note.');
    } finally {
      this.busy = false;
    }
  }

  private async reload(sheet: string): Promise<void> {
    const loads = this.session.loads();
    const notes = await firstValueFrom(this.http.get<Note[]>(`/api/sheets/${encodeURIComponent(sheet)}/notes`));
    if (this.session.sheet() === sheet && this.session.loads() === loads) this.notes.set(notes);
  }
}
