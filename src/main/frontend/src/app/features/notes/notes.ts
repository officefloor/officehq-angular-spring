import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { EMPTY, switchMap } from 'rxjs';
import { Notes } from '../../core/notes';
import { Sheets } from '../../core/sheet';

// The sheet's notes, newest first, each under the sheet's today when it was added.
@Component({
  selector: 'app-notes',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="notes-heading">
      <h3 id="notes-heading">Notes</h3>
      <ul aria-label="Notes">
        @for (n of notes.all(); track n.id) {
          <li data-testid="note-line">{{ n.addedOn }} {{ n.by }}: {{ n.text }}</li>
        }
      </ul>

      <form [formGroup]="form" (ngSubmit)="add()" aria-label="Add a note">
        <label for="note-text">Note</label>
        <input id="note-text" data-testid="note-text" formControlName="text" autocomplete="off" />
        <button type="submit" data-testid="note-add" [disabled]="saving()">Add note</button>
        @if (error(); as err) {
          <p role="alert" data-testid="note-error">{{ err }}</p>
        }
      </form>
    </section>
  `,
})
export class NotesPanel {
  protected readonly notes = inject(Notes);
  private readonly sheets = inject(Sheets);

  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly form = new FormGroup({
    text: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    // Each (re)load of a sheet re-reads its notes from the store.
    toObservable(this.sheets.loaded)
      .pipe(
        switchMap((sheet) => {
          this.error.set(null);
          return this.notes.load(sheet?.id ?? null) ?? EMPTY;
        }),
        takeUntilDestroyed(),
      )
      .subscribe({ error: () => this.error.set('Could not read the notes.') });
    // An error is removed as soon as its form is changed.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.error.set(null));
  }

  protected add(): void {
    // A second submit before the first has answered is ignored, so it stores one note.
    if (this.saving()) return;
    this.error.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const text = this.form.controls.text.value.trim();
    if (text === '') {
      this.error.set('Type the note.');
      return;
    }
    this.saving.set(true);
    this.notes.add(sheet.id, text).subscribe({
      next: () => {
        this.saving.set(false);
        this.form.reset({ text: '' }, { emitEvent: false });
      },
      error: () => {
        this.saving.set(false);
        this.error.set('Could not add the note.');
      },
    });
  }
}
