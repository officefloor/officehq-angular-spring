import { Component, computed, inject, input, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Claim, ClaimsService, cents, money } from '../../core/claims.service';
import { Grade } from '../../core/people.service';
import { RatesService, due } from '../../core/rates.service';

// A person's claims, newest first, each with its due (n/a when the person's grade has no rate), and a form that adds a claim or edits the one opened.
@Component({
  selector: 'app-person-claims',
  imports: [ReactiveFormsModule],
  template: `
    <ul [attr.aria-label]="'Claims of ' + personName()">
      @for (c of claims(); track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span>Due <span data-testid="claim-due">{{ dueOf(c) }}</span></span>
          <button type="button" data-testid="claim-edit" (click)="open(c)"
            [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date">Edit</button>
        </li>
      }
    </ul>
    <form [formGroup]="form" (ngSubmit)="submit()"
      [attr.aria-label]="(editing() === null ? 'Add claim for ' : 'Edit claim of ') + personName()">
      <label [for]="'claim-form-amount-' + personId()">Amount</label>
      <input [id]="'claim-form-amount-' + personId()" data-testid="claim-form-amount" formControlName="amount"
        inputmode="decimal" />
      <label [for]="'claim-form-date-' + personId()">Date (empty: today)</label>
      <input [id]="'claim-form-date-' + personId()" type="date" data-testid="claim-form-date" formControlName="date" />
      <label [for]="'claim-form-note-' + personId()">Note</label>
      <input [id]="'claim-form-note-' + personId()" data-testid="claim-form-note" formControlName="note" />
      <button type="submit" data-testid="claim-form-submit">{{ editing() === null ? 'Add claim' : 'Save claim' }}</button>
      @if (editing() !== null) {
        <button type="button" data-testid="claim-form-cancel" (click)="reset()">Cancel</button>
      }
      @if (error()) {
        <p role="alert" data-testid="claim-form-error">{{ error() }}</p>
      }
    </form>
  `,
})
export class PersonClaims {
  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  readonly grade = input.required<Grade>();
  private readonly service = inject(ClaimsService);
  private readonly rates = inject(RatesService);
  private readonly rate = computed(() => this.rates.byGrade().get(this.grade()));
  protected readonly claims = computed(() => this.service.byPerson().get(this.personId()) ?? []);
  protected readonly form = new FormGroup({
    amount: new FormControl('', { nonNullable: true }),
    date: new FormControl('', { nonNullable: true }),
    note: new FormControl('', { nonNullable: true }),
  });
  protected readonly editing = signal<number | null>(null);
  protected readonly error = signal('');

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
  }

  protected dueOf(c: Claim): string {
    const rate = this.rate();
    return rate === undefined ? 'n/a' : money(due(cents(c.amount), rate));
  }

  protected open(c: Claim): void {
    this.error.set('');
    this.editing.set(c.id);
    this.form.setValue({ amount: c.amount, date: c.date, note: c.note }, { emitEvent: false });
  }

  protected reset(): void {
    this.error.set('');
    this.editing.set(null);
    this.form.reset({ amount: '', date: '', note: '' }, { emitEvent: false });
  }

  protected submit(): void {
    this.error.set('');
    const { amount, date, note } = this.form.getRawValue();
    const a = amount.trim();
    if (!/^\d{1,13}(\.\d{1,2})?$/.test(a)) {
      this.error.set('Enter an amount of 0.00 or more, with up to two decimals.');
      return;
    }
    const form = { amount: a, date: date.trim() || null, note: note.trim() };
    const id = this.editing();
    const saved = id === null ? this.service.add(this.personId(), form) : this.service.edit(id, form);
    saved.subscribe({
      next: () => this.reset(),
      error: () => this.error.set(id === null ? 'Could not add that claim.' : 'Could not save that claim.'),
    });
  }
}
