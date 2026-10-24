import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { Session } from '../../core/session';
import { Claim, ClaimAction, ClaimsService, dueByOf, money, overdue } from './claims.service';
import { Grade } from './people.service';
import { Period, periodOn } from './periods.service';
import { Rate, claimDueCents } from './rates.service';

const AMOUNT = /^\d{1,10}(\.\d{1,2})?$/;

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
  /** The person's grade. */
  readonly grade = input.required<Grade>();
  /** The sheet's rates. */
  readonly rates = input.required<Rate[]>();
  /** The sheet's floor, in cents: the least a claim's live due can be. */
  readonly floor = input.required<number>();
  /** The sheet's periods. */
  readonly periods = input.required<Period[]>();
  /** The sheet's terms, in days: how long after its date a claim is due by. */
  readonly terms = input.required<number>();
  /** A claim was added or saved. */
  readonly saved = output<Claim>();

  protected readonly amount = signal('');
  protected readonly date = signal('');
  protected readonly note = signal('');
  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  /** A submit is waiting for its answer; another submit meanwhile is ignored. */
  private submitting = false;
  /** The error of the last refused action on each claim, by claim id. */
  protected readonly rowErrors = signal<Record<number, string>>({});
  protected readonly actions: { action: ClaimAction; label: string }[] = [
    { action: 'open', label: 'Open' },
    { action: 'settle', label: 'Settle' },
    { action: 'void', label: 'Void' },
  ];

  protected fieldId(field: string): string {
    return `claim-${this.personId()}-${field}`;
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  /** What is due on the claim at the person's rate in force on the claim's date; n/a when no rate of
   *  their grade applies then; never below the sheet's floor. A claim settled
   *  at a rate keeps what was due on it then; one settled without a rate fixed nothing. */
  protected dueOf(claim: Claim): string {
    const due = claimDueCents(claim, this.rates(), this.grade(), this.floor());
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
    this.error.set(null);
  }

  /** Change the claim's status; a change its status does not allow, a settle by the user who opened
   *  the claim, or a clerk's settle of a claim whose due is above the clerk limit, is refused with an error. */
  protected async act(claim: Claim, action: ClaimAction): Promise<void> {
    this.clearRowError(claim.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    try {
      const changed = await this.service.act(sheet, claim.id, action);
      if (this.session.sheetId() === sheet) this.saved.emit(changed);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      this.rowErrors.update((errors) => ({
        ...errors,
        [claim.id]: e instanceof HttpErrorResponse && e.status === 403
          ? 'You opened that claim, so another user must settle it.'
          : e instanceof HttpErrorResponse && e.status === 422
          ? 'What is due on that claim is above the clerk limit, so a steward must settle it.'
          : e instanceof HttpErrorResponse && e.status === 423
          ? 'That claim is dated in a closed period.'
          : `That claim cannot be ${action === 'open' ? 'opened' : action === 'settle' ? 'settled' : 'voided'} in its status.`,
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
      this.error.set('Type an amount of 0.00 or more, with up to two decimals.');
      return;
    }
    const fields = { amount, date: this.date().trim() || null, note: this.note() };
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
