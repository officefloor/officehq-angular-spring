import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { People, Person } from '../../core/people';

/** The message for a refused import: the server names the line that failed. */
function refusal(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 403) return 'A viewer may change nothing.';
  if (e instanceof HttpErrorResponse && typeof e.error?.message === 'string' && e.error.message) return e.error.message;
  return 'The people could not be imported.';
}

// The import box: lines of name,grade,terms,contact each add a person as the person form would,
// or update the person the name matches (by name or alias), all or none; a blank grade is the
// sheet's default grade.
@Component({
  selector: 'app-import-people-box',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()" aria-label="Import people">
      <label for="import-people">Import people (name,grade,terms,contact per line)</label>
      <textarea id="import-people" data-testid="import-people" formControlName="text" rows="4"></textarea>
      <button type="submit" data-testid="import-submit">Import people</button>
      @if (added() !== null) {
        <p role="status">
          Added <strong data-testid="import-added">{{ added() }}</strong>,
          updated <strong data-testid="import-updated">{{ updated() }}</strong>
        </p>
      }
      @if (error()) {
        <p role="alert" data-testid="import-error">{{ error() }}</p>
      }
    </form>
  `,
})
export class ImportPeopleBox {
  private readonly api = inject(People);

  readonly imported = output<Person[]>();

  protected readonly form = new FormGroup({ text: new FormControl('', { nonNullable: true }) });
  protected readonly error = signal('');
  protected readonly added = signal<number | null>(null);
  protected readonly updated = signal(0);
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    this.added.set(null);
    const { text } = this.form.getRawValue();
    if (!text.trim()) {
      this.error.set('Paste at least one line.');
      return;
    }
    this.submitting = true;
    try {
      const { added, updated, people } = await this.api.importPeople(text);
      this.updated.set(updated);
      this.added.set(added);
      this.imported.emit(people);
    } catch (e) {
      this.error.set(refusal(e));
    } finally {
      this.submitting = false;
    }
  }
}
