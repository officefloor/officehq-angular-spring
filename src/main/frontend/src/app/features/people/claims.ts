import { HttpErrorResponse } from '@angular/common/http';
import { Component, inject, input, output, signal } from '@angular/core';
import { Session } from '../../core/session';
import { Claim, ClaimAction, ClaimsService, money } from './claims.service';
import { Grade } from './people.service';
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
            <span data-testid="claim-status">{{ c.status }}</span>
            <span>Opened by <span data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</span></span>
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
  /** A claim was added or saved. */
  readonly saved = output<Claim>();

  protected readonly amount = signal('');
  protected readonly date = signal('');
  protected readonly note = signal('');
  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
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
   *  their grade applies then. A claim settled
   *  at a rate keeps what was due on it then; one settled without a rate fixed nothing. */
  protected dueOf(claim: Claim): string {
    const due = claimDueCents(claim, this.rates(), this.grade());
    return due === null ? 'n/a' : money(due);
  }

  protected edit(claim: Claim): void {
    this.clearRowError(claim.id);
    this.editingId.set(claim.id);
    this.amount.set(claim.amount);
    this.date.set(claim.date);
    this.note.set(claim.note);
    this.error.set(null);
  }

  /** Change the claim's status; a change its status does not allow, or a settle by the user who opened
   *  the claim, is refused with an error. */
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
    try {
      const claim =
        id === null
          ? await this.service.add(sheet, this.personId(), fields)
          : await this.service.update(sheet, id, fields);
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(claim);
      this.reset();
    } catch {
      this.error.set(id === null ? 'That claim could not be added.' : 'That claim could not be saved.');
    }
  }
}
