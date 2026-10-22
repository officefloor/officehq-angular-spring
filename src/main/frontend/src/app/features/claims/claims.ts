import { Component, inject, input, output, signal } from '@angular/core';
import { AMOUNT, ClaimAction, ClaimView, ClaimsApi, money } from '../../core/claims';
import { Grade } from '../../core/people';
import { PeriodView, periodOn } from '../../core/periods';
import { NO_RATE, RateView, claimDueCents } from '../../core/rates';
import { Session } from '../../core/session';

// A person's claims: listed newest first, added and edited through one form on the person's row,
// and moved through their statuses (open, settle, void) from each claim's row.
const PAST: Record<ClaimAction, string> = { open: 'opened', settle: 'settled', void: 'voided' };

@Component({
  selector: 'app-claims',
  template: `
    <form (submit)="save($event)" [attr.aria-label]="(editing() === null ? 'Add a claim for ' : 'Edit a claim of ') + personName()">
      <label [for]="'claim-form-amount-' + personId()">Amount</label>
      <input
        [id]="'claim-form-amount-' + personId()"
        data-testid="claim-form-amount"
        inputmode="decimal"
        [value]="amount()"
        (input)="amount.set(value($event)); error.set(null)"
      />
      <label [for]="'claim-form-date-' + personId()">Date</label>
      <input
        [id]="'claim-form-date-' + personId()"
        type="date"
        data-testid="claim-form-date"
        [value]="date()"
        (input)="date.set(value($event)); error.set(null)"
      />
      <label [for]="'claim-form-note-' + personId()">Note</label>
      <input
        [id]="'claim-form-note-' + personId()"
        data-testid="claim-form-note"
        [value]="note()"
        (input)="note.set(value($event)); error.set(null)"
      />
      <button type="submit" data-testid="claim-form-submit">
        {{ editing() === null ? 'Add claim' : 'Save claim' }}
      </button>
      @if (editing() !== null) {
        <button type="button" data-testid="claim-form-cancel" (click)="reset()">Cancel</button>
      }
      @if (error(); as message) {
        <p role="alert" data-testid="claim-form-error">{{ message }}</p>
      }
    </form>
    <ul [attr.aria-label]="'Claims of ' + personName()">
      @for (c of claims(); track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <span>No. <span data-testid="claim-number">{{ c.number ?? '' }}</span></span>
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span>Due <span data-testid="claim-due">{{ dueOf(c) }}</span></span>
          <span>Period <span data-testid="claim-period">{{ periodOf(c) }}</span></span>
          <span data-testid="claim-status">{{ c.status }}</span>
          <span>Stated <span data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</span></span>
          <span>Opened by <span data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</span></span>
          <button
            type="button"
            data-testid="claim-edit"
            [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date"
            (click)="open(c)"
          >
            Edit
          </button>
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
          @if (claimError()?.id === c.id) {
            <p role="alert" data-testid="claim-error">{{ claimError()?.message }}</p>
          }
        </li>
      }
    </ul>
  `,
})
export class Claims {
  private readonly session = inject(Session);
  private readonly api = inject(ClaimsApi);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  /** The person's claims, newest first. */
  readonly claims = input.required<ClaimView[]>();
  /** The person's grade. */
  readonly grade = input.required<Grade>();
  /** The sheet's rates; each claim uses the one its person's grade has in force on its date. */
  readonly rates = input.required<RateView[]>();
  /** The sheet's periods; each claim belongs to the one whose dates contain its date, or to none. */
  readonly periods = input.required<PeriodView[]>();
  /** A claim added or saved through the form. */
  readonly saved = output<ClaimView>();

  protected readonly amount = signal('');
  protected readonly date = signal('');
  protected readonly note = signal('');
  /** The id of the claim open in the form, or null when the form adds a new claim. */
  protected readonly editing = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  /** A submit is waiting for its answer: a second submit meanwhile is ignored. */
  private busy = false;
  /** The refusal of the last action taken on a claim's row, shown on that row. */
  protected readonly claimError = signal<{ id: number; message: string } | null>(null);
  protected readonly actions: { action: ClaimAction; label: string }[] = [
    { action: 'open', label: 'Open' },
    { action: 'settle', label: 'Settle' },
    { action: 'void', label: 'Void' },
  ];

  /**
   * What is due on a claim: the due fixed when it was settled, or else the figure its person's last
   * statement bound it to, or else its amount at the rate the
   * person's grade has in force on the claim's date (n/a when it is dated before every rate).
   */
  protected dueOf(claim: ClaimView): string {
    const figure = claimDueCents(claim, this.rates(), this.grade());
    return figure === null ? NO_RATE : money(figure);
  }

  /** The name of the period a claim's date falls in; empty when it falls in none. */
  protected periodOf(claim: ClaimView): string {
    return periodOn(this.periods(), claim.date)?.name ?? '';
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  /**
   * Open a claim's editor: the form takes its values. Only a DRAFT claim can be edited; any other is
   * refused with an error on its row (to change an OPEN claim, void it and add a new one).
   */
  protected open(claim: ClaimView): void {
    this.claimError.set(null);
    if (claim.status !== 'DRAFT') {
      this.reset();
      this.claimError.set({
        id: claim.id,
        message: `A ${claim.status} claim cannot be edited; void it and add a new one.`,
      });
      return;
    }
    this.editing.set(claim.id);
    this.amount.set(claim.amount);
    this.date.set(claim.date);
    this.note.set(claim.note);
    this.error.set(null);
  }

  /** Move a claim's status; a move its status does not allow is refused with an error on its row. */
  protected async act(claim: ClaimView, action: ClaimAction): Promise<void> {
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    try {
      this.saved.emit(await this.api.act(sheet, claim.id, action));
    } catch {
      const own = action === 'settle' && claim.status === 'OPEN' && claim.openedBy === this.session.user()?.trim();
      this.claimError.set({
        id: claim.id,
        message: own ? 'You opened this claim, so someone else must settle it.' : `A ${claim.status} claim cannot be ${PAST[action]}.`,
      });
    }
  }

  protected reset(): void {
    this.editing.set(null);
    this.amount.set('');
    this.date.set('');
    this.note.set('');
    this.error.set(null);
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const amount = this.amount().trim();
    if (!AMOUNT.test(amount)) {
      this.error.set('Type an amount of 0.00 or more, with up to two decimals.');
      return;
    }
    const form = { amount, date: this.date().trim() || null, note: this.note() };
    const id = this.editing();
    this.busy = true;
    try {
      const claim = id === null
        ? await this.api.add(sheet, this.personId(), form)
        : await this.api.edit(sheet, id, form);
      this.saved.emit(claim);
      this.reset();
    } catch {
      this.error.set(id === null ? 'The claim could not be added.' : 'The claim could not be saved.');
    } finally {
      this.busy = false;
    }
  }
}
