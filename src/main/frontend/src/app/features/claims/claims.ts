import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { CLAIM_AMOUNT, CLAIM_NUMBER, Claim, ClaimAction, Claims, dueBy, formatCents, isCorrection, overdue } from '../../core/claims';
import { GradeFrom, gradeOn, retiredOn } from '../../core/people';
import { Periods } from '../../core/periods';
import { Rates } from '../../core/rates';
import { Session } from '../../core/session';

const CLOSED = 'The claim is dated in a closed period, so it cannot be changed.';
const RETIRED = 'The person is retired before that date, so no claim can be dated then.';
const NOT_CORRECTING = 'A negative amount is a correction: name the number of a settled claim of this person that it corrects.';
const NOT_DRAFT = 'Only a draft claim can be edited. To change an open claim, void it and add a new one.';

// One person's claims, newest first, and the one form that adds a claim or edits an existing one.
@Component({
  selector: 'app-person-claims',
  template: `
    <form (submit)="save($event)" [attr.aria-label]="'Claim for ' + personName()">
      <label [for]="'claim-amount-' + personId()">Amount</label>
      <input
        [id]="'claim-amount-' + personId()"
        inputmode="decimal"
        data-testid="claim-form-amount"
        [value]="amount()"
        (input)="amount.set($any($event.target).value); error.set(null)"
      />
      <label [for]="'claim-date-' + personId()">Date</label>
      <input
        [id]="'claim-date-' + personId()"
        type="date"
        data-testid="claim-form-date"
        [value]="date()"
        (input)="date.set($any($event.target).value); error.set(null)"
      />
      <label [for]="'claim-note-' + personId()">Note</label>
      <input
        [id]="'claim-note-' + personId()"
        data-testid="claim-form-note"
        [value]="note()"
        (input)="note.set($any($event.target).value); error.set(null)"
      />
      <label [for]="'claim-corrects-' + personId()">Corrects claim number</label>
      <input
        [id]="'claim-corrects-' + personId()"
        inputmode="numeric"
        data-testid="claim-form-corrects"
        [value]="corrects()"
        (input)="corrects.set($any($event.target).value); error.set(null)"
      />
      <button type="submit" data-testid="claim-form-submit">
        {{ editingId() === null ? 'Add claim' : 'Save claim' }}
      </button>
      @if (editingId() !== null) {
        <button type="button" data-testid="claim-form-cancel" (click)="reset()">Cancel</button>
      }
      @if (error(); as err) {
        <p role="alert" data-testid="claim-form-error">{{ err }}</p>
      }
    </form>

    <ul>
      @for (c of mine(); track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <input
            type="checkbox"
            data-testid="claim-select"
            [checked]="claims.selected().has(c.id)"
            (change)="claims.select(c.id, $any($event.target).checked)"
            [attr.aria-label]="'Select claim of ' + c.amount + ' on ' + c.date"
          />
          <span data-testid="claim-number">{{ c.number }}</span>
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-due">{{ due(c) }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-due-by">{{ dueBy(c) }}</span>
          <span data-testid="claim-overdue">{{ overdue(c) ? 'yes' : 'no' }}</span>
          <span data-testid="claim-period">{{ periods.on(c.date)?.name }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span data-testid="claim-corrects">{{ c.corrects }}</span>
          <span data-testid="claim-status">{{ c.status }}</span>
          <span data-testid="claim-opened-by">{{ c.openedBy }}</span>
          <button type="button" data-testid="claim-edit" (click)="edit(c)">
            Edit<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
          </button>
          @for (a of actions; track a.action) {
            <button type="button" [attr.data-testid]="'claim-' + a.action" (click)="act(c, a.action)">
              {{ a.label }}<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
            </button>
          }
          @if (rowError()?.id === c.id) {
            <p role="alert" data-testid="claim-error">{{ rowError()?.message }}</p>
          }
        </li>
      }
    </ul>
  `,
})
export class PersonClaims {
  protected readonly claims = inject(Claims);
  private readonly session = inject(Session);
  private readonly rates = inject(Rates);
  protected readonly periods = inject(Periods);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  /** The person's grades, each with the date it applies from; a claim uses the one in force on its date. */
  readonly grades = input.required<GradeFrom[]>();
  /** yyyy-mm-dd the person is retired from, or null; a claim dated after it is refused. */
  readonly retiredFrom = input<string | null>(null);

  protected readonly mine = computed(() => this.claims.all().filter((c) => c.personId === this.personId()));
  protected readonly amount = signal('');
  protected readonly date = linkedSignal(() => this.session.today() ?? '');
  protected readonly note = signal('');
  /** The number of the settled claim a correction corrects, as typed. */
  protected readonly corrects = signal('');
  protected readonly editingId = signal<number | null>(null);
  /** Set while a submit awaits the store, so a second submit does not store a second claim. */
  private saving = false;
  protected readonly error = signal<string | null>(null);
  /** The error of the last action taken on a claim's row, if it was refused. */
  protected readonly rowError = signal<{ id: number; message: string } | null>(null);

  protected readonly actions: { action: ClaimAction; label: string }[] = [
    { action: 'open', label: 'Open' },
    { action: 'settle', label: 'Settle' },
    { action: 'void', label: 'Void' },
  ];

  /**
   * A settled claim's due as fixed when it was settled; any other claim's due at its person's grade (in force on
   * the claim's date)'s rate in force on the claim's date. n/a when there was or is no such rate.
   */
  protected due(claim: Claim): string {
    const cents = this.rates.dueOf(claim, gradeOn(this.grades(), claim.date));
    return cents === undefined ? 'n/a' : formatCents(cents);
  }

  /** The claim's due-by: its date plus the sheet's terms. */
  protected dueBy(claim: Claim): string {
    return dueBy(claim, this.session.terms() ?? 14);
  }

  /** Whether the claim is OPEN with its due-by before the sheet's today. */
  protected overdue(claim: Claim): boolean {
    return overdue(claim, this.session.terms() ?? 14, this.session.today() ?? '');
  }

  protected async act(claim: Claim, action: ClaimAction): Promise<void> {
    this.rowError.set(null);
    if (this.periods.closedOn(claim.date)) {
      this.rowError.set({ id: claim.id, message: CLOSED });
      return;
    }
    try {
      await this.claims.act(claim.id, action);
    } catch (e) {
      const message = e instanceof HttpErrorResponse && e.status === 423
        ? CLOSED
        : e instanceof HttpErrorResponse && e.status === 403
        ? claim.openedBy === this.session.user()
          ? 'The user who opened a claim cannot settle it.'
          : `A clerk cannot settle a claim with a due above ${this.session.clerkLimit()}.`
        : `A ${claim.status} claim cannot be changed that way.`;
      this.rowError.set({ id: claim.id, message });
    }
  }

  protected edit(claim: Claim): void {
    this.rowError.set(null);
    if (this.periods.closedOn(claim.date)) {
      this.reset();
      this.rowError.set({ id: claim.id, message: CLOSED });
      return;
    }
    if (claim.status !== 'DRAFT') {
      this.reset();
      this.rowError.set({ id: claim.id, message: NOT_DRAFT });
      return;
    }
    this.editingId.set(claim.id);
    this.amount.set(claim.amount);
    this.date.set(claim.date);
    this.note.set(claim.note);
    this.corrects.set(claim.corrects === null ? '' : String(claim.corrects));
    this.error.set(null);
  }

  protected reset(): void {
    this.editingId.set(null);
    this.amount.set('');
    this.date.set(this.session.today() ?? '');
    this.note.set('');
    this.corrects.set('');
    this.error.set(null);
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.saving) return;
    this.error.set(null);
    const fields = { amount: this.amount().trim(), date: this.date(), note: this.note(), corrects: this.corrects().trim() };
    if (!CLAIM_AMOUNT.test(fields.amount)) {
      this.error.set('Enter an amount with at most two decimals.');
      return;
    }
    if (isCorrection(fields)) {
      // Whether it names a settled claim of this person is the store's to say, as the screen may be out of date.
      if (!CLAIM_NUMBER.test(fields.corrects)) {
        this.error.set(NOT_CORRECTING);
        return;
      }
    } else {
      fields.corrects = '';
    }
    if (this.periods.closedOn(fields.date || (this.session.today() ?? ''))) {
      this.error.set(CLOSED);
      return;
    }
    if (retiredOn({ retiredFrom: this.retiredFrom() }, fields.date || (this.session.today() ?? ''))) {
      this.error.set(RETIRED);
      return;
    }
    const id = this.editingId();
    this.saving = true;
    try {
      if (id === null) {
        await this.claims.add(this.personId(), fields);
      } else {
        await this.claims.update(id, fields);
      }
      this.reset();
    } catch (e) {
      this.error.set(e instanceof HttpErrorResponse && e.status === 423
        ? CLOSED
        : e instanceof HttpErrorResponse && e.status === 409
        ? NOT_DRAFT
        : e instanceof HttpErrorResponse && e.status === 422
        ? isCorrection(fields) ? NOT_CORRECTING : RETIRED
        : id === null ? 'The claim could not be added.' : 'The claim could not be saved.');
    } finally {
      this.saving = false;
    }
  }
}
