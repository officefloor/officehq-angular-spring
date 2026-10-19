import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { Rate } from '../rates/rates-api';
import { Grade } from '../people/people-api';
import { Claim, ClaimAction, ClaimsApi, claimDue } from './claims-api';

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
            <th scope="col">No.</th>
            <th scope="col">Amount</th>
            <th scope="col">Date</th>
            <th scope="col">Note</th>
            <th scope="col">Due</th>
            <th scope="col">Status</th>
            <th scope="col">Opened by</th>
            <th scope="col"><span class="visually-hidden">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          @for (c of claims(); track c.id) {
            <tr [attr.data-testid]="'claim-row-' + c.id">
              <td data-testid="claim-number">{{ c.number ?? '' }}</td>
              <td data-testid="claim-amount">{{ c.amount }}</td>
              <td data-testid="claim-date">{{ c.date }}</td>
              <td data-testid="claim-note">{{ c.note }}</td>
              <td data-testid="claim-due">{{ due(c) }}</td>
              <td data-testid="claim-status">{{ c.status }}</td>
              <td data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</td>
              <td>
                <button
                  type="button"
                  data-testid="claim-edit"
                  [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date"
                  (click)="edit(c)"
                >
                  Edit
                </button>
                @for (a of actions; track a.action) {
                  <button
                    type="button"
                    [attr.data-testid]="'claim-' + a.action"
                    [attr.aria-label]="a.label + ' claim of ' + c.amount + ' on ' + c.date"
                    (click)="changeStatus(c, a.action)"
                  >
                    {{ a.label }}
                  </button>
                }
                @if (claimError()?.id === c.id) {
                  <p role="alert" data-testid="claim-error">{{ claimError()?.message }}</p>
                }
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
  /** This person's grade, and the sheet's rates. */
  readonly grade = input.required<Grade>();
  readonly rates = input<readonly Rate[]>([]);
  /** A claim was added or saved. */
  readonly saved = output<Claim>();

  protected readonly actions: { action: ClaimAction; label: string }[] = [
    { action: 'open', label: 'Open' },
    { action: 'settle', label: 'Settle' },
    { action: 'void', label: 'Void' },
  ];
  protected readonly editingId = signal<number | null>(null);
  /** The error of the last action taken on a claim, shown on that claim's row. */
  protected readonly claimError = signal<{ id: number; message: string } | null>(null);
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

  /** A settled claim shows the due it was settled at; any other is due at the rate its grade has now in
   * force on the claim's date. */
  protected due(claim: Claim): string {
    return claimDue(claim, this.rates(), this.grade());
  }

  protected changeStatus(claim: Claim, action: ClaimAction): void {
    this.claimError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    this.api.changeStatus(sheetId, claim.id, action).subscribe({
      next: (c) => {
        if (this.session.sheetId() === sheetId) this.saved.emit(c);
      },
      error: (e: HttpErrorResponse) =>
        this.claimError.set({
          id: claim.id,
          message:
            e.status === 403
              ? 'You opened that claim, so someone else must settle it.'
              : e.status === 409
              ? 'That claim\'s status does not allow that change.'
              : e.status === 404
                ? 'That claim is no longer on this sheet.'
                : 'Could not change that claim\'s status.',
        }),
    });
  }

  protected edit(claim: Claim): void {
    this.claimError.set(null);
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
