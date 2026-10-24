import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { Session } from '../../core/session';
import { Claim, ClaimAction, ClaimsService, dueByOf, money, overdue } from './claims.service';
import { PersonGrade } from './people.service';
import { Period, periodOn } from './periods.service';
import { Rate, claimShownCents } from './rates.service';

const AMOUNT = /^-?\d{1,10}(\.\d{1,2})?$/;
const NUMBER = /^\S.{0,119}$/;
const PAST: Record<ClaimAction, string> = {
  open: 'opened',
  settle: 'settled',
  approve: 'approved',
  reject: 'rejected',
  void: 'voided',
};

// A person's claims, newest first, each with its status and the buttons that change it, and the form that adds a claim to them or, once a claim's
// editor is opened, saves changes to it.
@Component({
  selector: 'app-claims',
  template: `
    <form (submit)="submit($event)" [attr.aria-label]="'Claim for ' + personName()" data-testid="claim-form">
      <label [for]="fieldId('amount')">Amount</label>
      <input
        [id]="fieldId('amount')"
        data-testid="claim-form-amount"
        inputmode="decimal"
        [value]="amount()"
        (input)="amount.set(value($event)); error.set(null)"
      />
      <label [for]="fieldId('date')">Date</label>
      <input
        [id]="fieldId('date')"
        type="date"
        data-testid="claim-form-date"
        [value]="date()"
        (input)="date.set(value($event)); error.set(null)"
      />
      <label [for]="fieldId('note')">Note</label>
      <input
        [id]="fieldId('note')"
        data-testid="claim-form-note"
        [value]="note()"
        (input)="note.set(value($event)); error.set(null)"
      />
      <label [for]="fieldId('corrects')">Corrects claim no.</label>
      <input
        [id]="fieldId('corrects')"
        data-testid="claim-form-corrects"
        [value]="corrects()"
        (input)="corrects.set(value($event)); error.set(null)"
      />
      <button type="submit" data-testid="claim-form-submit">
        {{ editingId() === null ? 'Add claim' : 'Save claim' }}
      </button>
      @if (error(); as message) {
        <p role="alert" class="error" data-testid="claim-form-error">{{ message }}</p>
      }
    </form>
    @if (claims().length > 0) {
      <ul class="claims" [attr.aria-label]="'Claims of ' + personName()">
        @for (c of claims(); track c.id) {
          <li [attr.data-testid]="'claim-row-' + c.id">
            <input
              type="checkbox"
              data-testid="claim-select"
              [attr.aria-label]="'Select claim of ' + c.amount + ' on ' + c.date"
              [checked]="selected().has(c.id)"
              (change)="selectToggled.emit({ id: c.id, selected: checked($event) })"
            />
            <span>No. <span data-testid="claim-number">{{ c.number ?? '' }}</span></span>
            <span data-testid="claim-amount">{{ c.amount }}</span>
            <span data-testid="claim-date">{{ c.date }}</span>
            <span data-testid="claim-note">{{ c.note }}</span>
            <span>Due <span data-testid="claim-due">{{ dueOf(c) }}</span></span>
            <span>Due by <span data-testid="claim-due-by">{{ dueBy(c) }}</span></span>
            <span>Overdue <span data-testid="claim-overdue">{{ isOverdue(c) ? 'yes' : 'no' }}</span></span>
            <span data-testid="claim-status">{{ c.status }}</span>
            <span>Opened by <span data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</span></span>
            <span>Stated <span data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</span></span>
            <span>Period <span data-testid="claim-period">{{ periodOf(c) }}</span></span>
            <span>Corrects <span data-testid="claim-corrects">{{ c.corrects ?? '' }}</span></span>
            <label [for]="fieldId('reject-reason-' + c.id)">Reject reason</label>
            <input
              [id]="fieldId('reject-reason-' + c.id)"
              data-testid="claim-reject-reason"
              [value]="rejectReasons()[c.id] ?? ''"
              (input)="setRejectReason(c.id, value($event))"
            />
            @for (a of actions; track a.action) {
              <button
                type="button"
                [attr.data-testid]="'claim-' + a.action"
                [attr.aria-label]="a.label + ' claim of ' + c.amount + ' on ' + c.date"
                (click)="act(c, a.action)"
              >
                {{ a.label }}
              </button>
            }
            <button
              type="button"
              data-testid="claim-edit"
              [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date"
              (click)="edit(c)"
            >
              Edit
            </button>
            @if (editingId() === c.id) {
              <button type="button" data-testid="claim-cancel" (click)="reset()">Cancel</button>
            }
            @if (rowErrors()[c.id]; as message) {
              <p role="alert" class="error" data-testid="claim-error">{{ message }}</p>
            }
          </li>
        }
      </ul>
    }
  `,
  styles: `
    form {
      display: flex;
      flex-wrap: wrap;
      gap: 0.25rem;
      align-items: center;
    }
    .error {
      color: #a40000;
      margin: 0;
    }
    .claims {
      margin: 0.25rem 0 0;
      padding-left: 1rem;
    }
    .claims li span {
      margin-right: 0.75rem;
    }
  `,
})
export class Claims {
  private readonly session = inject(Session);
  private readonly service = inject(ClaimsService);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  /** The person's claims, newest first. */
  readonly claims = input.required<Claim[]>();
  /** The grades the person was given, each from its date. */
  readonly grades = input.required<PersonGrade[]>();
  /** The sheet's rates. */
  readonly rates = input.required<Rate[]>();
  /** The sheet's floor, in cents: the least a claim's live due can be. */
  readonly floor = input.required<number>();
  /** The sheet's periods. */
  readonly periods = input.required<Period[]>();
  /** The sheet's terms, in days: how long after its date a claim is due by. */
  readonly terms = input.required<number>();
  /** The sheet's late percent, with two decimals. */
  readonly latePct = input.required<string>();
  /** The sheet's policy date in force (YYYY-MM-DD): a claim dated before it rounds half up, one on
   *  or after it half to even; null until read. */
  readonly policyDate = input.required<string | null>();
  /** The ids of the claims selected for a bulk action. */
  readonly selected = input.required<ReadonlySet<number>>();
  /** A claim was selected or unselected. */
  readonly selectToggled = output<{ id: number; selected: boolean }>();
  /** A claim was added or saved. */
  readonly saved = output<Claim>();

  protected readonly amount = signal('');
  protected readonly date = signal('');
  protected readonly note = signal('');
  protected readonly corrects = signal('');
  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  /** A submit is waiting for its answer; another submit meanwhile is ignored. */
  private submitting = false;
  /** The error of the last refused action on each claim, by claim id. */
  protected readonly rowErrors = signal<Record<number, string>>({});
  /** The reason typed for rejecting each PENDING claim, by claim id. */
  protected readonly rejectReasons = signal<Record<number, string>>({});
  protected readonly actions: { action: ClaimAction; label: string }[] = [
    { action: 'open', label: 'Open' },
    { action: 'settle', label: 'Settle' },
    { action: 'approve', label: 'Approve' },
    { action: 'reject', label: 'Reject' },
    { action: 'void', label: 'Void' },
  ];

  protected fieldId(field: string): string {
    return `claim-${this.personId()}-${field}`;
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected checked(event: Event): boolean {
    return (event.target as HTMLInputElement).checked;
  }

  /** What is due on the claim at the rate in force on the claim's date of the person's grade in force
   *  then; n/a when no rate of that grade applies then; never below the sheet's floor; with its late charge added while it is
   *  overdue. A claim settled at a rate keeps what was due on it then; one settled without a rate
   *  fixed nothing. */
  protected dueOf(claim: Claim): string {
    const late = { terms: this.terms(), today: this.session.today(), latePct: this.latePct() };
    const rounding = { policyDate: this.policyDate(), claims: this.claims() };
    const due = claimShownCents(claim, this.rates(), this.grades(), this.floor(), late, rounding);
    return due === null ? 'n/a' : money(due);
  }

  protected dueBy(claim: Claim): string {
    return dueByOf(claim, this.terms());
  }

  protected isOverdue(claim: Claim): boolean {
    return overdue(claim, this.terms(), this.session.today());
  }

  /** The name of the period whose dates contain the claim's date; empty when none does. */
  protected periodOf(claim: Claim): string {
    return periodOn(this.periods(), claim.date)?.name ?? '';
  }

  /** Open the claim's editor; only a DRAFT can be edited, so the edit of any other claim is refused with
   *  an error on the claim (an open claim is changed by voiding it and adding a new one). */
  protected edit(claim: Claim): void {
    this.clearRowError(claim.id);
    if (claim.status !== 'DRAFT') {
      if (this.editingId() === claim.id) this.reset();
      this.rowErrors.update((errors) => ({
        ...errors,
        [claim.id]: 'Only a draft claim can be edited; void it and add a new one instead.',
      }));
      return;
    }
    this.editingId.set(claim.id);
    this.amount.set(claim.amount);
    this.date.set(claim.date);
    this.note.set(claim.note);
    this.corrects.set(claim.corrects ?? '');
    this.error.set(null);
  }

  protected setRejectReason(id: number, reason: string): void {
    this.rejectReasons.update((reasons) => ({ ...reasons, [id]: reason }));
    this.clearRowError(id);
  }

  /** Change the claim's status; a change its status does not allow, a settle by the user who opened
   *  the claim, an approve by a user who is not a steward or who opened the claim, a reject by a user
   *  who is not a steward or without a one-word reason, is refused with an error. A clerk's settle of
   *  a claim whose due is above the clerk limit leaves it PENDING for a steward. */
  protected async act(claim: Claim, action: ClaimAction): Promise<void> {
    this.clearRowError(claim.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const reason = (this.rejectReasons()[claim.id] ?? '').trim();
    if (action === 'reject' && !/^\S{1,200}$/.test(reason)) {
      this.rowErrors.update((errors) => ({ ...errors, [claim.id]: 'Type a one-word reason to reject that claim.' }));
      return;
    }
    try {
      const changed = await this.service.act(sheet, claim.id, action, action === 'reject' ? reason : undefined);
      if (this.session.sheetId() !== sheet) return;
      if (action === 'reject') this.rejectReasons.update(({ [claim.id]: _, ...rest }) => rest);
      this.saved.emit(changed);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.rowErrors.update((errors) => ({
        ...errors,
        [claim.id]: e instanceof HttpErrorResponse && e.status === 403
          ? action === 'settle'
            ? 'You opened that claim, so another user must settle it.'
            : `Only a steward${action === 'approve' ? ' who did not open that claim' : ''} can ${action} it.`
          : e instanceof HttpErrorResponse && e.status === 400
          ? 'Type a one-word reason to reject that claim.'
          : e instanceof HttpErrorResponse && e.status === 423
          ? 'That claim is dated in a closed period.'
          : `That claim cannot be ${PAST[action]} in its status.`,
      }));
    }
  }

  private clearRowError(id: number): void {
    this.rowErrors.update(({ [id]: _, ...rest }) => rest);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.amount.set('');
    this.date.set('');
    this.note.set('');
    this.corrects.set('');
    this.error.set(null);
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const amount = this.amount().trim();
    if (!AMOUNT.test(amount)) {
      this.error.set('Type an amount with up to two decimals.');
      return;
    }
    const corrects = this.corrects().trim();
    if (corrects !== '' && !NUMBER.test(corrects)) {
      this.error.set('Type the number of the claim it corrects.');
      return;
    }
    const fields = {
      amount,
      date: this.date().trim() || null,
      note: this.note(),
      corrects: corrects === '' ? null : corrects,
    };
    const id = this.editingId();
    this.submitting = true;
    try {
      const claim =
        id === null
          ? await this.service.add(sheet, this.personId(), fields)
          : await this.service.update(sheet, id, fields);
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(claim);
      this.reset();
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 423
          ? 'A claim dated in a closed period cannot be changed.'
          : e instanceof HttpErrorResponse && e.status === 422
            ? 'A claim cannot be dated after the person retired.'
          : e instanceof HttpErrorResponse && e.status === 400
            ? 'A negative amount is a correction: name the number of a settled claim of this person it corrects (and only a correction names one).'
          : id !== null && e instanceof HttpErrorResponse && e.status === 409
            ? 'Only a draft claim can be edited; void it and add a new one instead.'
          : id === null
            ? 'That claim could not be added.'
            : 'That claim could not be saved.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
