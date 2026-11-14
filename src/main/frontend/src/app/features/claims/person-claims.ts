import { Component, inject, input, output, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { Period, periodOn } from '../periods/periods-api';
import { Rate, claimDueCents } from '../rates/rates-api';
import { AMOUNT_PATTERN, Claim, ClaimAction, ClaimsApi, claimError, money, statusError } from './claims-api';

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
            <span>No. <span data-testid="claim-number">{{ c.number ?? '' }}</span></span>
            <span data-testid="claim-amount">{{ c.amount }}</span>
            <span data-testid="claim-date">{{ c.date }}</span>
            <span data-testid="claim-note">{{ c.note }}</span>
            <span>Due <span data-testid="claim-due">{{ dueOf(c) }}</span></span>
            <span>Status <span data-testid="claim-status">{{ c.status }}</span></span>
            <span>Opened by <span data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</span></span>
            <span>Stated <span data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</span></span>
            <span>Period <span data-testid="claim-period">{{ periodOf(c) }}</span></span>
            <button type="button" data-testid="claim-open" (click)="changeStatus(c, 'open')"
              [attr.aria-label]="'Open claim of ' + c.amount + ' on ' + c.date">Open</button>
            <button type="button" data-testid="claim-settle" (click)="changeStatus(c, 'settle')"
              [attr.aria-label]="'Settle claim of ' + c.amount + ' on ' + c.date">Settle</button>
            <button type="button" data-testid="claim-void" (click)="changeStatus(c, 'void')"
              [attr.aria-label]="'Void claim of ' + c.amount + ' on ' + c.date">Void</button>
            <button type="button" data-testid="claim-edit" (click)="edit(c)"
              [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date">Edit</button>
            @if (editingId() === c.id) {
              <button type="button" data-testid="claim-cancel" (click)="reset()">Cancel</button>
            }
            @if (rowErrors()[c.id]; as err) {
              <p role="alert" data-testid="claim-error">{{ err }}</p>
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
  /** Every rate the sheet has for this person's grade, each from its from-date. */
  readonly rates = input.required<Rate[]>();
  /** The sheet's periods. */
  readonly periods = input.required<Period[]>();
  /** A claim added or saved, for the sheet's list to take in. */
  readonly saved = output<Claim>();

  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  /** The error of each claim's last refused status change, by claim id. */
  /** Whether a submit is waiting for its answer; a second submit meanwhile is ignored. */
  private submitting = false;
  protected readonly rowErrors = signal<Record<number, string>>({});
  protected readonly fieldId = (name: string) => `claim-form-${name}-${this.personId()}`;

  protected readonly form = new FormGroup({
    amount: new FormControl('', { nonNullable: true }),
    date: new FormControl('', { nonNullable: true }),
    note: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(null));
  }

  /** The claim's amount times the rate in force on the claim's date, divided by 100, rounded half
   *  to even; n/a with no rate in force then. A claim settled with a due shows the due fixed then. */
  protected dueOf(c: Claim): string {
    const due = claimDueCents(this.rates(), c);
    return due === null ? 'n/a' : money(due);
  }

  /** The name of the period whose dates contain the claim's date; blank when none does. */
  protected periodOf(c: Claim): string {
    return periodOn(this.periods(), c.date)?.name ?? '';
  }

  protected edit(c: Claim): void {
    this.error.set(null);
    this.clearRowError(c.id);
    if (c.status !== 'DRAFT') {
      this.rowErrors.update((errs) => ({ ...errs, [c.id]: 'Only a draft claim can be edited; void it and add a new one.' }));
      return;
    }
    if (periodOn(this.periods(), c.date)?.closed) {
      this.rowErrors.update((errs) => ({ ...errs, [c.id]: 'This claim is dated in a closed period.' }));
      return;
    }
    this.editingId.set(c.id);
    this.form.setValue({ amount: c.amount, date: c.date, note: c.note });
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
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
    this.submitting = true;
    try {
      const claim = editing === null
        ? await this.api.add(sheetId, this.personId(), form)
        : await this.api.update(sheetId, editing, form);
      if (this.session.sheetId() === sheetId) this.saved.emit(claim);
      this.reset();
    } catch (e) {
      this.error.set(claimError(e));
    } finally {
      this.submitting = false;
    }
  }

  /** Open, settle or void a claim; a move its status does not allow shows an error on the claim. */
  protected async changeStatus(c: Claim, action: ClaimAction): Promise<void> {
    this.clearRowError(c.id);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    try {
      const claim = await this.api.changeStatus(sheetId, c.id, action);
      if (this.session.sheetId() === sheetId) this.saved.emit(claim);
    } catch (e) {
      this.rowErrors.update((errs) => ({ ...errs, [c.id]: statusError(e, c.openedBy === this.session.user()) }));
    }
  }

  private clearRowError(id: number): void {
    this.rowErrors.update(({ [id]: _, ...rest }) => rest);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.form.reset({ amount: '', date: '', note: '' });
    this.error.set(null);
  }
}
