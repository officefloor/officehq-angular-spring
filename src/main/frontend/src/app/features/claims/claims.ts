import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { Rate } from '../rates/rates-api';
import { GradeFrom, gradeOn } from '../people/people-api';
import { Period, periodOf } from '../periods/periods-api';
import { SheetFloor } from '../settings/sheet-floor';
import { SheetLatePct } from '../settings/sheet-late-pct';
import { SheetTerms } from '../settings/sheet-terms';
import { Claim, ClaimAction, ClaimsApi, Lateness, claimDue, dueBy, isOverdue } from './claims-api';

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
            <th scope="col">Period</th>
            <th scope="col">Due</th>
            <th scope="col">Due by</th>
            <th scope="col">Overdue</th>
            <th scope="col">Stated</th>
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
              <td data-testid="claim-period">{{ periodName(c) }}</td>
              <td data-testid="claim-due">{{ due(c) }}</td>
              <td data-testid="claim-due-by">{{ dueBy(c) }}</td>
              <td data-testid="claim-overdue">{{ overdue(c) ? 'yes' : 'no' }}</td>
              <td data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</td>
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
  private readonly floor = inject(SheetFloor);
  private readonly terms = inject(SheetTerms);
  private readonly latePct = inject(SheetLatePct);
  /** What a late charge goes by: the sheet's today, terms and late percent. */
  private readonly late = computed<Lateness>(() => ({
    today: this.session.today(),
    terms: this.terms.value(),
    latePct: this.latePct.value(),
  }));

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  /** This person's claims, newest first. */
  readonly claims = input.required<Claim[]>();
  /** This person's dated grades, and the sheet's rates. */
  readonly grades = input.required<readonly GradeFrom[]>();
  readonly rates = input<readonly Rate[]>([]);
  /** The sheet's periods. */
  readonly periods = input<readonly Period[]>([]);
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
  /** Whether a submit is waiting on the server; another submit is ignored until it answers. */
  private submitting = false;
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

  /** A settled claim shows the due it was settled at; any other is due at the rate the grade its person has on
   * the claim's date has now in force on that date, and at least the sheet's floor, plus its late charge when
   * overdue. */
  protected due(claim: Claim): string {
    return claimDue(claim, this.rates(), gradeOn(this.grades(), claim.date), this.floor.value(), this.late());
  }

  /** The claim's date plus the sheet's terms. */
  protected dueBy(claim: Claim): string {
    return dueBy(claim, this.terms.value());
  }

  /** Whether the claim is OPEN and its due-by is before the sheet's today. */
  protected overdue(claim: Claim): boolean {
    return isOverdue(claim, this.terms.value(), this.session.today());
  }

  /** The name of the period the claim's date falls in; empty when it falls in none. */
  protected periodName(claim: Claim): string {
    return periodOf(this.periods(), claim.date)?.name ?? '';
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
              : e.status === 422
              ? 'That claim\'s due is above the clerk limit, so only a steward can settle it.'
              : e.status === 423
              ? 'That claim is dated in a closed period.'
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
    if (claim.status !== 'DRAFT') {
      this.claimError.set({
        id: claim.id,
        message: 'Only a draft claim can be edited. To change it, void it and add a new one.',
      });
      return;
    }
    if (periodOf(this.periods(), claim.date)?.closed) {
      this.claimError.set({ id: claim.id, message: 'That claim is dated in a closed period.' });
      return;
    }
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
    if (this.submitting) return;
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
    this.submitting = true;
    request.subscribe({
      next: (c) => {
        this.submitting = false;
        if (this.session.sheetId() === sheetId) this.saved.emit(c);
        this.reset();
      },
      error: (e: HttpErrorResponse) => {
        this.submitting = false;
        this.formError.set(
          e.status === 404
            ? 'That person or claim is no longer on this sheet.'
            : e.status === 422
              ? 'That claim is dated after its person is retired.'
              : e.status === 423
              ? 'That claim is dated in a closed period.'
              : e.status === 409
                ? 'Only a draft claim can be edited. To change it, void it and add a new one.'
                : 'Could not save that claim.',
        );
      },
    });
  }
}
