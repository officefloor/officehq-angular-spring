import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { Session } from '../../core/session';
import { VoidReasons as VoidReasonList } from '../../core/void-reasons';

// The sheet's void reasons and the entry that adds one; a blank reason, or one already on the
// list, is refused. While the list is not empty, voiding a claim needs a reason chosen from it.
@Component({
  selector: 'app-void-reasons',
  template: `
    <section aria-labelledby="void-reasons-heading">
      <h2 id="void-reasons-heading">Void reasons</h2>
      <form aria-label="Void reason" (submit)="add($event)">
        <label for="void-reason-text">Reason</label>
        <input
          id="void-reason-text"
          data-testid="void-reason-text"
          [value]="textInput()"
          (input)="textInput.set($any($event.target).value); error.set(null)"
        />
        <button type="submit" data-testid="void-reason-add">Add reason</button>
        @if (error() ?? reasons.loadError(); as err) {
          <p role="alert" data-testid="void-reason-error">{{ err }}</p>
        }
      </form>
      <ul data-testid="void-reasons-list" aria-label="Void reasons">
        @for (r of reasons.list(); track r.id) {
          <li [attr.data-testid]="'void-reason-row-' + r.reason">{{ r.reason }}</li>
        }
      </ul>
    </section>
  `,
})
export class VoidReasons {
  private readonly session = inject(Session);
  protected readonly reasons = inject(VoidReasonList);

  protected readonly textInput = signal('');
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;

  constructor() {
    effect(() => {
      this.session.sheet();
      this.session.loads();
      this.textInput.set('');
      this.error.set(null);
    });
  }

  protected async add(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const reason = this.textInput().trim();
    if (!reason) {
      this.error.set('Enter a void reason.');
      return;
    }
    if (this.reasons.list().some((r) => r.reason === reason)) {
      this.error.set('That reason is already on the list.');
      return;
    }
    const sheet = this.session.sheet();
    this.submitting = true;
    try {
      await this.reasons.add(reason);
      if (this.session.sheet() === sheet) this.textInput.set('');
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 409
          ? 'That reason is already on the list.'
          : e instanceof HttpErrorResponse && e.status === 403
            ? 'Your role may not add void reasons.'
            : 'Could not add this void reason.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
