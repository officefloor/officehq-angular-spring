import { Component, inject, signal } from '@angular/core';
import { FigurePipe } from '../../core/locale';
import { VoidReasons } from '../../core/void-reasons';

// The sheet's void reasons: while there are any, a claim is voided with one of them.
@Component({
  selector: 'app-void-reasons',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="void-reasons-heading">
      <h2 id="void-reasons-heading">Void reasons</h2>
      <form (submit)="add($event)" aria-labelledby="void-reasons-heading">
        <label for="void-reason-text">Void reason</label>
        <input
          id="void-reason-text"
          data-testid="void-reason-text"
          [value]="reason()"
          (input)="reason.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="void-reason-add">Add void reason</button>
        @if (error(); as message) {
          <p role="alert" data-testid="void-reason-error">{{ message | fig }}</p>
        }
      </form>
      <ul aria-label="Void reasons">
        @for (r of reasons.list(); track r.id) {
          <li [attr.data-testid]="'void-reason-row-' + r.reason">{{ r.reason | fig }}</li>
        }
      </ul>
    </section>
  `,
})
export class VoidReasonsList {
  protected readonly reasons = inject(VoidReasons);

  protected readonly reason = signal('');
  protected readonly error = signal<string | null>(null);
  /** Whether an add is waiting for its answer: a second add meanwhile is ignored. */
  private adding = false;

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async add(event: Event): Promise<void> {
    event.preventDefault();
    if (this.adding) return;
    this.error.set(null);
    const reason = this.reason().trim();
    if (reason === '') {
      this.error.set('Type a void reason.');
      return;
    }
    if (this.reasons.list().some((r) => r.reason === reason)) {
      this.error.set(`${reason} is already a void reason.`);
      return;
    }
    this.adding = true;
    try {
      await this.reasons.add(reason);
      this.reason.set('');
    } catch {
      this.error.set(`The void reason could not be added: ${reason} may already be one.`);
    } finally {
      this.adding = false;
    }
  }
}
