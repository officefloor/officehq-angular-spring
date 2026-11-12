import { Component, inject, signal } from '@angular/core';
import { VoidReasons } from '../../core/void-reasons';

// The sheet's void reasons: a form adds one (a blank one, or one already listed, is refused), and the list
// shows them. While the list has any, a claim is voided with one chosen from it.
@Component({
  selector: 'app-void-reasons',
  template: `
    <section aria-label="Void reasons" data-testid="void-reasons">
      <h2>Void reasons</h2>
      <form (submit)="add($event)" data-testid="void-reason-form">
        <label for="void-reason-text">Reason</label>
        <input
          id="void-reason-text"
          data-testid="void-reason-text"
          [value]="reason()"
          (input)="reason.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="void-reason-add" [disabled]="saving()">Add void reason</button>
        @if (error(); as err) {
          <p role="alert" data-testid="void-reason-error">{{ err }}</p>
        }
      </form>
      <ul data-testid="void-reason-list" aria-label="Void reasons">
        @for (r of reasons.reasons(); track r.id) {
          <li [attr.data-testid]="'void-reason-row-' + r.reason">{{ r.reason }}</li>
        }
      </ul>
    </section>
  `,
})
export class VoidReasonsPanel {
  protected readonly reasons = inject(VoidReasons);
  protected readonly reason = signal('');
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async add(e: Event): Promise<void> {
    e.preventDefault();
    if (this.saving()) return; // a second submit before the first has answered
    this.error.set(null);
    this.saving.set(true);
    try {
      const err = await this.reasons.add(this.reason());
      if (err) this.error.set(err);
      else this.reason.set('');
    } finally {
      this.saving.set(false);
    }
  }
}
