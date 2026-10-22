import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, output, signal } from '@angular/core';
import { AMOUNT, CLAIM_NUMBER, ClaimAction, ClaimView, ClaimsApi, cents, dueBy, money, overdue, roundsHalfUp } from '../../core/claims';
import { GradeView, gradeOn } from '../../core/people';
import { PeriodView, periodOn } from '../../core/periods';
import { NO_RATE, RateView, claimDueCents } from '../../core/rates';
import { Session } from '../../core/session';

// A person's claims: listed newest first, added and edited through one form on the person's row,
// moved through their statuses (open, settle, approve, reject with a reason, void) from each
// claim's row, and reassigned there to another person.
const PAST: Record<ClaimAction, string> = {
  open: 'opened',
  settle: 'settled',
  approve: 'approved',
  reject: 'rejected',
  void: 'voided',
};

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
      <label [for]="'claim-form-corrects-' + personId()">Corrects claim no.</label>
      <input
        [id]="'claim-form-corrects-' + personId()"
        data-testid="claim-form-corrects"
        inputmode="numeric"
        [value]="corrects()"
        (input)="corrects.set(value($event)); error.set(null)"
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
          <input
            type="checkbox"
            data-testid="claim-select"
            [attr.aria-label]="'Select claim of ' + c.amount + ' on ' + c.date"
            [checked]="selected().has(c.id)"
            (change)="selectedChange.emit({ id: c.id, on: checked($event) })"
          />
          <span>No. <span data-testid="claim-number">{{ c.number ?? '' }}</span></span>
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span>Due <span data-testid="claim-due">{{ dueOf(c) }}</span></span>
          <span>Due by <span data-testid="claim-due-by">{{ dueByOf(c) }}</span></span>
          <span>Overdue <span data-testid="claim-overdue">{{ overdueOf(c) ? 'yes' : 'no' }}</span></span>
          <span>Period <span data-testid="claim-period">{{ periodOf(c) }}</span></span>
          <span data-testid="claim-status">{{ c.status }}</span>
          <span>Stated <span data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</span></span>
          <span>Corrects <span data-testid="claim-corrects">{{ c.corrects ?? '' }}</span></span>
          <span>Opened by <span data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</span></span>
          <button
            type="button"
            data-testid="claim-edit"
            [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date"
            (click)="open(c)"
          >
            Edit
          </button>
          <label [for]="'claim-reject-reason-' + c.id">Reject reason</label>
          <input
            [id]="'claim-reject-reason-' + c.id"
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
          <label [for]="'claim-reassign-to-' + c.id">Reassign to</label>
          <select
            [id]="'claim-reassign-to-' + c.id"
            data-testid="claim-reassign-to"
            (change)="setReassignTo(c.id, value($event))"
          >
            <option value="">Choose a person</option>
            @for (other of others(); track other.id) {
              <option [value]="other.id" [selected]="reassignTo()[c.id] === other.id">{{ other.name }}</option>
            }
          </select>
          <button
            type="button"
            data-testid="claim-reassign"
            [attr.aria-label]="'Reassign claim of ' + c.amount + ' on ' + c.date"
            (click)="reassign(c)"
          >
            Reassign
          </button>
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
  /** The person's grades by the date each is in force from; each claim uses the one on its date. */
  readonly grades = input.required<GradeView[]>();
  /** The sheet's rates; each claim uses the one its person's grade has in force on its date. */
  readonly rates = input.required<RateView[]>();
  /** The sheet's periods; each claim belongs to the one whose dates contain its date, or to none. */
  readonly periods = input.required<PeriodView[]>();
  /** The people on the sheet, any of whom (but this one) a claim may be reassigned to. */
  readonly people = input.required<{ id: number; name: string }[]>();
  /** The ids of the claims selected for a bulk action. */
  readonly selected = input.required<ReadonlySet<number>>();
  /** A claim's checkbox ticked (on) or cleared. */
  readonly selectedChange = output<{ id: number; on: boolean }>();
  /** A claim added or saved through the form. */
  readonly saved = output<ClaimView>();

  protected readonly amount = signal('');
  protected readonly date = signal('');
  protected readonly note = signal('');
  /** The number of the SETTLED claim a correction corrects; empty on any other claim. */
  protected readonly corrects = signal('');
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
    { action: 'approve', label: 'Approve' },
    { action: 'reject', label: 'Reject' },
    { action: 'void', label: 'Void' },
  ];
  /** The people a claim of this person can be reassigned to. */
  protected readonly others = computed(() => this.people().filter((p) => p.id !== this.personId()));
  /** The person chosen on each claim's row to reassign it to, by claim id. */
  protected readonly reassignTo = signal<Record<number, number>>({});
  /** The reason typed on each claim's row for rejecting it, by claim id. */
  protected readonly rejectReasons = signal<Record<number, string>>({});

  /**
   * What is due on a claim: the due fixed when it was settled, or else the figure its person's last
   * statement bound it to, or else its amount at the rate the
   * person's grade has in force on the claim's date (n/a when it is dated before every rate), with
   * its late charge on top while it is overdue.
   */
  protected dueOf(claim: ClaimView): string {
    const late = { pct: this.session.latePct(), today: this.session.today() ?? '' };
    const halfUp = roundsHalfUp(claim, this.claims(), this.session.policyDate(), late.today);
    const figure = claimDueCents(claim, this.rates(), gradeOn(this.grades(), claim.date), cents(this.session.floor()), late, halfUp);
    return figure === null ? NO_RATE : money(figure);
  }

  /** A claim's due-by, fixed when it was added. */
  protected dueByOf(claim: ClaimView): string {
    return dueBy(claim);
  }

  /** Whether a claim is OPEN and due by a date before the sheet's today. */
  protected overdueOf(claim: ClaimView): boolean {
    return overdue(claim, this.session.today() ?? '');
  }

  /** The name of the period a claim's date falls in; empty when it falls in none. */
  protected periodOf(claim: ClaimView): string {
    return periodOn(this.periods(), claim.date)?.name ?? '';
  }

  protected checked(event: Event): boolean {
    return (event.target as HTMLInputElement).checked;
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  /**
   * Open a claim's editor: the form takes its values. Only a DRAFT claim can be edited; any other is
   * refused with an error on its row (to change an OPEN claim, void it and add a new one), as is one
   * dated in a closed period.
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
    const period = periodOn(this.periods(), claim.date);
    if (period?.closed) {
      this.reset();
      this.claimError.set({ id: claim.id, message: `Period ${period.name} is closed, so its claims cannot be edited.` });
      return;
    }
    this.editing.set(claim.id);
    this.amount.set(claim.amount);
    this.date.set(claim.date);
    this.note.set(claim.note);
    this.corrects.set(claim.corrects ?? '');
    this.error.set(null);
  }

  /** Type the reason for rejecting a claim; it clears any error left on the claim's row. */
  protected setRejectReason(id: number, reason: string): void {
    this.rejectReasons.update((reasons) => ({ ...reasons, [id]: reason }));
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  /** Choose the person to reassign a claim to; it clears any error left on the claim's row. */
  protected setReassignTo(id: number, personId: string): void {
    this.reassignTo.update((chosen) => ({ ...chosen, [id]: Number(personId) }));
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  /**
   * Move a claim to the person chosen on its row; refused with an error on its row when none is
   * chosen, when the user is not a steward, or when the claim is SETTLED or VOID.
   */
  protected async reassign(claim: ClaimView): Promise<void> {
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const to = this.reassignTo()[claim.id];
    if (!to) {
      this.claimError.set({ id: claim.id, message: 'Choose a person to reassign the claim to.' });
      return;
    }
    try {
      this.saved.emit(await this.api.reassign(sheet, claim.id, to));
    } catch (e) {
      this.claimError.set({
        id: claim.id,
        message: e instanceof HttpErrorResponse && e.status === 403
          ? 'Only a steward can reassign a claim.'
          : claim.status === 'SETTLED' || claim.status === 'VOID'
            ? `A ${claim.status} claim cannot be reassigned.`
            : 'The claim could not be reassigned to that person.',
      });
    }
  }

  /**
   * Move a claim's status; a move its status does not allow is refused with an error on its row, as
   * is a reject without a reason. A clerk settling a claim above the clerk limit sends it PENDING.
   */
  protected async act(claim: ClaimView, action: ClaimAction): Promise<void> {
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const reason = (this.rejectReasons()[claim.id] ?? '').trim();
    if (action === 'reject' && reason === '') {
      this.claimError.set({ id: claim.id, message: 'Type a reason to reject the claim.' });
      return;
    }
    try {
      this.saved.emit(await this.api.act(sheet, claim.id, action, reason));
      if (action === 'reject') this.rejectReasons.update((reasons) => ({ ...reasons, [claim.id]: '' }));
    } catch (e) {
      const mine = claim.openedBy === this.session.user()?.trim();
      const own = (action === 'settle' && claim.status === 'OPEN' && mine)
        || (action === 'approve' && claim.status === 'PENDING' && mine);
      const steward = (action === 'approve' || action === 'reject') && claim.status === 'PENDING'
        && e instanceof HttpErrorResponse && e.status === 403;
      this.claimError.set({
        id: claim.id,
        message: own
          ? `You opened this claim, so someone else must ${action} it.`
          : steward
            ? `Only a steward can ${action} a PENDING claim.`
            : `A ${claim.status} claim cannot be ${PAST[action]}.`,
      });
    }
  }

  protected reset(): void {
    this.editing.set(null);
    this.amount.set('');
    this.date.set('');
    this.note.set('');
    this.corrects.set('');
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
      this.error.set('Type an amount with up to two decimals; below 0.00 only for a correction.');
      return;
    }
    const corrects = this.corrects().trim();
    if (corrects !== '' && !CLAIM_NUMBER.test(corrects)) {
      this.error.set('Type the number of the claim it corrects.');
      return;
    }
    const form = { amount, date: this.date().trim() || null, note: this.note(), corrects: corrects === '' ? null : corrects };
    const id = this.editing();
    this.busy = true;
    try {
      const claim = id === null
        ? await this.api.add(sheet, this.personId(), form)
        : await this.api.edit(sheet, id, form);
      this.saved.emit(claim);
      this.reset();
    } catch {
      const verb = id === null ? 'added' : 'saved';
      this.error.set(amount.startsWith('-') || corrects !== ''
        ? `The claim could not be ${verb}: a correction (an amount below 0.00) must name a SETTLED claim of ${this.personName()}, and only a correction names one.`
        : `The claim could not be ${verb}.`);
    } finally {
      this.busy = false;
    }
  }
}
