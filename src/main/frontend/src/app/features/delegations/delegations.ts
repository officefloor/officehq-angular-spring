import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, inject, signal } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { map, of, switchMap } from 'rxjs';
import { Session } from '../../core/session';
import { Delegation, DelegationsApi } from './delegations-api';

const DATE = /^\d{4}-\d{2}-\d{2}$/;

// The sheet's delegations of the steward role, each to a user from a date to a date, inclusive, by the sheet's
// today, and the form a steward delegates with.
@Component({
  selector: 'app-delegations',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="delegations-heading" data-testid="delegations">
      <h2 id="delegations-heading">Delegations of the steward role</h2>
      <form [formGroup]="form" (ngSubmit)="submit()" data-testid="delegate-form">
        <label for="delegate-user">User</label>
        <input id="delegate-user" data-testid="delegate-user" formControlName="user" />
        <label for="delegate-from">From</label>
        <input id="delegate-from" data-testid="delegate-from" type="date" formControlName="from" />
        <label for="delegate-to">To</label>
        <input id="delegate-to" data-testid="delegate-to" type="date" formControlName="to" />
        <button type="submit" data-testid="delegate-submit">Delegate</button>
      </form>
      @if (error(); as e) {
        <p role="alert" data-testid="delegate-error">{{ e }}</p>
      }
      <ul data-testid="delegate-list">
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
export class Delegations {
  private readonly session = inject(Session);
  private readonly api = inject(DelegationsApi);

  protected readonly delegations = signal<Delegation[]>([]);
  protected readonly error = signal<string | null>(null);
  private readonly busy = signal(false);
  protected readonly form = new FormGroup({
    user: new FormControl('', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(null));
    // Re-read the sheet's delegations from the store each time a sheet is loaded.
    const loaded = this.session.registerLoader(inject(DestroyRef));
    toObservable(this.session.sheetId)
      .pipe(switchMap((id) => (id ? this.api.list(id) : of([] as Delegation[])).pipe(map((ds) => ({ id, ds })))))
      .subscribe(({ id, ds }) => {
        this.delegations.set(ds);
        this.error.set(null);
        loaded(id);
      });
  }

  protected submit(): void {
    if (this.busy()) return;
    this.error.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) {
      this.error.set('Load a sheet first.');
      return;
    }
    const { user, from, to } = this.form.getRawValue();
    if (!user.trim()) {
      this.error.set('Type a user name.');
      return;
    }
    if (!DATE.test(from) || !DATE.test(to)) {
      this.error.set('Give a from-date and a to-date.');
      return;
    }
    if (to < from) {
      this.error.set('The to-date cannot be before the from-date.');
      return;
    }
    this.busy.set(true);
    this.api.delegate(sheetId, { user: user.trim(), from, to }).subscribe({
      next: (made) => {
        this.busy.set(false);
        if (this.session.sheetId() !== sheetId) return;
        this.delegations.update((ds) => [...ds, made]);
        this.form.reset({ user: '', from: '', to: '' }, { emitEvent: false });
      },
      error: (e: HttpErrorResponse) => {
        this.busy.set(false);
        this.error.set(
          e.status === 403
            ? 'Only a steward can delegate the steward role.'
            : e.status === 400
              ? 'Give a user, a from-date and a to-date no earlier than it.'
              : 'Could not delegate the steward role.',
        );
      },
    });
  }
}
