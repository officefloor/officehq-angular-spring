import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, output, signal, untracked } from '@angular/core';
import { ImportView, PeopleApi } from '../../core/people';
import { Session } from '../../core/session';

// The import box: lines of name,grade,terms,contact each add a person as the person form would, or update
// the person a line names, all or none; how many were added and how many updated is shown.
@Component({
  selector: 'app-import-people',
  template: `
    <form (submit)="save($event)" aria-label="Import people">
      <label for="import-people">Import people, one per line: name,grade,terms,contact</label>
      <textarea
        id="import-people"
        data-testid="import-people"
        rows="4"
        [value]="text()"
        (input)="text.set(value($event)); error.set(null)"
      ></textarea>
      <button type="submit" data-testid="import-submit">Import people</button>
      @if (added() !== null) {
        <p role="status">
          Added <strong data-testid="import-added">{{ added() }}</strong> people, updated
          <strong data-testid="import-updated">{{ updated() }}</strong>.
        </p>
      }
      @if (error(); as message) {
        <p role="alert" data-testid="import-error">{{ message }}</p>
      }
    </form>
  `,
})
export class ImportPeople {
  private readonly session = inject(Session);
  private readonly api = inject(PeopleApi);

  /** The people imported: those added and those updated, each in the order of their lines. */
  readonly imported = output<ImportView>({ alias: 'added' });

  protected readonly text = signal('');
  protected readonly added = signal<number | null>(null);
  protected readonly updated = signal<number>(0);
  protected readonly error = signal<string | null>(null);
  /** A submit is waiting for its answer: a second submit meanwhile is ignored. */
  private busy = false;

  constructor() {
    // A sheet load starts the box afresh.
    effect(() => {
      this.session.sheet();
      this.session.loads();
      untracked(() => {
        this.text.set('');
        this.added.set(null);
        this.error.set(null);
      });
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLTextAreaElement).value;
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.error.set(null);
    this.added.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const text = this.text();
    if (!text.trim()) {
      this.error.set('Paste at least one line of name,grade,terms,contact.');
      return;
    }
    this.busy = true;
    try {
      const imported = await this.api.importPeople(sheet, text);
      if (this.session.sheet() !== sheet) return;
      this.imported.emit(imported);
      this.updated.set(imported.updated.length);
      this.added.set(imported.added.length);
      this.text.set('');
    } catch (e) {
      const reason = e instanceof HttpErrorResponse && (e.status === 400 || e.status === 409) && typeof e.error?.detail === 'string' ? e.error.detail : null;
      this.error.set(reason ?? 'The people could not be imported; nothing was changed.');
    } finally {
      this.busy = false;
    }
  }
}
