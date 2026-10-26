import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

interface DelegationView {
  id: number;
  user: string;
  by: string;
  from: string;
  to: string;
}

// The delegations of the steward role on the active sheet: a steward delegates it to a user from a from-date to a
// to-date, inclusive (each blank for the sheet's today); while the sheet's today is inside, the user acts as a steward.
@Component({
  selector: 'app-delegation',
  template: `
    <section aria-label="Delegations">
      <h2>Delegations</h2>
      <form (submit)="save($event)" aria-label="Delegate the steward role">
        <label for="delegate-user">User name</label>
        <input
          id="delegate-user"
          data-testid="delegate-user"
          [value]="user()"
          (input)="user.set(value($event)); error.set(null)"
        />
        <label for="delegate-from">From (blank for today)</label>
        <input
          id="delegate-from"
          type="date"
          data-testid="delegate-from"
          [value]="from()"
          (input)="from.set(value($event)); error.set(null)"
        />
        <label for="delegate-to">To (blank for today)</label>
        <input
          id="delegate-to"
          type="date"
          data-testid="delegate-to"
          [value]="to()"
          (input)="to.set(value($event)); error.set(null)"
        />
        <button type="submit" data-testid="delegate-submit">Delegate</button>
        @if (error(); as err) {
          <p role="alert" data-testid="delegate-error">{{ err }}</p>
        }
      </form>

      <ul aria-label="Delegations made">
        @for (d of delegations(); track d.id) {
          <li data-testid="delegate-line">
            <span data-testid="delegate-line-user">{{ d.user }}</span> acts for
            <span data-testid="delegate-line-by">{{ d.by }}</span> from
            <span data-testid="delegate-line-from">{{ d.from }}</span> to
            <span data-testid="delegate-line-to">{{ d.to }}</span>
          </li>
        }
      </ul>
    </section>
  `,
})
export class Delegation {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly delegations = signal<readonly DelegationView[]>([]);
  protected readonly user = signal('');
  protected readonly from = signal('');
  protected readonly to = signal('');
  protected readonly error = signal<string | null>(null);
  // A submit in flight: a second one before it answers is ignored.
  private submitting = false;

  constructor() {
    // Re-read the delegations from the store each time a sheet is loaded.
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.error.set(null);
        this.delegations.set([]);
        if (id) this.load(id).catch(() => this.error.set('Could not load the delegations.'));
      });
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  private async load(sheet: string): Promise<void> {
    const l = await firstValueFrom(
      this.http.get<DelegationView[]>(`/api/sheets/${encodeURIComponent(sheet)}/delegations`),
    );
    if (this.session.sheetId() === sheet) this.delegations.set(l);
  }

  protected async save(e: Event): Promise<void> {
    e.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const user = this.user().trim();
    const from = this.from() || null;
    const to = this.to() || null;
    if (!user) {
      this.error.set('Enter a user name.');
      return;
    }
    const today = this.session.sheetToday();
    const start = from ?? today;
    const end = to ?? today;
    if (start && end && end < start) {
      this.error.set('The to-date cannot be before the from-date.');
      return;
    }
    this.submitting = true;
    try {
      const d = await firstValueFrom(
        this.http.post<DelegationView>(`/api/sheets/${encodeURIComponent(sheet)}/delegations`, { user, from, to }),
      );
      if (this.session.sheetId() === sheet) this.delegations.update((l) => [...l, d]);
    } catch (err) {
      this.error.set(
        err instanceof HttpErrorResponse && err.status === 403
          ? 'Only a steward can delegate the steward role.'
          : 'Could not delegate: the to-date cannot be before the from-date.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
