import { Component, inject, signal } from '@angular/core';
import { Notes } from '../../core/notes';

// The sheet's notes: a form adds a text (a blank one is refused), and the list shows them newest first, each
// as the sheet's today when it was added, who added it, and its text.
@Component({
  selector: 'app-notes',
  template: `
    <section aria-label="Notes" data-testid="notes">
      <h2>Notes</h2>
      <form (submit)="add($event)" data-testid="note-form">
        <label for="note-text">Note</label>
        <input
          id="note-text"
          data-testid="note-text"
          [value]="text()"
          (input)="text.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="note-add" [disabled]="saving()">Add note</button>
        @if (error(); as err) {
          <p role="alert" data-testid="note-error">{{ err }}</p>
        }
      </form>
      <ul data-testid="note-list" aria-label="Notes">
        @for (n of notes.notes(); track n.id) {
          <li data-testid="note-line">{{ n.date }} {{ n.user }}: {{ n.text }}</li>
        }
      </ul>
    </section>
  `,
})
export class NotesPanel {
  protected readonly notes = inject(Notes);
  protected readonly text = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async add(e: Event): Promise<void> {
    e.preventDefault();
    if (this.saving()) return; // a second submit before the first has answered
    this.error.set(null);
    this.saving.set(true);
    try {
      const err = await this.notes.add(this.text());
      if (err) this.error.set(err);
      else this.text.set('');
    } finally {
      this.saving.set(false);
    }
  }
}
