import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, output, signal, untracked } from '@angular/core';
import { ClaimsApi, ClaimView } from '../../core/claims';
import { Session } from '../../core/session';

// The paste box: lines of name,amount,date,note each add a DRAFT claim for the named person, all or
// none; how many were added is shown.
@Component({
  selector: 'app-paste-claims',
  template: `
    <form (submit)="save($event)" aria-label="Paste claims">
      <label for="paste-claims">Paste claims, one per line: name,amount,date,note</label>
      <textarea
        id="paste-claims"
        data-testid="paste-claims"
        rows="4"
        [value]="text()"
        (input)="text.set(value($event)); error.set(null)"
      ></textarea>
      <button type="submit" data-testid="paste-submit">Add pasted claims</button>
      @if (added() !== null) {
        <p role="status">Added <strong data-testid="paste-added">{{ added() }}</strong> claims.</p>
      }
      @if (error(); as message) {
        <p role="alert" data-testid="paste-error">{{ message }}</p>
      }
    </form>
  `,
})
export class PasteClaims {
  private readonly session = inject(Session);
  private readonly api = inject(ClaimsApi);

  /** The claims pasted, in the order they were added. */
  readonly pasted = output<ClaimView[]>({ alias: 'added' });

  protected readonly text = signal('');
  protected readonly added = signal<number | null>(null);
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
      this.error.set('Paste at least one line of name,amount,date,note.');
      return;
    }
    this.busy = true;
    try {
      const claims = await this.api.paste(sheet, text);
      if (this.session.sheet() !== sheet) return;
      this.pasted.emit(claims);
      this.added.set(claims.length);
      this.text.set('');
    } catch (e) {
      const reason = e instanceof HttpErrorResponse && e.status === 400 && typeof e.error?.detail === 'string' ? e.error.detail : null;
      this.error.set(reason ?? 'The pasted claims could not be added; nothing was added.');
    } finally {
      this.busy = false;
    }
  }
}
