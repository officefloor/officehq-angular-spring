import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

// A steward's delegations of the steward role: while the sheet's today is from the from-date to
// the to-date, inclusive, the user acts as a steward, and each change they make is recorded as
// made for the steward who delegated. Only a steward in their own right may delegate.
interface Delegation {
  id: number;
  user: string;
  from: string;
  to: string;
  steward: string;
}

@Component({
  selector: 'app-delegations',
  template: `
    <form aria-label="Delegate the steward role" (submit)="save($event)">
      <label for="delegate-user">Delegate to user</label>
      <input
        id="delegate-user"
        data-testid="delegate-user"
        [value]="userInput()"
        (input)="userInput.set($any($event.target).value); error.set(null)"
      />
      <label for="delegate-from">From</label>
      <input
        id="delegate-from"
        type="date"
        data-testid="delegate-from"
        [value]="fromInput()"
        (input)="fromInput.set($any($event.target).value); error.set(null)"
      />
      <label for="delegate-to">To</label>
      <input
        id="delegate-to"
        type="date"
        data-testid="delegate-to"
        [value]="toInput()"
        (input)="toInput.set($any($event.target).value); error.set(null)"
      />
      <button type="submit" data-testid="delegate-submit">Delegate</button>
      @if (error(); as err) {
        <p role="alert" data-testid="delegate-error">{{ err }}</p>
      }
    </form>
    <ul aria-label="Delegations" data-testid="delegations-list">
      @for (d of list(); track d.id) {
        <li data-testid="delegate-line">
          <span data-testid="delegate-line-user">{{ d.user }}</span> acts as a steward for
          <span data-testid="delegate-line-steward">{{ d.steward }}</span> from
          <span data-testid="delegate-line-from">{{ d.from }}</span> to
          <span data-testid="delegate-line-to">{{ d.to }}</span>
        </li>
      }
    </ul>
  `,
})
export class Delegations {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly list = signal<Delegation[]>([]);
  protected readonly userInput = signal('');
  protected readonly fromInput = signal('');
  protected readonly toInput = signal('');
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;

  constructor() {
    // Re-read the sheet's delegations from the store every time a sheet is loaded.
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.list.set([]);
      this.userInput.set('');
      this.fromInput.set('');
      this.toInput.set('');
      this.error.set(null);
      if (sheet) {
        void this.fetch(sheet);
      }
    });
  }

  private async fetch(sheet: string): Promise<void> {
    try {
      const list = await firstValueFrom(this.http.post<Delegation[]>('/api/roles/delegations', { sheet }));
      if (this.session.sheet() === sheet) this.list.set(list);
    } catch {
      this.error.set('Could not read the delegations on this sheet.');
    }
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    const name = this.userInput().trim();
    const from = this.fromInput().trim() || null;
    const to = this.toInput().trim();
    if (!name) {
      this.error.set('Enter a user name.');
      return;
    }
    if (!to) {
      this.error.set('Enter a to-date.');
      return;
    }
    if (from && to < from) {
      this.error.set('The to-date is before the from-date.');
      return;
    }
    this.submitting = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<Delegation>('/api/roles/delegate', { sheet, user: this.session.user(), name, from, to }),
      );
      if (this.session.sheet() !== sheet) return;
      this.list.update((list) => [...list, saved]);
      this.userInput.set('');
      this.fromInput.set('');
      this.toInput.set('');
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 403
          ? 'Only a steward can delegate the steward role.'
          : 'Could not delegate the steward role.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
