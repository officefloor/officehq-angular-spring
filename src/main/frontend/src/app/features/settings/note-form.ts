import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Note, Notes } from '../../core/notes';
import { Session } from '../../core/session';

// The sheet's notes, newest first, and the entry a note is added with.
@Component({
  selector: 'app-note-form',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="notes-heading">
      <h3 id="notes-heading">Notes</h3>
      <form data-testid="note-form" [formGroup]="form" (ngSubmit)="submit()">
        <label for="note-text">Note</label>
        <input id="note-text" type="text" data-testid="note-text" formControlName="text" />
        <button type="submit" data-testid="note-add">Add note</button>
        @if (error()) {
          <p role="alert" data-testid="note-error">{{ error() }}</p>
        }
      </form>
      <ul data-testid="notes">
        @for (n of notes(); track n.id) {
          <li data-testid="note-line">{{ n.addedOn }} {{ n.user }}: {{ n.text }}</li>
        }
      </ul>
    </section>
  `,
})
export class NoteForm {
  private readonly api = inject(Notes);
  private readonly session = inject(Session);

  protected readonly notes = signal<Note[]>([]);
  protected readonly error = signal('');
  protected readonly form = new FormGroup({
    text: new FormControl('', { nonNullable: true }),
  });
  private submitting = false;
  private reads = 0;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    effect(() => {
      this.session.loads();
      const sheetId = this.session.sheetId();
      this.notes.set([]);
      if (sheetId) void this.reload(sheetId);
    });
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    const { text } = this.form.getRawValue();
    if (!text.trim()) {
      this.error.set('Enter the note.');
      return;
    }
    this.submitting = true;
    try {
      const saved = await this.api.add(text);
      this.notes.update((ns) => [saved, ...ns.filter((n) => n.id !== saved.id)]);
      this.form.reset({ text: '' }, { emitEvent: false });
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 409
          ? 'The sheet may not be changed.'
          : e instanceof HttpErrorResponse && e.status === 403
            ? 'A viewer may not add a note.'
            : 'The note could not be added.',
      );
    } finally {
      this.submitting = false;
    }
  }

  private async reload(sheetId: string): Promise<void> {
    const read = ++this.reads;
    try {
      const notes = await this.api.list(sheetId);
      if (read === this.reads) this.notes.set(notes);
    } catch {
      // the list stays empty; the next load reads it again
    }
  }
}
