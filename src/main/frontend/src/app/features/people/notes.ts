import { Component, effect, inject, signal } from '@angular/core';
import { Session } from '../../core/session';
import { Note, NotesService } from './notes.service';

// The sheet's notes, one line each, newest first, as "<sheet's today when added> <user>: <text>", and
// the entry by which one is added (a blank note is refused). Read from the store every time a sheet is
// loaded; under an as-of date the server lists only the notes added by then.
@Component({
  selector: 'app-notes',
  template: `
    <section aria-labelledby="notes-heading">
      <h2 id="notes-heading">Notes</h2>
      <form (submit)="submit($event)" aria-label="Note" data-testid="note-form">
        <label for="note-text">Note</label>
        <input id="note-text" data-testid="note-text" [value]="text()" (input)="text.set(value($event)); error.set(null)" />
        <button type="submit" data-testid="note-add">Add note</button>
        @if (error(); as message) {
          <p role="alert" class="error" data-testid="note-error">{{ message }}</p>
        }
      </form>
      @if (notes().length > 0) {
        <ul data-testid="note-list">
          @for (n of notes(); track n.id) {
            <li data-testid="note-line">{{ n.on }} {{ n.by }}: {{ n.text }}</li>
          }
        </ul>
      }
    </section>
  `,
  styles: `
    form {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      align-items: center;
      margin-bottom: 0.5rem;
    }
    .error {
      color: #a40000;
      margin: 0;
    }
  `,
})
export class Notes {
  private readonly session = inject(Session);
  private readonly service = inject(NotesService);

  protected readonly notes = signal<Note[]>([]);
  protected readonly text = signal('');
  protected readonly error = signal<string | null>(null);
  private saving = false;

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      this.session.loads();
      this.notes.set([]);
      this.text.set('');
      this.error.set(null);
      if (sheet) void this.read(sheet);
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.saving) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const text = this.text().trim();
    if (!text) {
      this.error.set('Type a note.');
      return;
    }
    this.saving = true;
    try {
      const all = await this.service.add(sheet, text);
      if (this.session.sheetId() !== sheet) return;
      this.notes.set(all);
      this.text.set('');
    } catch {
      this.error.set('The note could not be added.');
    } finally {
      this.saving = false;
    }
  }

  private async read(sheet: string): Promise<void> {
    try {
      const all = await this.service.list(sheet);
      if (this.session.sheetId() === sheet) this.notes.set(all);
    } catch {
      // The notes could not be read; they show as none.
    }
  }
}
