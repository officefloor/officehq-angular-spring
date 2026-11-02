import { Component, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { AMOUNT, Claim, Claims } from '../../core/claims';

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
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <button type="button" data-testid="claim-edit" (click)="open(c)"
            [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date">Edit</button>
          @if (editing() === c.id) {
            <button type="button" data-testid="claim-cancel" (click)="cancel()">Cancel</button>
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
  /** A claim added or saved through the form. */
  readonly saved = output<Claim>();

  /** The id of the claim whose editor is open, or null when the form adds a claim. */
  protected readonly editing = signal<number | null>(null);
  protected readonly error = signal('');
  protected readonly form = new FormGroup({
    amount: new FormControl('', { nonNullable: true }),
    date: new FormControl('', { nonNullable: true }),
    note: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  protected open(c: Claim): void {
    this.editing.set(c.id);
    this.form.setValue({ amount: c.amount, date: c.date, note: c.note }, { emitEvent: false });
    this.error.set('');
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
