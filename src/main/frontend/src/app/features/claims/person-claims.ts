import { Component, inject, input, output, signal } from '@angular/core';
import { HttpErrorResponse } from '@angular/common/http';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { PeriodView, periodOf } from '../periods/periods-api';
import { RateView } from '../rates/rates-api';
import { SheetFloor } from '../settings/sheet-floor';
import { AMOUNT, ClaimAction, ClaimView, ClaimsApi, claimDueCents, money } from './claims-api';

const DATE = /^\d{4}-\d{2}-\d{2}$/;
const PAST: Record<ClaimAction, string> = { open: 'opened', settle: 'settled', void: 'voided' };

// One person's claims, newest first, with one form to add a claim or edit one.
@Component({
  selector: 'app-person-claims',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="submit()"
      [attr.aria-label]="(editingId() === null ? 'Add a claim for ' : 'Edit a claim of ') + personName()">
      <label [for]="fieldId('amount')">Amount</label>
      <input [id]="fieldId('amount')" data-testid="claim-form-amount" formControlName="amount" inputmode="decimal" />
      <label [for]="fieldId('date')">Date</label>
      <input [id]="fieldId('date')" type="date" data-testid="claim-form-date" formControlName="date" />
      <label [for]="fieldId('note')">Note</label>
      <input [id]="fieldId('note')" data-testid="claim-form-note" formControlName="note" />
      <button type="submit" data-testid="claim-form-submit" [disabled]="saving()">
        {{ editingId() === null ? 'Add claim' : 'Save claim' }}
      </button>
      @if (error()) {
        <p role="alert" data-testid="claim-form-error">{{ error() }}</p>
      }
    </form>
    <ul [attr.aria-label]="'Claims of ' + personName()">
      @for (c of claims(); track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <span>No. <span data-testid="claim-number">{{ c.number ?? '' }}</span></span>
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span>Due <span data-testid="claim-due">{{ due(c) }}</span></span>
          <span data-testid="claim-status">{{ c.status }}</span>
          <span>Opened by <span data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</span></span>
          <span>Stated <span data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</span></span>
          <span>Period <span data-testid="claim-period">{{ periodName(c) }}</span></span>
          <button type="button" data-testid="claim-open" (click)="act(c, 'open')"
            [attr.aria-label]="'Open claim of ' + c.amount + ' on ' + c.date">Open</button>
          <button type="button" data-testid="claim-settle" (click)="act(c, 'settle')"
            [attr.aria-label]="'Settle claim of ' + c.amount + ' on ' + c.date">Settle</button>
          <button type="button" data-testid="claim-void" (click)="act(c, 'void')"
            [attr.aria-label]="'Void claim of ' + c.amount + ' on ' + c.date">Void</button>
          <button type="button" data-testid="claim-edit" (click)="edit(c)"
            [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date">Edit</button>
          @if (editingId() === c.id) {
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
  private readonly session = inject(Session);
  private readonly api = inject(ClaimsApi);
  private readonly floor = inject(SheetFloor);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  /** The person's claims, newest first. */
  readonly claims = input.required<ClaimView[]>();
  /** The rates of the person's grade, whatever their from-dates. */
  readonly rates = input.required<RateView[]>();
  /** The sheet's periods in date order. */
  readonly periods = input.required<PeriodView[]>();
  readonly added = output<ClaimView>();
  readonly saved = output<ClaimView>();
  /** A claim whose status changed. */
  readonly changed = output<ClaimView>();

  protected readonly amount = new FormControl('', { nonNullable: true });
  protected readonly date = new FormControl('', { nonNullable: true });
  protected readonly note = new FormControl('', { nonNullable: true });
  protected readonly form = new FormGroup({ amount: this.amount, date: this.date, note: this.note });

  protected readonly editingId = signal<number | null>(null);
  protected readonly saving = signal(false);
  protected readonly error = signal('');
  /** The refusal of the last action taken on a claim's row, shown on that row. */
  protected readonly claimError = signal<{ id: number; message: string } | null>(null);

  constructor() {
    this.form.valueChanges.subscribe(() => {
      this.error.set('');
      // A refused save shown on the claim being edited belongs to the form too.
      if (this.editingId() !== null && this.claimError()?.id === this.editingId()) this.claimError.set(null);
    });
  }

  /** What is due on the claim: the figure fixed when it was settled, else the figure its person's
   *  last statement bound it to, else at the person's grade's
   *  rate in force on the claim's date and at least the sheet's floor, or n/a when the claim is dated before every rate of the grade. */
  protected due(claim: ClaimView): string {
    const due = claimDueCents(claim, this.rates(), this.floor.cents());
    return due === null ? 'n/a' : money(due);
  }

  /** The name of the period whose dates contain the claim's date, or blank when none does. */
  protected periodName(claim: ClaimView): string {
    return periodOf(this.periods(), claim.date)?.name ?? '';
  }

  private inClosedPeriod(date: string): boolean {
    return date !== '' && (periodOf(this.periods(), date)?.closed ?? false);
  }

  protected fieldId(field: string): string {
    return `claim-form-${field}-${this.personId()}`;
  }

  private reset(): void {
    this.editingId.set(null);
    this.error.set('');
    this.form.reset({ amount: '', date: '', note: '' }, { emitEvent: false });
  }

  protected edit(claim: ClaimView): void {
    this.claimError.set(null);
    this.editingId.set(claim.id);
    this.error.set('');
    this.form.setValue({ amount: claim.amount, date: claim.date, note: claim.note }, { emitEvent: false });
  }

  /** Open, settle or void a claim; the store refuses a change its status does not allow. */
  protected async act(claim: ClaimView, action: ClaimAction): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.claimError.set(null);
    try {
      const changed = await this.api.act(sheet.id, claim.id, action);
      if (this.session.sheet()?.id === sheet.id) this.changed.emit(changed);
    } catch (e) {
      if (this.session.sheet()?.id === sheet.id) {
        const message = e instanceof HttpErrorResponse && e.status === 403
          ? 'You opened this claim, so someone else must settle it.'
          : e instanceof HttpErrorResponse && e.status === 422
            ? 'This claim\'s due is above the clerk limit, so only a steward can settle it.'
          : periodOf(this.periods(), claim.date)?.closed
            ? `This claim is dated inside a closed period, so it cannot be ${PAST[action]}.`
            : `This claim cannot be ${PAST[action]} from its status.`;
        this.claimError.set({ id: claim.id, message });
      }
    }
  }

  protected cancel(): void {
    this.reset();
  }

  protected async submit(): Promise<void> {
    // A second submit before the first has answered is ignored, so it stores one record, not two.
    if (this.saving()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.error.set('');
    this.claimError.set(null);
    const value = this.form.getRawValue();
    const amount = value.amount.trim();
    const date = value.date.trim();
    if (!AMOUNT.test(amount)) {
      this.error.set('Type an amount of 0.00 or more, with up to two decimals.');
      return;
    }
    if (date !== '' && !DATE.test(date)) {
      this.error.set('Type a date as yyyy-mm-dd.');
      return;
    }
    const form = { amount, date: date === '' ? null : date, note: value.note.trim() };
    const id = this.editingId();
    this.saving.set(true);
    try {
      if (id === null) {
        const claim = await this.api.add(sheet.id, this.personId(), form);
        if (this.session.sheet()?.id === sheet.id) this.added.emit(claim);
      } else {
        const claim = await this.api.update(sheet.id, id, form);
        if (this.session.sheet()?.id === sheet.id) this.saved.emit(claim);
      }
      if (this.session.sheet()?.id === sheet.id) this.reset();
    } catch (e) {
      const conflict = e instanceof HttpErrorResponse && e.status === 409;
      const editing = id === null ? undefined : this.claims().find((c) => c.id === id);
      if (conflict && editing && !this.inClosedPeriod(editing.date) && !this.inClosedPeriod(date)) {
        // Only a draft can be edited; the refusal is shown on the claim, and the editor stays open.
        this.claimError.set({ id: editing.id, message: 'Only a draft claim can be edited. To change an open claim, void it and add a new one.' });
      } else {
        this.error.set(conflict
          ? 'A claim dated inside a closed period cannot be added or changed.'
          : id === null ? 'The claim could not be added.' : 'The claim could not be saved.');
      }
    } finally {
      this.saving.set(false);
    }
  }
}
