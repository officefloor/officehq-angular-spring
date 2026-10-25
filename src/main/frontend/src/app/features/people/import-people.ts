import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { PeopleApi, PersonView } from './people-api';

// A box that takes lines of name,grade,terms,contact and adds a person per line, in order, as the person
// form would; every line is checked first, and when any is refused no one is added.
@Component({
  selector: 'app-import-people',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Import people">
      <label for="import-people">Import people, one per line: name,grade,terms,contact</label>
      <textarea id="import-people" data-testid="import-people" formControlName="text" rows="4"></textarea>
      <button type="submit" data-testid="import-submit" [disabled]="saving()">Import people</button>
      @if (count() !== null) {
        <p>Added: <strong data-testid="import-added">{{ count() }}</strong></p>
      }
      @if (error()) {
        <p role="alert" data-testid="import-error">{{ error() }}</p>
      }
    </form>
  `,
})
export class ImportPeople {
  private readonly session = inject(Session);
  private readonly api = inject(PeopleApi);

  readonly added = output<PersonView[]>();

  protected readonly form = new FormGroup({
    text: new FormControl('', { nonNullable: true }),
  });
  protected readonly saving = signal(false);
  protected readonly error = signal('');
  protected readonly count = signal<number | null>(null);

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    // Each load of a sheet starts the box afresh.
    effect(() => {
      this.session.loaded();
      this.error.set('');
      this.count.set(null);
      this.form.reset({ text: '' }, { emitEvent: false });
    });
  }

  protected async submit(): Promise<void> {
    if (this.saving()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.error.set('');
    this.count.set(null);
    const text = this.form.getRawValue().text;
    if (!text.trim()) {
      this.error.set('Paste at least one line of name,grade,terms,contact.');
      return;
    }
    this.saving.set(true);
    try {
      const result = await this.api.importPeople(sheet.id, text);
      if (this.session.sheet()?.id === sheet.id) {
        this.count.set(result.added);
        this.added.emit(result.people);
      }
    } catch (e) {
      if (this.session.sheet()?.id !== sheet.id) return;
      const reason = e instanceof HttpErrorResponse && typeof e.error?.message === 'string' && e.error.message
        ? ' ' + e.error.message : '';
      this.error.set('No one was imported: a line was refused.' + reason);
    } finally {
      this.saving.set(false);
    }
  }
}
