import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { Notes } from './notes-api';

// The sheet's notes, newest first, with an entry to add one.
@Component({
  selector: 'app-notes',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="notes-heading">
      <h2 id="notes-heading">Notes</h2>
      <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Add a note">
        <label for="note-text">Note</label>
        <input id="note-text" data-testid="note-text" formControlName="text" />
        <button type="submit" data-testid="note-add" [disabled]="saving()">Add note</button>
        @if (error() || store.loadError(); as message) {
          <p role="alert" data-testid="note-error">{{ message }}</p>
        }
      </form>
      <ul aria-label="Notes">
        @for (n of store.notes(); track n.id) {
          <li data-testid="note-line">{{ n.addedOn }} {{ n.addedBy }}: {{ n.text }}</li>
        }
      </ul>
    </section>
  `,
})
export class NotesList {
  private readonly session = inject(Session);
  protected readonly store = inject(Notes);

  protected readonly text = new FormControl('', { nonNullable: true });
  protected readonly form = new FormGroup({ text: this.text });
  protected readonly saving = signal(false);
  protected readonly error = signal('');

  constructor() {
    this.text.valueChanges.subscribe(() => this.error.set(''));
  }

  protected async submit(): Promise<void> {
    // A second submit before the first has answered is ignored, so it stores one note, not two.
    if (this.saving()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.error.set('');
    const text = this.text.value.trim();
    if (text === '') {
      this.error.set('Type a note.');
      return;
    }
    this.saving.set(true);
    try {
      await this.store.add(sheet.id, text);
      if (this.session.sheet()?.id === sheet.id) this.text.reset('', { emitEvent: false });
    } catch (e) {
      if (this.session.sheet()?.id !== sheet.id) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      this.error.set(status === 403 ? 'Your role may not add a note on this sheet.'
        : status === 409 ? 'The sheet cannot be changed now.' : 'The note could not be added.');
    } finally {
      this.saving.set(false);
    }
  }
}
