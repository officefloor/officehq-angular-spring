import { Component, effect, inject, signal } from '@angular/core';
import { Session } from '../../core/session';
import { Delegation, DelegationsService } from './delegations.service';

const DATE = /^\d{4}-\d{2}-\d{2}$/;

// The form by which a steward delegates the steward role to a user from a from-date (blank for the
// sheet's today) to a to-date, inclusive, and the delegations made on the sheet, one line each.
// Read from the store every time a sheet is loaded.
@Component({
  selector: 'app-delegations',
  template: `
    <section aria-labelledby="delegations-heading">
      <h2 id="delegations-heading">Delegations</h2>
      <form (submit)="submit($event)" aria-label="Delegate" data-testid="delegate-form">
        <label for="delegate-user">User</label>
        <input id="delegate-user" data-testid="delegate-user" [value]="user()" (input)="user.set(value($event)); error.set(null)" />
        <label for="delegate-from">From</label>
        <input id="delegate-from" data-testid="delegate-from" [value]="from()" (input)="from.set(value($event)); error.set(null)" />
        <label for="delegate-to">To</label>
        <input id="delegate-to" data-testid="delegate-to" [value]="to()" (input)="to.set(value($event)); error.set(null)" />
        <button type="submit" data-testid="delegate-submit">Delegate</button>
        @if (error(); as message) {
          <p role="alert" class="error" data-testid="delegate-error">{{ message }}</p>
        }
      </form>
      @if (delegations().length > 0) {
        <ul data-testid="delegate-list">
          @for (d of delegations(); track d.id) {
            <li data-testid="delegate-line">{{ d.user }} for {{ d.delegator }} from {{ d.from }} to {{ d.to }}</li>
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
export class Delegations {
  private readonly session = inject(Session);
  private readonly service = inject(DelegationsService);

  protected readonly delegations = signal<Delegation[]>([]);
  protected readonly user = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly error = signal<string | null>(null);
  private saving = false;

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      this.session.loads();
      this.delegations.set([]);
      this.user.set('');
      this.from.set('');
      this.to.set('');
      this.error.set(null);
      if (sheet) void this.read(sheet);
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
    const user = this.user().trim();
    const from = this.from().trim();
    const to = this.to().trim();
    if (user === '') {
      this.error.set('Type a user name.');
      return;
    }
    if ((from !== '' && !DATE.test(from)) || !DATE.test(to)) {
      this.error.set('Type the dates as YYYY-MM-DD; a to-date is needed.');
      return;
    }
    if (from !== '' && to < from) {
      this.error.set('The to-date is before the from-date.');
      return;
    }
    this.saving = true;
    try {
      const all = await this.service.delegate(sheet, user, from === '' ? null : from, to);
      if (this.session.sheetId() !== sheet) return;
      this.delegations.set(all);
      this.user.set('');
      this.from.set('');
      this.to.set('');
    } catch {
      this.error.set('Only a steward can delegate, and the to-date cannot be before the from-date.');
    } finally {
      this.saving = false;
    }
  }

  private async read(sheet: string): Promise<void> {
    try {
      const all = await this.service.list(sheet);
      if (this.session.sheetId() === sheet) this.delegations.set(all);
    } catch {
      // The delegations could not be read; they show as none.
    }
  }
}
