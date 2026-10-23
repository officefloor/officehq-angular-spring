import { Component, effect, inject, signal, untracked } from '@angular/core';
import { NoteView, NotesApi } from '../../core/notes';
import { Session } from '../../core/session';

// The sheet's notes, newest first, and a form to add one; a blank note is refused.
@Component({
  selector: 'app-notes',
  template: `
    <section aria-labelledby="notes-heading">
      <h2 id="notes-heading">Notes</h2>
      <form (submit)="add($event)" aria-label="Add a note">
        <label for="note-text">Note</label>
        <input
          id="note-text"
          data-testid="note-text"
          [value]="text()"
          (input)="text.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="note-add">Add note</button>
        @if (error(); as message) {
          <p role="alert" data-testid="note-error">{{ message }}</p>
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
  private readonly session = inject(Session);
  private readonly api = inject(NotesApi);

  protected readonly notes = signal<NoteView[]>([]);
  protected readonly text = signal('');
  protected readonly error = signal<string | null>(null);
  /** A submit is waiting for its answer: a second submit meanwhile is ignored. */
  private busy = false;

  constructor() {
    // Every sheet load re-reads the sheet's notes from the store.
    effect(() => {
      const sheet = this.session.sheet();
      const loads = this.session.loads();
      untracked(() => {
        this.notes.set([]);
        this.error.set(null);
        if (sheet) void this.reload(sheet, loads);
      });
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async add(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const text = this.text().trim();
    if (!text) {
      this.error.set('Write something in the note.');
      return;
    }
    this.busy = true;
    try {
      const saved = await this.api.add(sheet, text);
      this.notes.update((all) => [saved, ...all]);
      this.text.set('');
    } catch {
      this.error.set('The note could not be added.');
    } finally {
      this.busy = false;
    }
  }

  private async reload(sheet: string, loads: number): Promise<void> {
    const notes = await this.api.list(sheet);
    // A later load may have started while this one was in flight: only the latest is shown.
    if (this.session.sheet() === sheet && this.session.loads() === loads) this.notes.set(notes);
  }
}
