import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A note on the sheet: the sheet's today when it was added, who added it, and its text. */
interface NoteView {
  id: number;
  addedOn: string;
  addedBy: string;
  text: string;
}

// The sheet's notes, newest first; a blank note is refused.
@Component({
  selector: 'app-notes',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="notes-heading">
      <h2 id="notes-heading">Notes</h2>
      <form [formGroup]="form" (ngSubmit)="add()" aria-label="Add a note">
        <label for="note-text">Note</label>
        <input id="note-text" data-testid="note-text" formControlName="text" />
        <button type="submit" data-testid="note-add">Add note</button>
        @if (error()) {
          <p role="alert" class="error" data-testid="note-error">{{ error() }}</p>
        }
      </form>
      <ul aria-label="Notes">
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

  protected readonly notes = signal<NoteView[]>([]);
  protected readonly error = signal('');
  protected readonly form = new FormGroup({ text: new FormControl('', { nonNullable: true }) });
  private submitting = false;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      this.notes.set([]);
      this.error.set('');
      if (id) void this.load(id);
    });
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }

  private base(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/notes`;
  }

  private async load(sheet: string): Promise<void> {
    try {
      const list = await firstValueFrom(this.http.get<NoteView[]>(this.base(sheet), { headers: this.headers() }));
      if (this.session.sheetId() === sheet) this.notes.set(list);
    } catch {
      if (this.session.sheetId() === sheet) this.error.set('Could not load the notes of this sheet.');
    }
  }

  /** Add a note to the sheet; a blank one is refused. */
  protected async add(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const text = this.form.controls.text.value.trim();
    if (!text) {
      this.error.set('Enter the text of the note.');
      return;
    }
    this.submitting = true;
    try {
      const saved = await firstValueFrom(this.http.post<NoteView>(this.base(sheet), { text }, { headers: this.headers() }));
      if (this.session.sheetId() !== sheet) return;
      this.notes.update((list) => [saved, ...list.filter((n) => n.id !== saved.id)]);
    } catch {
      if (this.session.sheetId() === sheet) this.error.set('Could not add that note.');
    } finally {
      this.submitting = false;
    }
  }
}
