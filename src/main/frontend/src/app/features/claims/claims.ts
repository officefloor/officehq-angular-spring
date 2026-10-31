import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, output, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { AMOUNT_PATTERN, ClaimStatus, ClaimView, Claims, cents, counts, money } from '../../core/claims';
import { PeriodView, periodOn } from '../../core/periods';
import { NOT_AVAILABLE, RateView, claimDueCents } from '../../core/rates';
import { Sheets } from '../../core/sheet';

// One person's claims: their claimed total, one form to add a claim or edit an existing one, and
// the claims listed newest first.
@Component({
  selector: 'app-person-claims',
  imports: [ReactiveFormsModule],
  template: `
    <p>Claimed: <span data-testid="person-claimed">{{ claimed() }}</span></p>

    <form [formGroup]="form" (ngSubmit)="submit()"
          [attr.aria-label]="(editingId() === null ? 'Add a claim for ' : 'Edit a claim of ') + personName()">
      <label [for]="fieldId('amount')">Amount</label>
      <input [id]="fieldId('amount')" data-testid="claim-form-amount" formControlName="amount"
             inputmode="decimal" autocomplete="off" />
      <label [for]="fieldId('date')">Date</label>
      <input [id]="fieldId('date')" type="date" data-testid="claim-form-date" formControlName="date" />
      <label [for]="fieldId('note')">Note</label>
      <input [id]="fieldId('note')" data-testid="claim-form-note" formControlName="note" autocomplete="off" />
      <button type="submit" data-testid="claim-form-submit" [disabled]="saving()">
        {{ editingId() === null ? 'Add claim' : 'Save claim' }}
      </button>
      @if (error(); as err) {
        <p role="alert" data-testid="claim-form-error">{{ err }}</p>
      }
    </form>

    <ul [attr.aria-label]="'Claims of ' + personName()">
      @for (c of claims(); track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          No. <span data-testid="claim-number">{{ c.number ?? '' }}</span>
          <span data-testid="claim-amount">{{ c.amount }}</span>
          on <span data-testid="claim-date">{{ c.date }}</span>
          — due <span data-testid="claim-due">{{ dueOf(c) }}</span>
          in period <span data-testid="claim-period">{{ periodOf(c) }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span data-testid="claim-status">{{ c.status }}</span>
          opened by <span data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</span>
          — stated <span data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</span>
          <button type="button" data-testid="claim-open" (click)="changeStatus(c, 'OPEN')"
                  [attr.aria-label]="'Open claim of ' + c.amount + ' on ' + c.date">Open</button>
          <button type="button" data-testid="claim-settle" (click)="changeStatus(c, 'SETTLED')"
                  [attr.aria-label]="'Settle claim of ' + c.amount + ' on ' + c.date">Settle</button>
          <button type="button" data-testid="claim-void" (click)="changeStatus(c, 'VOID')"
                  [attr.aria-label]="'Void claim of ' + c.amount + ' on ' + c.date">Void</button>
          @if (editingId() === c.id) {
            <button type="button" data-testid="claim-cancel" (click)="reset()">Cancel</button>
          } @else {
            <button type="button" data-testid="claim-edit" (click)="edit(c)"
                    [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date">Edit</button>
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
  private readonly sheets = inject(Sheets);
  private readonly api = inject(Claims);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  /** This person's claims, newest first. */
  readonly claims = input.required<ClaimView[]>();
  /** The rates of this person's grade, in the order they take effect. */
  readonly rates = input.required<readonly RateView[]>();
  /** The sheet's periods, by from-date. */
  readonly periods = input.required<readonly PeriodView[]>();
  readonly saved = output<ClaimView>();

  protected readonly claimed = computed(() => money(this.claims().filter(counts).reduce((sum, c) => sum + cents(c.amount), 0)));
  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  /** The error of the last action taken on a claim's row, until the next action on that row. */
  protected readonly claimError = signal<{ id: number; message: string } | null>(null);

  protected readonly form = new FormGroup({
    amount: new FormControl('', { nonNullable: true }),
    date: new FormControl('', { nonNullable: true }),
    note: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    // An error is removed as soon as its form is changed.
    this.form.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.error.set(null));
  }

  protected dueOf(c: ClaimView): string {
    const due = claimDueCents(c, this.rates());
    return due === null ? NOT_AVAILABLE : money(due);
  }

  protected periodOf(c: ClaimView): string {
    return periodOn(this.periods(), c.date)?.name ?? '';
  }

  protected changeStatus(c: ClaimView, status: ClaimStatus): void {
    this.claimError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    this.api.changeStatus(sheet.id, c.id, status).subscribe({
      next: (saved) => this.saved.emit(saved),
      error: (err: unknown) => this.claimError.set({
        id: c.id,
        message: err instanceof HttpErrorResponse && err.status === 403
          ? 'You opened this claim, so someone else must settle it.'
          : err instanceof HttpErrorResponse && err.status === 423
            ? 'This claim is dated inside a closed period.'
            : `A ${c.status} claim cannot become ${status}.`,
      }),
    });
  }

  protected fieldId(field: string): string {
    return `claim-form-${field}-${this.personId()}`;
  }

  protected edit(c: ClaimView): void {
    this.claimError.set(null);
    // Only a draft can be edited; an open claim is changed by voiding it and adding a new one.
    if (c.status !== 'DRAFT') {
      this.claimError.set({ id: c.id, message: `A ${c.status} claim cannot be edited. Void it and add a new one.` });
      return;
    }
    this.editingId.set(c.id);
    this.form.setValue({ amount: c.amount, date: c.date, note: c.note }, { emitEvent: false });
    this.error.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.form.reset({ amount: '', date: '', note: '' }, { emitEvent: false });
    this.error.set(null);
  }

  protected submit(): void {
    // A second submit before the first has answered is ignored, so it stores one record.
    if (this.saving()) return;
    this.error.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const value = this.form.getRawValue();
    const amount = value.amount.trim();
    if (!AMOUNT_PATTERN.test(amount)) {
      this.error.set('Type an amount of 0.00 or more, with up to two decimals.');
      return;
    }
    const date = value.date.trim();
    if (date !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.error.set('Choose a date for the claim, or leave it blank for today.');
      return;
    }
    const claim = { amount, date: date === '' ? null : date, note: value.note };
    const id = this.editingId();
    this.saving.set(true);
    const request = id === null ? this.api.add(sheet.id, this.personId(), claim) : this.api.update(sheet.id, id, claim);
    request.subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.saved.emit(saved);
        this.reset();
      },
      error: (err: unknown) => {
        this.saving.set(false);
        this.error.set(
          err instanceof HttpErrorResponse && err.status === 423
            ? 'That claim is dated inside a closed period.'
            : err instanceof HttpErrorResponse && err.status === 422
              ? 'That claim is dated after the person retired.'
            : id !== null && err instanceof HttpErrorResponse && err.status === 409
              ? 'Only a draft claim can be edited. Void it and add a new one.'
              : id === null ? 'Could not add that claim.' : 'Could not save that claim.',
        );
      },
    });
  }
}
