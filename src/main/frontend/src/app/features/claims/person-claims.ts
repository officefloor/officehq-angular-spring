import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { AMOUNT, Claim, ClaimAction, Claims } from '../../core/claims';
import { due } from '../../core/rates';

/** Why the server refused an action: the claim's status does not allow it. */
const REFUSALS: Record<ClaimAction, string> = {
  open: 'Only a draft claim can be opened.',
  settle: 'Only an open claim can be settled.',
  void: 'Only a draft or open claim can be voided.',
};

// A person's claims, newest first, and the form that adds a claim for them or, once a claim's
// editor is opened, edits it.
@Component({
  selector: 'app-person-claims',
  imports: [ReactiveFormsModule],
  template: `
    <form data-testid="claim-form" [formGroup]="form" (ngSubmit)="submit()">
      <label [for]="'claim-form-amount-' + personId()">Amount</label>
      <input [id]="'claim-form-amount-' + personId()" data-testid="claim-form-amount" formControlName="amount"
        inputmode="decimal" />
      <label [for]="'claim-form-date-' + personId()">Date</label>
      <input [id]="'claim-form-date-' + personId()" type="date" data-testid="claim-form-date" formControlName="date" />
      <label [for]="'claim-form-note-' + personId()">Note</label>
      <input [id]="'claim-form-note-' + personId()" data-testid="claim-form-note" formControlName="note" />
      <button type="submit" data-testid="claim-form-submit">{{ editing() === null ? 'Add claim' : 'Save claim' }}</button>
      @if (error()) {
        <p role="alert" data-testid="claim-form-error">{{ error() }}</p>
      }
    </form>
    <ul data-testid="claims">
      @for (c of claims(); track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <span data-testid="claim-number">{{ c.number ?? '' }}</span>
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span data-testid="claim-due">{{ dueOf(c) }}</span>
          <span data-testid="claim-status">{{ c.status }}</span>
          <button type="button" data-testid="claim-open" (click)="act(c, 'open')"
            [attr.aria-label]="'Open claim of ' + c.amount + ' on ' + c.date">Open</button>
          <button type="button" data-testid="claim-settle" (click)="act(c, 'settle')"
            [attr.aria-label]="'Settle claim of ' + c.amount + ' on ' + c.date">Settle</button>
          <button type="button" data-testid="claim-void" (click)="act(c, 'void')"
            [attr.aria-label]="'Void claim of ' + c.amount + ' on ' + c.date">Void</button>
          <button type="button" data-testid="claim-edit" (click)="open(c)"
            [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date">Edit</button>
          @if (editing() === c.id) {
            <button type="button" data-testid="claim-cancel" (click)="cancel()">Cancel</button>
          }
          @if (claimError()?.id === c.id) {
            <p role="alert" data-testid="claim-error">{{ claimError()?.message }}</p>
          }
        </li>
      }
    </ul>
  `,
})
export class PersonClaims {
  private readonly api = inject(Claims);

  readonly personId = input.required<number>();
  /** This person's claims, newest first. */
  readonly claims = input.required<Claim[]>();
  /** The rate for this person's grade, or undefined when it has none. */
  readonly pct = input<string | undefined>();
  /** A claim added or saved through the form. */
  readonly saved = output<Claim>();

  /** The id of the claim whose editor is open, or null when the form adds a claim. */
  protected readonly editing = signal<number | null>(null);
  protected readonly error = signal('');
  /** The refusal of the last action taken on a claim's row, shown on that row. */
  protected readonly claimError = signal<{ id: number; message: string } | null>(null);
  protected readonly form = new FormGroup({
    amount: new FormControl('', { nonNullable: true }),
    date: new FormControl('', { nonNullable: true }),
    note: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  protected dueOf(c: Claim): string {
    return due(c.amount, this.pct()) ?? 'n/a';
  }

  protected open(c: Claim): void {
    this.claimError.set(null);
    this.editing.set(c.id);
    this.form.setValue({ amount: c.amount, date: c.date, note: c.note }, { emitEvent: false });
    this.error.set('');
  }

  protected async act(c: Claim, action: ClaimAction): Promise<void> {
    this.claimError.set(null);
    try {
      this.saved.emit(await this.api.act(c.id, action));
    } catch (e) {
      this.claimError.set({
        id: c.id,
        message: e instanceof HttpErrorResponse && e.status === 409 ? REFUSALS[action] : 'The claim could not be changed.',
      });
    }
  }

  protected cancel(): void {
    this.reset();
  }

  protected async submit(): Promise<void> {
    this.error.set('');
    const { amount, date, note } = this.form.getRawValue();
    if (!AMOUNT.test(amount.trim())) {
      this.error.set('Enter an amount of 0.00 or more, with up to two decimals.');
      return;
    }
    const day = date.trim();
    if (day !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(day)) {
      this.error.set('Enter a date, or leave it blank for the sheet\'s today.');
      return;
    }
    const form = { amount: amount.trim(), date: day === '' ? null : day, note };
    const id = this.editing();
    try {
      const claim = id === null ? await this.api.add(this.personId(), form) : await this.api.edit(id, form);
      this.saved.emit(claim);
      this.reset();
    } catch {
      this.error.set(id === null ? 'The claim could not be added.' : 'The claim could not be saved.');
    }
  }

  private reset(): void {
    this.editing.set(null);
    this.form.reset({ amount: '', date: '', note: '' }, { emitEvent: false });
    this.error.set('');
  }
}
