import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { Claim, ClaimsApi } from './claims-api';

const AMOUNT = /^\d{1,10}(\.\d{1,2})?$/;

// One person's claims, newest first, and the one form that adds a claim for them or, once a
// claim's editor is opened, saves changes to it.
@Component({
  selector: 'app-claims',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()" data-testid="claim-form">
      <label [for]="'claim-form-amount-' + personId()">Amount</label>
      <input
        [id]="'claim-form-amount-' + personId()"
        data-testid="claim-form-amount"
        inputmode="decimal"
        formControlName="amount"
      />
      <label [for]="'claim-form-date-' + personId()">Date</label>
      <input [id]="'claim-form-date-' + personId()" type="date" data-testid="claim-form-date" formControlName="date" />
      <label [for]="'claim-form-note-' + personId()">Note</label>
      <input [id]="'claim-form-note-' + personId()" data-testid="claim-form-note" formControlName="note" />
      <button type="submit" data-testid="claim-form-submit">
        {{ editingId() === null ? 'Add claim' : 'Save claim' }}
      </button>
      @if (editingId() !== null) {
        <button type="button" data-testid="claim-form-cancel" (click)="reset()">Cancel</button>
      }
    </form>
    @if (formError(); as e) {
      <p role="alert" data-testid="claim-form-error">{{ e }}</p>
    }
    @if (claims().length > 0) {
      <table data-testid="claim-list">
        <caption class="visually-hidden">Claims by {{ personName() }}</caption>
        <thead>
          <tr>
            <th scope="col">Amount</th>
            <th scope="col">Date</th>
            <th scope="col">Note</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (c of claims(); track c.id) {
            <tr [attr.data-testid]="'claim-row-' + c.id">
              <td data-testid="claim-amount">{{ c.amount }}</td>
              <td data-testid="claim-date">{{ c.date }}</td>
              <td data-testid="claim-note">{{ c.note }}</td>
              <td>
                <button
                  type="button"
                  data-testid="claim-edit"
                  [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date"
                  (click)="edit(c)"
                >
                  Edit
                </button>
              </td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
})
export class Claims {
  private readonly session = inject(Session);
  private readonly api = inject(ClaimsApi);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  /** This person's claims, newest first. */
  readonly claims = input.required<Claim[]>();
  /** A claim was added or saved. */
  readonly saved = output<Claim>();

  protected readonly editingId = signal<number | null>(null);
  protected readonly formError = signal<string | null>(null);
  protected readonly form = new FormGroup({
    amount: new FormControl('', { nonNullable: true }),
    date: new FormControl('', { nonNullable: true }),
    note: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.formError.set(null));
    // A new claim is dated the sheet's today unless another date is picked.
    effect(() => {
      const today = this.session.today() ?? '';
      untracked(() => {
        if (this.editingId() === null && !this.form.controls.date.dirty) {
          this.form.controls.date.setValue(today, { emitEvent: false });
        }
      });
    });
  }

  protected edit(claim: Claim): void {
    this.editingId.set(claim.id);
    this.form.setValue({ amount: claim.amount, date: claim.date, note: claim.note }, { emitEvent: false });
    this.formError.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.form.reset({ amount: '', date: this.session.today() ?? '', note: '' }, { emitEvent: false });
    this.formError.set(null);
  }

  protected submit(): void {
    this.formError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) {
      this.formError.set('Load a sheet first.');
      return;
    }
    const value = this.form.getRawValue();
    const amount = value.amount.trim();
    if (!AMOUNT.test(amount)) {
      this.formError.set('Type an amount of 0.00 or more, with up to two decimals.');
      return;
    }
    const form = { amount, date: value.date || null, note: value.note.trim() };
    const id = this.editingId();
    const request = id === null ? this.api.add(sheetId, this.personId(), form) : this.api.update(sheetId, id, form);
    request.subscribe({
      next: (c) => {
        if (this.session.sheetId() === sheetId) this.saved.emit(c);
        this.reset();
      },
      error: (e: HttpErrorResponse) =>
        this.formError.set(e.status === 404 ? 'That person or claim is no longer on this sheet.' : 'Could not save that claim.'),
    });
  }
}
