import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal, untracked } from '@angular/core';
import { FigPipe } from '../../core/locale';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

interface NoteView {
  id: number;
  addedOn: string;
  addedBy: string;
  text: string;
}

// The active sheet's notes, newest first, each shown with the sheet's today when it was added and who added it. A
// blank note is refused.
@Component({
  selector: 'app-notes',
  imports: [FigPipe],
  template: `
    <section aria-label="Notes">
      <h2>Notes</h2>
      <form (submit)="add($event)" aria-label="Add a note">
        <label for="note-text">Note</label>
        <input
          id="note-text"
          data-testid="note-text"
          [value]="text()"
          (input)="text.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="note-add">Add note</button>
        @if (error(); as err) {
          <p role="alert" data-testid="note-error">{{ err | fig }}</p>
        }
      </form>

      <ul aria-label="Notes on the sheet">
        @for (n of notes(); track n.id) {
          <li data-testid="note-line">{{ n.addedOn }} {{ n.addedBy }}: {{ n.text }}</li>
        }
      </ul>
    </section>
  `,
})
export class Notes {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly notes = signal<readonly NoteView[]>([]);
  protected readonly text = signal('');
  protected readonly error = signal<string | null>(null);
  // A submit in flight: a second one before it answers is ignored.
  private submitting = false;

  constructor() {
    // Re-read the notes from the store each time a sheet is loaded.
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.error.set(null);
        this.notes.set([]);
        if (id) this.load(id).catch(() => this.error.set('Could not load the notes.'));
      });
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  private async load(sheet: string): Promise<void> {
    const l = await firstValueFrom(this.http.get<NoteView[]>(`/api/sheets/${encodeURIComponent(sheet)}/notes`));
    if (this.session.sheetId() === sheet) this.notes.set(l);
  }

  protected async add(e: Event): Promise<void> {
    e.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const text = this.text().trim();
    if (!text) {
      this.error.set('Enter a note.');
      return;
    }
    this.submitting = true;
    try {
      const n = await firstValueFrom(
        this.http.post<NoteView>(`/api/sheets/${encodeURIComponent(sheet)}/notes`, { text }),
      );
      if (this.session.sheetId() === sheet) this.notes.update((l) => [n, ...l]);
    } catch {
      this.error.set('Could not add the note.');
    } finally {
      this.submitting = false;
    }
  }
}
