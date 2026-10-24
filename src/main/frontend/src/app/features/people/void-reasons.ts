import { Component, effect, inject, signal } from '@angular/core';
import { Session } from '../../core/session';
import { VoidReasonsService } from './void-reasons.service';

// The sheet's void reasons, one line each, in the order they were added, and the entry by which one is
// added (a blank one, or one already on the list, is refused).
@Component({
  selector: 'app-void-reasons',
  template: `
    <section aria-labelledby="void-reasons-heading">
      <h2 id="void-reasons-heading">Void reasons</h2>
      <form (submit)="submit($event)" aria-label="Void reason" data-testid="void-reason-form">
        <label for="void-reason-text">Reason</label>
        <input id="void-reason-text" data-testid="void-reason-text" [value]="text()" (input)="text.set(value($event)); error.set(null)" />
        <button type="submit" data-testid="void-reason-add">Add void reason</button>
        @if (error(); as message) {
          <p role="alert" class="error" data-testid="void-reason-error">{{ message }}</p>
        }
      </form>
      @if (service.reasons().length > 0) {
        <ul data-testid="void-reason-list">
          @for (r of service.reasons(); track r.id) {
            <li [attr.data-testid]="'void-reason-row-' + r.reason">{{ r.reason }}</li>
          }
        </ul>
      }
    </section>
  `,
  styles: `
    form {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      align-items: center;
      margin-bottom: 0.5rem;
    }
    .error {
      color: #a40000;
      margin: 0;
    }
  `,
})
export class VoidReasons {
  private readonly session = inject(Session);
  protected readonly service = inject(VoidReasonsService);

  protected readonly text = signal('');
  protected readonly error = signal<string | null>(null);
  private saving = false;

  constructor() {
    effect(() => {
      this.session.sheetId();
      this.session.loads();
      this.text.set('');
      this.error.set(null);
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.saving) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const reason = this.text().trim();
    if (reason === '') {
      this.error.set('Type a reason.');
      return;
    }
    if (this.service.reasons().some((r) => r.reason === reason)) {
      this.error.set('That reason is already on the list.');
      return;
    }
    this.saving = true;
    try {
      await this.service.add(sheet, reason);
      if (this.session.sheetId() === sheet) this.text.set('');
    } catch {
      if (this.session.sheetId() === sheet) this.error.set('That reason is already on the list, or could not be added.');
    } finally {
      this.saving = false;
    }
  }
}
