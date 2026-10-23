import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, output, signal, untracked } from '@angular/core';
import { PeriodsApi } from '../../core/periods';
import { Session } from '../../core/session';

// The opening balances import: lines of name,period,amount each add the amount to the person's carried
// figure for the period, all or none; how many lines went through is shown.
@Component({
  selector: 'app-import-openings',
  template: `
    <form (submit)="save($event)" aria-label="Import opening balances">
      <label for="opening-import">Paste opening balances, one per line: name,period,amount</label>
      <textarea
        id="opening-import"
        data-testid="opening-import"
        rows="4"
        [value]="text()"
        (input)="text.set(value($event)); error.set(null)"
      ></textarea>
      <button type="submit" data-testid="opening-import-submit">Import opening balances</button>
      @if (done() !== null) {
        <p role="status"><strong data-testid="opening-import-done">{{ done() }}</strong> lines went through.</p>
      }
      @if (error(); as message) {
        <p role="alert" data-testid="opening-error">{{ message }}</p>
      }
    </form>
  `,
})
export class ImportOpenings {
  private readonly session = inject(Session);
  private readonly api = inject(PeriodsApi);

  /** Opening balances were imported. */
  readonly imported = output<void>();

  protected readonly text = signal('');
  protected readonly done = signal<number | null>(null);
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
        this.done.set(null);
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
    this.done.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const text = this.text();
    if (!text.trim()) {
      this.error.set('Paste at least one line of name,period,amount.');
      return;
    }
    this.busy = true;
    try {
      const lines = await this.api.importOpenings(sheet, text);
      if (this.session.sheet() !== sheet) return;
      this.done.set(lines);
      this.text.set('');
      this.imported.emit();
    } catch (e) {
      const reason = e instanceof HttpErrorResponse && e.status >= 400 && e.status < 500 && typeof e.error?.detail === 'string' ? e.error.detail : null;
      this.error.set(reason ?? 'The opening balances could not be imported; nothing was changed.');
    } finally {
      this.busy = false;
    }
  }
}
