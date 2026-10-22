import { Component, effect, inject, signal, untracked } from '@angular/core';
import { VoidReasons } from '../../core/void-reasons';
import { Session } from '../../core/session';

// The sheet's void reasons, in the order they were added, and a form to add one; a blank reason or
// one already on the list is refused. While the list is not empty, a claim is voided only with one.
@Component({
  selector: 'app-void-reasons',
  template: `
    <section aria-labelledby="void-reasons-heading">
      <h2 id="void-reasons-heading">Void reasons</h2>
      <form (submit)="add($event)" aria-label="Add a void reason">
        <label for="void-reason-text">Reason</label>
        <input
          id="void-reason-text"
          data-testid="void-reason-text"
          [value]="text()"
          (input)="text.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="void-reason-add">Add void reason</button>
        @if (error(); as message) {
          <p role="alert" data-testid="void-reason-error">{{ message }}</p>
        }
      </form>
      <ul aria-label="Void reasons">
        @for (r of reasons.list(); track r.id) {
          <li [attr.data-testid]="'void-reason-row-' + r.reason">{{ r.reason }}</li>
        }
      </ul>
    </section>
  `,
})
export class VoidReasonsList {
  private readonly session = inject(Session);
  protected readonly reasons = inject(VoidReasons);

  protected readonly text = signal('');
  protected readonly error = signal<string | null>(null);
  /** A submit is waiting for its answer: a second submit meanwhile is ignored. */
  private busy = false;

  constructor() {
    // A sheet load clears what was being entered on the last one.
    effect(() => {
      this.session.sheet();
      this.session.loads();
      untracked(() => this.error.set(null));
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async add(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const reason = this.text().trim();
    if (reason === '') {
      this.error.set('Type a reason to add.');
      return;
    }
    if (this.reasons.list().some((r) => r.reason === reason)) {
      this.error.set(`"${reason}" is already on the list.`);
      return;
    }
    this.busy = true;
    try {
      await this.reasons.add(sheet, reason);
      this.text.set('');
    } catch {
      this.error.set(`"${reason}" could not be added: it may already be on the list.`);
    } finally {
      this.busy = false;
    }
  }
}
