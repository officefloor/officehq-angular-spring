import { Component, effect, inject, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { NotesApi, SheetNote, noteError } from './notes-api';

// The sheet's notes, newest first, and the entry that adds one.
@Component({
  selector: 'app-notes',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="notes-heading">
      <h2 id="notes-heading">Notes</h2>
      <form [formGroup]="form" (ngSubmit)="add()" aria-label="Add a note">
        <label for="note-text">Note</label>
        <input id="note-text" data-testid="note-text" formControlName="text" autocomplete="off" />
        <button type="submit" data-testid="note-add">Add note</button>
        @if (error(); as err) {
          <p role="alert" data-testid="note-error">{{ err }}</p>
        }
      </form>
      <ul aria-label="Notes" data-testid="notes-list">
        @for (n of notes(); track n.id) {
          <li data-testid="note-line">{{ n.on }} {{ n.by }}: {{ n.text }}</li>
        }
      </ul>
    </section>
  `,
})
export class Notes {
  private readonly session = inject(Session);
  private readonly api = inject(NotesApi);

  protected readonly notes = signal<SheetNote[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly form = new FormGroup({
    text: new FormControl('', { nonNullable: true }),
  });
  private adding = false;
  /** Guards against a slower, older read landing over a newer one. */
  private readSeq = 0;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(null));
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.notes.set([]);
        this.error.set(null);
        this.form.reset({ text: '' }, { emitEvent: false });
        if (id) void this.read(id);
      });
    });
  }

  protected async add(): Promise<void> {
    if (this.adding) return;
    this.error.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const text = this.form.getRawValue().text.trim();
    if (text === '') {
      this.error.set('Enter the text of the note.');
      return;
    }
    this.adding = true;
    try {
      const notes = await this.api.add(sheetId, text);
      if (this.session.sheetId() === sheetId) {
        this.readSeq++;
        this.notes.set(notes);
      }
      this.form.reset({ text: '' }, { emitEvent: false });
    } catch (e) {
      this.error.set(noteError(e));
    } finally {
      this.adding = false;
    }
  }

  private async read(sheetId: string): Promise<void> {
    const seq = ++this.readSeq;
    try {
      const notes = await this.api.list(sheetId);
      if (seq === this.readSeq && this.session.sheetId() === sheetId) this.notes.set(notes);
    } catch {
      if (seq === this.readSeq) this.error.set('Something went wrong loading notes; try again.');
    }
  }
}
