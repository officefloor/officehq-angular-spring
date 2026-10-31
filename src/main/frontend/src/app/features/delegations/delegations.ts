import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { EMPTY, switchMap } from 'rxjs';
import { Delegations } from '../../core/delegations';
import { Sheets } from '../../core/sheet';

/** A date the form accepts: yyyy-mm-dd. */
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

// A steward delegates the steward role to a user for a from-date to a to-date, inclusive, by the sheet's today.
@Component({
  selector: 'app-delegations',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="delegations-heading">
      <h3 id="delegations-heading">Delegations</h3>
      <p>While the sheet's today is in its range, a delegated user acts as a steward.</p>
      <ul aria-label="Delegations made">
        @for (d of delegations.all(); track d.id) {
          <li data-testid="delegate-line">{{ d.by }} to {{ d.user }}: {{ d.from }} to {{ d.to }}</li>
        }
      </ul>

      <form [formGroup]="form" (ngSubmit)="delegate()" aria-label="Delegate the steward role">
        <label for="delegate-user">User name</label>
        <input id="delegate-user" data-testid="delegate-user" formControlName="user" autocomplete="off" />
        <label for="delegate-from">From (blank: today)</label>
        <input id="delegate-from" data-testid="delegate-from" formControlName="from" autocomplete="off" placeholder="yyyy-mm-dd" />
        <label for="delegate-to">To (blank: today)</label>
        <input id="delegate-to" data-testid="delegate-to" formControlName="to" autocomplete="off" placeholder="yyyy-mm-dd" />
        <button type="submit" data-testid="delegate-submit" [disabled]="saving()">Delegate</button>
        @if (error(); as err) {
          <p role="alert" data-testid="delegate-error">{{ err }}</p>
        }
      </form>
    </section>
  `,
})
export class DelegationsPanel {
  protected readonly delegations = inject(Delegations);
  private readonly sheets = inject(Sheets);

  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly form = new FormGroup({
    user: new FormControl('', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    // Each (re)load of a sheet re-reads its delegations from the store.
    toObservable(this.sheets.loaded)
      .pipe(
        switchMap((sheet) => {
          this.error.set(null);
          return this.delegations.load(sheet?.id ?? null) ?? EMPTY;
        }),
        takeUntilDestroyed(),
      )
      .subscribe({ error: () => this.error.set('Could not read the delegations.') });
    // An error is removed as soon as its form is changed.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.error.set(null));
  }

  protected delegate(): void {
    // A second submit before the first has answered is ignored, so it stores one delegation.
    if (this.saving()) return;
    this.error.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const user = this.form.controls.user.value.trim();
    const from = this.form.controls.from.value.trim();
    const to = this.form.controls.to.value.trim();
    if (!user) {
      this.error.set('Type the user name to delegate the steward role to.');
      return;
    }
    if ((from && !DATE_PATTERN.test(from)) || (to && !DATE_PATTERN.test(to))) {
      this.error.set('Type each date as yyyy-mm-dd, or leave it blank for the sheet\'s today.');
      return;
    }
    this.saving.set(true);
    this.delegations.delegate(sheet.id, { user, from: from || null, to: to || null }).subscribe({
      next: () => {
        this.saving.set(false);
        this.form.reset({ user: '', from: '', to: '' }, { emitEvent: false });
      },
      error: () => {
        this.saving.set(false);
        this.error.set('Could not delegate: only a steward can, and the to-date must not be before the from-date.');
      },
    });
  }
}
