import { Component, effect, inject, signal, untracked } from '@angular/core';
import { DelegationView, DelegationsApi } from '../../core/delegations';
import { Session } from '../../core/session';

const DATE = /^\d{4}-\d{2}-\d{2}$/;

// A steward delegates the steward role to a user for a range of the sheet's today, both ends
// included; while the sheet's today is inside it, the user acts as a steward for them.
@Component({
  selector: 'app-delegation',
  template: `
    <section aria-labelledby="delegation-heading">
      <h2 id="delegation-heading">Delegate the steward role</h2>
      <form (submit)="delegate($event)" aria-label="Delegate the steward role">
        <label for="delegate-user">User</label>
        <input
          id="delegate-user"
          data-testid="delegate-user"
          [value]="user()"
          (input)="user.set(value($event)); error.set(null)"
        />
        <label for="delegate-from">From</label>
        <input
          id="delegate-from"
          type="date"
          data-testid="delegate-from"
          [value]="from()"
          (input)="from.set(value($event)); error.set(null)"
        />
        <label for="delegate-to">To</label>
        <input
          id="delegate-to"
          type="date"
          data-testid="delegate-to"
          [value]="to()"
          (input)="to.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="delegate-submit">Delegate</button>
        @if (error(); as message) {
          <p role="alert" data-testid="delegate-error">{{ message }}</p>
        }
      </form>
      <ul aria-label="Delegations">
        @for (d of delegations(); track d.id) {
          <li data-testid="delegate-line">{{ d.user }} acts as a steward for {{ d.steward }} from {{ d.from }} to {{ d.to }}</li>
        }
      </ul>
    </section>
  `,
})
export class Delegation {
  private readonly session = inject(Session);
  private readonly api = inject(DelegationsApi);

  protected readonly delegations = signal<DelegationView[]>([]);
  protected readonly user = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly error = signal<string | null>(null);
  /** A submit is waiting for its answer: a second submit meanwhile is ignored. */
  private busy = false;

  constructor() {
    // Every sheet load re-reads the sheet's delegations from the store.
    effect(() => {
      const sheet = this.session.sheet();
      const loads = this.session.loads();
      untracked(() => {
        this.delegations.set([]);
        this.error.set(null);
        if (sheet) void this.reload(sheet, loads);
      });
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async delegate(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const user = this.user().trim();
    const from = this.from().trim();
    const to = this.to().trim();
    if (!user) {
      this.error.set('Type the user to delegate the steward role to.');
      return;
    }
    if (!DATE.test(from) || !DATE.test(to)) {
      this.error.set('Pick a from-date and a to-date.');
      return;
    }
    if (to < from) {
      this.error.set('The to-date is before the from-date.');
      return;
    }
    this.busy = true;
    try {
      const saved = await this.api.delegate(sheet, { user, from, to });
      this.delegations.update((all) => [...all, saved]);
      this.user.set('');
      this.from.set('');
      this.to.set('');
    } catch {
      this.error.set(`The steward role could not be delegated to ${user}: only a steward delegates it.`);
    } finally {
      this.busy = false;
    }
  }

  private async reload(sheet: string, loads: number): Promise<void> {
    const delegations = await this.api.list(sheet);
    // A later load may have started while this one was in flight: only the latest is shown.
    if (this.session.sheet() === sheet && this.session.loads() === loads) this.delegations.set(delegations);
  }
}
