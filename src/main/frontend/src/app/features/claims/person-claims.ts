import { Component, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { dueCents } from '../rates/rates-api';
import { AMOUNT_PATTERN, Claim, ClaimsApi, cents, claimError, money } from './claims-api';

// One person's claims, newest first, and the one form that adds a claim or, with a claim's
// editor open, saves that claim.
@Component({
  selector: 'app-person-claims',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()"
      [attr.aria-label]="(editingId() === null ? 'Add claim for ' : 'Edit claim for ') + personName()">
      <label [for]="fieldId('amount')">Amount</label>
      <input [id]="fieldId('amount')" data-testid="claim-form-amount" formControlName="amount"
        inputmode="decimal" autocomplete="off" />
      <label [for]="fieldId('date')">Date</label>
      <input [id]="fieldId('date')" type="date" data-testid="claim-form-date" formControlName="date" />
      <label [for]="fieldId('note')">Note</label>
      <input [id]="fieldId('note')" data-testid="claim-form-note" formControlName="note" autocomplete="off" />
      <button type="submit" data-testid="claim-form-submit">{{ editingId() === null ? 'Add claim' : 'Save claim' }}</button>
      @if (error(); as err) {
        <p role="alert" data-testid="claim-form-error">{{ err }}</p>
      }
    </form>
    @if (claims().length > 0) {
      <ul [attr.aria-label]="'Claims of ' + personName()">
        @for (c of claims(); track c.id) {
          <li [attr.data-testid]="'claim-row-' + c.id">
            <span data-testid="claim-amount">{{ c.amount }}</span>
            <span data-testid="claim-date">{{ c.date }}</span>
            <span data-testid="claim-note">{{ c.note }}</span>
            <span>Due <span data-testid="claim-due">{{ dueOf(c) }}</span></span>
            <button type="button" data-testid="claim-edit" (click)="edit(c)"
              [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date">Edit</button>
            @if (editingId() === c.id) {
              <button type="button" data-testid="claim-cancel" (click)="reset()">Cancel</button>
            }
          </li>
        }
      </ul>
    }
  `,
})
export class PersonClaims {
  private readonly session = inject(Session);
  private readonly api = inject(ClaimsApi);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  /** This person's claims, newest first. */
  readonly claims = input.required<Claim[]>();
  /** The rate for this person's grade (a percent with two decimals), or null when it has none. */
  readonly pct = input.required<string | null>();
  /** A claim added or saved, for the sheet's list to take in. */
  readonly saved = output<Claim>();

  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly fieldId = (name: string) => `claim-form-${name}-${this.personId()}`;

  protected readonly form = new FormGroup({
    amount: new FormControl('', { nonNullable: true }),
    date: new FormControl('', { nonNullable: true }),
    note: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(null));
  }

  /** The claim's amount times the rate, divided by 100, rounded half up; n/a with no rate. */
  protected dueOf(c: Claim): string {
    const pct = this.pct();
    return pct === null ? 'n/a' : money(dueCents(cents(c.amount), cents(pct)));
  }

  protected edit(c: Claim): void {
    this.error.set(null);
    this.editingId.set(c.id);
    this.form.setValue({ amount: c.amount, date: c.date, note: c.note });
  }

  protected async submit(): Promise<void> {
    this.error.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const value = this.form.getRawValue();
    const amount = value.amount.trim();
    if (!AMOUNT_PATTERN.test(amount)) {
      this.error.set('Enter an amount of 0.00 or more with up to two decimals.');
      return;
    }
    const date = value.date.trim();
    if (date !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.error.set('Enter a date as yyyy-mm-dd.');
      return;
    }
    const form = { amount, date: date || null, note: value.note };
    const editing = this.editingId();
    try {
      const claim = editing === null
        ? await this.api.add(sheetId, this.personId(), form)
        : await this.api.update(sheetId, editing, form);
      if (this.session.sheetId() === sheetId) this.saved.emit(claim);
      this.reset();
    } catch (e) {
      this.error.set(claimError(e));
    }
  }

  protected reset(): void {
    this.editingId.set(null);
    this.form.reset({ amount: '', date: '', note: '' });
    this.error.set(null);
  }
}
