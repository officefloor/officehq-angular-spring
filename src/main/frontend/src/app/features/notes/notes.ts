import { Component, inject, signal } from '@angular/core';
import { Notes } from '../../core/notes';

// The sheet's notes, newest first, each with the sheet's today when it was added and who added it.
@Component({
  selector: 'app-notes',
  template: `
    <section aria-labelledby="notes-heading">
      <h2 id="notes-heading">Notes</h2>
      <form (submit)="add($event)" aria-labelledby="notes-heading">
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
        @for (n of notes.list(); track n.id) {
          <li data-testid="note-line">{{ n.date }} {{ n.user }}: {{ n.text }}</li>
        }
      </ul>
    </section>
  `,
})
export class NotesList {
  protected readonly notes = inject(Notes);

  protected readonly text = signal('');
  protected readonly error = signal<string | null>(null);
  /** Whether an add is waiting for its answer: a second add meanwhile is ignored. */
  private adding = false;

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async add(event: Event): Promise<void> {
    event.preventDefault();
    if (this.adding) return;
    this.error.set(null);
    const text = this.text().trim();
    if (text === '') {
      this.error.set('Type a note.');
      return;
    }
    this.adding = true;
    try {
      await this.notes.add(text);
      this.text.set('');
    } catch {
      this.error.set('The note could not be added.');
    } finally {
      this.adding = false;
    }
  }
}
