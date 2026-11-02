import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Delegation, Delegations } from '../../core/delegations';
import { Session } from '../../core/session';

const DAY = /^\d{4}-\d{2}-\d{2}$/;

// The steward role delegated to a user for a range of days, inclusive, by the sheet's today, and the
// form a steward delegates it with. While the sheet's today is inside the range the user acts as a
// steward.
@Component({
  selector: 'app-delegation-form',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="delegations-heading">
      <h3 id="delegations-heading">Delegations</h3>
      <form data-testid="delegate-form" [formGroup]="form" (ngSubmit)="submit()">
        <label for="delegate-user">User</label>
        <input id="delegate-user" data-testid="delegate-user" formControlName="user" />
        <label for="delegate-from">From</label>
        <input id="delegate-from" type="date" data-testid="delegate-from" formControlName="from" />
        <label for="delegate-to">To</label>
        <input id="delegate-to" type="date" data-testid="delegate-to" formControlName="to" />
        <button type="submit" data-testid="delegate-submit">Delegate steward role</button>
        @if (error()) {
          <p role="alert" data-testid="delegate-error">{{ error() }}</p>
        }
      </form>
      <ul data-testid="delegations">
        @for (d of delegations(); track d.id) {
          <li data-testid="delegate-line">{{ d.by }} delegated steward to {{ d.user }} from {{ d.from }} to {{ d.to }}</li>
        }
      </ul>
    </section>
  `,
})
export class DelegationForm {
  private readonly api = inject(Delegations);
  private readonly session = inject(Session);

  protected readonly delegations = signal<Delegation[]>([]);
  protected readonly error = signal('');
  protected readonly form = new FormGroup({
    user: new FormControl('', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
  });
  private submitting = false;
  private reads = 0;

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    effect(() => {
      this.session.loads();
      const sheetId = this.session.sheetId();
      this.delegations.set([]);
      if (sheetId) void this.reload(sheetId);
    });
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    const { user, from, to } = this.form.getRawValue();
    if (user.trim() === '') {
      this.error.set('Enter a user name.');
      return;
    }
    if ((from !== '' && !DAY.test(from)) || (to !== '' && !DAY.test(to))) {
      this.error.set('Enter the days as yyyy-mm-dd.');
      return;
    }
    if (from !== '' && to !== '' && to < from) {
      this.error.set('A delegation cannot end before it starts.');
      return;
    }
    this.submitting = true;
    try {
      const saved = await this.api.delegate(user.trim(), from || null, to || null);
      this.delegations.update((ds) => [...ds, saved]);
      this.form.reset({ user: '', from: '', to: '' }, { emitEvent: false });
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 403
          ? 'Only a steward may delegate the steward role.'
          : 'The steward role could not be delegated.',
      );
    } finally {
      this.submitting = false;
    }
  }

  private async reload(sheetId: string): Promise<void> {
    const read = ++this.reads;
    try {
      const delegations = await this.api.list(sheetId);
      if (read === this.reads) this.delegations.set(delegations);
    } catch {
      // the list stays empty; the next load reads it again
    }
  }
}
