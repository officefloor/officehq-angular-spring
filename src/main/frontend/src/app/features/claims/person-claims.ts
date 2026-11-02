import { HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { AMOUNT, CLAIM_AMOUNT, Claim, ClaimAction, Claims, claimDue, deferred, overdue } from '../../core/claims';
import { GradeFrom, Person, gradeOn } from '../../core/people';
import { Period, periodOf } from '../../core/periods';
import { Rate } from '../../core/rates';
import { Session } from '../../core/session';
import { DatedValue, Graces, graceOn } from '../../core/settings';

/** Why the server refused an action: the claim's status does not allow it. */
const REFUSALS: Record<ClaimAction, string> = {
  open: 'Only a draft claim can be opened.',
  settle: 'Only an open claim that is not deferred can be settled.',
  resettle: 'Only a settled claim can be re-settled.',
  void: 'Only a draft, open or pending claim can be voided, or a settled one within 7 days of its settlement.',
  approve: 'Only a pending claim can be approved.',
  reject: 'Only a pending claim can be rejected.',
};

/** The message when an edit of a claim that is not a draft is refused. */
const NOT_DRAFT = 'Only a draft claim can be edited; void an open claim and add a new one.';

/** The message when another user has the claim's editor open. */
const EDIT_LOCKED = 'Another user is editing this claim; try again once they save or cancel.';

/** The message for a refused opening of a claim's editor. */
function editorRefusal(e: unknown, c: Claim): string {
  if (e instanceof HttpErrorResponse && e.status === 423) return CLOSED;
  if (e instanceof HttpErrorResponse && e.status === 403) return 'Your role may not edit a claim.';
  if (e instanceof HttpErrorResponse && e.status === 409) return c.status === 'DRAFT' ? EDIT_LOCKED : NOT_DRAFT;
  return 'The claim\'s editor could not be opened.';
}

/** The message when the server refused a change to a claim dated inside a closed period. */
const CLOSED = 'The claim\'s period is closed.';
const RETIRED = 'A claim cannot be dated after the person\'s retirement.';
const CORRECTS = 'A negative amount must name the number of a settled claim of this person.';
const REF_USED = 'That reference is already used on this sheet.';
const REF_LONG = 'Enter a reference of at most 120 characters.';

/** The message for a refused action on a claim. */
function refusal(e: unknown, action: ClaimAction): string {
  if (e instanceof HttpErrorResponse && e.status === 423) return CLOSED;
  if (e instanceof HttpErrorResponse && e.status === 409) return REFUSALS[action];
  if (e instanceof HttpErrorResponse && e.status === 428) return NEEDS_PROOF;
  if (e instanceof HttpErrorResponse && e.status === 422 && action === 'open') {
    return 'A claim whose due is above the note threshold needs a note before it is opened.';
  }
  if (e instanceof HttpErrorResponse && e.status === 403 && action === 'settle') {
    return 'The user who opened a claim cannot settle it.';
  }
  if (e instanceof HttpErrorResponse && e.status === 403 && action === 'void') {
    return 'Your role may not void this claim; only a steward may void a settled claim.';
  }
  if (e instanceof HttpErrorResponse && e.status === 403 && action === 'resettle') {
    return 'Only a steward can re-settle a claim.';
  }
  if (e instanceof HttpErrorResponse && e.status === 403 && (action === 'approve' || action === 'reject')) {
    return action === 'approve' ? 'Only a steward who did not open the claim can approve it.'
      : 'Only a steward can reject a claim.';
  }
  if (e instanceof HttpErrorResponse && e.status === 400 && action === 'reject') {
    return 'Enter a reason for rejecting the claim.';
  }
  return 'The claim could not be changed.';
}

/** The message when a claim due above the proof threshold has no proof to be settled or approved. */
const NEEDS_PROOF = 'The claim\'s due is above the proof threshold: add a proof first.';

/** The message for a refused proof of a claim. */
function proofRefusal(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 423) return CLOSED;
  if (e instanceof HttpErrorResponse && e.status === 409) return 'A settled or void claim cannot be given a proof.';
  return 'The proof could not be added.';
}

/** The message for a refused reminder of a claim. */
function remindRefusal(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 409) return 'Only an open or pending claim can be reminded.';
  if (e instanceof HttpErrorResponse && e.status === 429) return 'The claim was already reminded today.';
  return 'The reminder could not be sent.';
}

/** The message for a refused defer of a claim. */
function deferRefusal(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 423) return CLOSED;
  if (e instanceof HttpErrorResponse && e.status === 409) return 'Only an open claim can be deferred.';
  if (e instanceof HttpErrorResponse && e.status === 400) return DEFER_DATE;
  return 'The claim could not be deferred.';
}

/** The message for a refused re-date of a claim. */
function redateRefusal(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 403) return 'Only a steward can re-date a claim.';
  if (e instanceof HttpErrorResponse && e.status === 423) return 'That claim\'s period is closed.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'Only an open or pending claim can be re-dated.';
  if (e instanceof HttpErrorResponse && e.status === 422) return 'A claim cannot be dated after the person\'s retirement.';
  if (e instanceof HttpErrorResponse && e.status === 400) return REDATE_DATE;
  return 'The claim could not be re-dated.';
}

/** The message when a re-date's date is missing. */
const REDATE_DATE = 'Enter the new date for the claim.';

/** The message when a defer's date is missing or not after the sheet's today. */
const DEFER_DATE = 'Enter a date after the sheet\'s today to defer the claim to.';

/** The message when a row's amount entry is not an amount. */
const INLINE_AMOUNT = 'Enter an amount with up to two decimals; a negative amount is a correction.';

/** The message for a refused change of a claim's amount from its row. */
function amountRefusal(e: unknown, c: Claim, amount: string): string {
  if (e instanceof HttpErrorResponse && e.status === 400 && amount.startsWith('-')) return CORRECTS;
  if (e instanceof HttpErrorResponse && e.status === 422) return RETIRED;
  if (e instanceof HttpErrorResponse && (e.status === 403 || e.status === 409 || e.status === 423)) {
    return editorRefusal(e, c);
  }
  return 'The claim\'s amount could not be saved.';
}

/** The message for a refused reassign of a claim. */
function reassignRefusal(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 403) return 'Only a steward can reassign a claim.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'Only a draft, open or pending claim can be reassigned.';
  if (e instanceof HttpErrorResponse && e.status === 423) return CLOSED;
  if (e instanceof HttpErrorResponse && e.status === 422) return 'The claim is dated after that person\'s retirement.';
  return 'The claim could not be reassigned.';
}

/** The message when a split's amount is not above 0.00 and below the claim's amount. */
const SPLIT_AMOUNT = 'Enter an amount above 0.00 and below the claim\'s amount to split off.';

/** The message for a refused split of a claim. */
function splitRefusal(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 423) return CLOSED;
  if (e instanceof HttpErrorResponse && e.status === 409) return 'Only an open claim that is not deferred can be split.';
  if (e instanceof HttpErrorResponse && e.status === 403) return 'Your role may not open a claim, so it cannot split one.';
  if (e instanceof HttpErrorResponse && e.status === 400) return SPLIT_AMOUNT;
  return 'The claim could not be split.';
}

// A person's claims, newest first, and the form that adds a claim for them or, once a claim's
// editor is opened, edits it.
@Component({
  selector: 'app-person-claims',
  imports: [ReactiveFormsModule],
  template: `
    <form data-testid="claim-form" [formGroup]="form" (ngSubmit)="submit()">
      <label [for]="'claim-form-amount-' + personId()">Amount</label>
      <input [id]="'claim-form-amount-' + personId()" data-testid="claim-form-amount" formControlName="amount"
        inputmode="decimal" />
      <label [for]="'claim-form-date-' + personId()">Date</label>
      <input [id]="'claim-form-date-' + personId()" type="date" data-testid="claim-form-date" formControlName="date" />
      <label [for]="'claim-form-note-' + personId()">Note</label>
      <input [id]="'claim-form-note-' + personId()" data-testid="claim-form-note" formControlName="note" />
      <label [for]="'claim-form-corrects-' + personId()">Corrects claim number</label>
      <input [id]="'claim-form-corrects-' + personId()" data-testid="claim-form-corrects" formControlName="corrects"
        inputmode="numeric" />
      <label [for]="'claim-form-ref-' + personId()">Reference</label>
      <input [id]="'claim-form-ref-' + personId()" data-testid="claim-form-ref" formControlName="ref" />
      <button type="submit" data-testid="claim-form-submit">{{ editing() === null ? 'Add claim' : 'Save claim' }}</button>
      @if (error()) {
        <p role="alert" data-testid="claim-form-error">{{ error() }}</p>
      }
    </form>
    <ul data-testid="claims">
      @for (c of claims(); track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <input type="checkbox" data-testid="claim-select" [checked]="selected().has(c.id)"
            (change)="selectedChange.emit({ id: c.id, selected: $any($event.target).checked })"
            [attr.aria-label]="'Select claim of ' + c.amount + ' on ' + c.date" />
          <span data-testid="claim-number">{{ c.number ?? '' }}</span>
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span data-testid="claim-corrects">{{ c.corrects ?? '' }}</span>
          <span data-testid="claim-ref">{{ c.ref ?? '' }}</span>
          <span data-testid="claim-due">{{ dueOf(c) }}</span>
          <span data-testid="claim-status">{{ c.status }}</span>
          <span data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</span>
          <span data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</span>
          <span data-testid="claim-period">{{ periodName(c) }}</span>
          <span data-testid="claim-late-entry">{{ c.lateEntry ? 'yes' : 'no' }}</span>
          <span data-testid="claim-due-by">{{ dueByOf(c) }}</span>
          <span data-testid="claim-overdue">{{ overdueOf(c) ? 'yes' : 'no' }}</span>
          <span data-testid="claim-deferred">{{ deferredOf(c) ? 'yes' : 'no' }}</span>
          <span data-testid="claim-deferred-until">{{ c.deferredUntil ?? '' }}</span>
          <span data-testid="claim-proofs">{{ c.proofs }}</span>
          <span data-testid="claim-split-from">{{ c.splitFrom ?? '' }}</span>
          <span data-testid="claim-reminded">{{ c.remindedOn ?? '' }}</span>
          <ol data-testid="claim-log" [attr.aria-label]="'Status log of claim of ' + c.amount + ' on ' + c.date">
            @for (l of c.log; track $index) {
              <li data-testid="claim-log-line">{{ l.status }} by {{ l.by }} on {{ l.on }} due={{ l.due ?? 'n/a' }}</li>
            }
          </ol>
          <button type="button" data-testid="claim-open" (click)="act(c, 'open')"
            [attr.aria-label]="'Open claim of ' + c.amount + ' on ' + c.date">Open</button>
          <button type="button" data-testid="claim-settle" (click)="act(c, 'settle')"
            [attr.aria-label]="'Settle claim of ' + c.amount + ' on ' + c.date">Settle</button>
          <button type="button" data-testid="claim-resettle" (click)="act(c, 'resettle')"
            [attr.aria-label]="'Re-settle claim of ' + c.amount + ' on ' + c.date">Re-settle</button>
          <button type="button" data-testid="claim-void" (click)="act(c, 'void')"
            [attr.aria-label]="'Void claim of ' + c.amount + ' on ' + c.date">Void</button>
          <button type="button" data-testid="claim-approve" (click)="act(c, 'approve')"
            [attr.aria-label]="'Approve claim of ' + c.amount + ' on ' + c.date">Approve</button>
          <label [for]="'claim-reject-reason-' + c.id">Reject reason</label>
          <input [id]="'claim-reject-reason-' + c.id" data-testid="claim-reject-reason" #reason
            (input)="clearError(c)" />
          <button type="button" data-testid="claim-reject" (click)="act(c, 'reject', reason.value)"
            [attr.aria-label]="'Reject claim of ' + c.amount + ' on ' + c.date">Reject</button>
          <button type="button" data-testid="claim-proof" (click)="addProof(c)"
            [attr.aria-label]="'Add a proof to claim of ' + c.amount + ' on ' + c.date">Add proof</button>
          <button type="button" data-testid="claim-remind" (click)="remind(c)"
            [attr.aria-label]="'Remind claim of ' + c.amount + ' on ' + c.date">Remind</button>
          <label [for]="'claim-defer-date-' + c.id">Defer until</label>
          <input [id]="'claim-defer-date-' + c.id" type="date" data-testid="claim-defer-date" #deferDate
            (input)="clearError(c)" />
          <button type="button" data-testid="claim-defer" (click)="defer(c, deferDate.value)"
            [attr.aria-label]="'Defer claim of ' + c.amount + ' on ' + c.date">Defer</button>
          <label [for]="'claim-date-new-' + c.id">New date</label>
          <input [id]="'claim-date-new-' + c.id" type="date" data-testid="claim-date-new" #redateDate
            (input)="clearError(c)" />
          <button type="button" data-testid="claim-redate" (click)="redate(c, redateDate.value)"
            [attr.aria-label]="'Re-date claim of ' + c.amount + ' on ' + c.date">Re-date</button>
          <label [for]="'claim-reassign-to-' + c.id">Reassign to</label>
          <select [id]="'claim-reassign-to-' + c.id" data-testid="claim-reassign-to" #reassignTo
            (change)="clearError(c)">
            <option value="">Choose a person</option>
            @for (o of others(); track o.id) {
              <option [value]="o.id">{{ o.name }}</option>
            }
          </select>
          <button type="button" data-testid="claim-reassign" (click)="reassign(c, reassignTo.value)"
            [attr.aria-label]="'Reassign claim of ' + c.amount + ' on ' + c.date">Reassign</button>
          <label [for]="'claim-split-amount-' + c.id">Split off amount</label>
          <input [id]="'claim-split-amount-' + c.id" data-testid="claim-split-amount" #splitAmount
            inputmode="decimal" (input)="clearError(c)" />
          <button type="button" data-testid="claim-split" (click)="split(c, splitAmount.value)"
            [attr.aria-label]="'Split claim of ' + c.amount + ' on ' + c.date">Split</button>
          <label [for]="'claim-inline-amount-' + c.id">Amount</label>
          <input [id]="'claim-inline-amount-' + c.id" data-testid="claim-inline-amount" #inlineAmount
            inputmode="decimal" (input)="clearError(c)" />
          <button type="button" data-testid="claim-inline-save" (click)="saveAmount(c, inlineAmount.value)"
            [attr.aria-label]="'Save amount of claim of ' + c.amount + ' on ' + c.date">Save amount</button>
          <button type="button" data-testid="claim-edit" (click)="open(c)"
            [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date">Edit</button>
          @if (editing() === c.id) {
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
  private readonly api = inject(Claims);
  private readonly session = inject(Session);

  readonly personId = input.required<number>();
  /** This person's claims, newest first. */
  readonly claims = input.required<Claim[]>();
  /** The rates set on the sheet; a claim's due uses its person's grade's rate on the claim's date. */
  readonly rates = input.required<Rate[]>();
  /** The sheet's floors recorded: the least live due a claim has is the one in force on its date. */
  readonly floor = input.required<DatedValue[]>();
  /** The person's grades; a claim's due uses the one in force on the claim's date. */
  readonly grades = input.required<GradeFrom[]>();
  /** The periods on the sheet; a claim belongs to the one whose days contain its due-by. */
  readonly periods = input.required<Period[]>();
  /** The sheet's today; a claim is overdue once its due-by (plus its grace days) is before it. */
  readonly today = input.required<string | null>();
  /** The sheet's late percents recorded: what an overdue claim gains for every full 30 days it is
   *  overdue is the one in force on its date. */
  readonly latePct = input.required<DatedValue[]>();
  /** The grace days recorded for each grade: a claim is overdue only once its due-by plus those in
   *  force on its date for the person's grade then is before the sheet's today. */
  readonly graces = input.required<Graces>();
  /** The sheet's policy date (null when blank): it decides how a claim's live figures round. */
  readonly policyDate = input.required<string | null>();
  /** The other people on the sheet, whom a claim can be reassigned to. */
  readonly others = input.required<Person[]>();
  /** The ids of the claims selected for a bulk action. */
  readonly selected = input.required<ReadonlySet<number>>();
  /** A claim's checkbox ticked or cleared. */
  readonly selectedChange = output<{ id: number; selected: boolean }>();
  /** A claim added or saved through the form. */
  readonly saved = output<Claim>();

  /** The id of the claim whose editor is open, or null when the form adds a claim. */
  protected readonly editing = signal<number | null>(null);
  protected readonly error = signal('');
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;
  /** The refusal of the last action taken on a claim's row, shown on that row. */
  protected readonly claimError = signal<{ id: number; message: string } | null>(null);
  protected readonly form = new FormGroup({
    amount: new FormControl('', { nonNullable: true }),
    date: new FormControl('', { nonNullable: true }),
    note: new FormControl('', { nonNullable: true }),
    corrects: new FormControl('', { nonNullable: true }),
    ref: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    // The editor the user holds open in the store (it locks the claim) shows open again once the
    // sheet is re-loaded.
    effect(() => {
      const claims = this.claims();
      const user = this.session.user();
      untracked(() => {
        if (this.editing() !== null) return;
        const mine = claims.find((c) => c.status === 'DRAFT' && c.editLockedBy !== null && c.editLockedBy === user);
        if (mine) this.fill(mine);
      });
    });
    // A new sheet's today closes every editor held open.
    let today: string | null | undefined;
    effect(() => {
      const now = this.today();
      if (today !== undefined && now !== today) untracked(() => this.reset());
      today = now;
    });
  }

  protected dueOf(c: Claim): string {
    return claimDue(c, this.rates(), gradeOn(this.grades(), c.date), this.floor(),
      { today: this.today(), pcts: this.latePct(), graces: this.graces(), policyDate: this.policyDate() }) ?? 'n/a';
  }

  protected dueByOf(c: Claim): string {
    return c.dueBy;
  }

  protected overdueOf(c: Claim): boolean {
    return overdue(c, this.today(), graceOn(this.graces(), gradeOn(this.grades(), c.date), c.date));
  }

  protected deferredOf(c: Claim): boolean {
    return deferred(c, this.today());
  }

  /** The name of the period the claim belongs to; empty when it belongs to none. */
  protected periodName(c: Claim): string {
    return periodOf(this.periods(), c.dueBy)?.name ?? '';
  }

  /** Open the claim's editor, which locks it for every other user; refused when it is not a draft,
   *  is due by a day inside a closed period, or another user has its editor open. The editor is open at
   *  once; its fields take the claim's values (those not typed into meanwhile) once the store has
   *  locked the claim, and it closes again when the store refuses. */
  protected async open(c: Claim): Promise<void> {
    this.claimError.set(null);
    if (c.status !== 'DRAFT') {
      this.claimError.set({ id: c.id, message: NOT_DRAFT });
      return;
    }
    if (periodOf(this.periods(), c.dueBy)?.closed) {
      this.claimError.set({ id: c.id, message: CLOSED });
      return;
    }
    if (c.editLockedBy !== null && c.editLockedBy !== this.session.user()) {
      this.claimError.set({ id: c.id, message: EDIT_LOCKED });
      return;
    }
    this.editing.set(c.id);
    this.form.reset({ amount: '', date: '', note: '', corrects: '', ref: '' }, { emitEvent: false });
    this.error.set('');
    try {
      const claim = await this.api.openEditor(c.id);
      if (this.editing() === c.id) {
        const values = { amount: claim.amount, date: claim.date, note: claim.note, corrects: claim.corrects ?? '',
          ref: claim.ref ?? '' };
        for (const key of Object.keys(values) as (keyof typeof values)[]) {
          const control = this.form.controls[key];
          if (!control.dirty) control.setValue(values[key], { emitEvent: false });
        }
      }
      this.saved.emit(claim);
    } catch (e) {
      if (this.editing() === c.id) this.reset();
      this.claimError.set({ id: c.id, message: editorRefusal(e, c) });
    }
  }

  /** Fill the form with the claim's values, as its editor. */
  private fill(c: Claim): void {
    this.editing.set(c.id);
    this.form.setValue({ amount: c.amount, date: c.date, note: c.note, corrects: c.corrects ?? '',
      ref: c.ref ?? '' },
      { emitEvent: false });
    this.error.set('');
  }

  /** Clear the refusal shown on a claim's row once its reject reason is changed. */
  protected clearError(c: Claim): void {
    if (this.claimError()?.id === c.id) this.claimError.set(null);
  }

  protected async act(c: Claim, action: ClaimAction, reason?: string): Promise<void> {
    this.claimError.set(null);
    if (action === 'reject' && (reason ?? '').trim() === '') {
      this.claimError.set({ id: c.id, message: refusal(new HttpErrorResponse({ status: 400 }), action) });
      return;
    }
    try {
      this.saved.emit(await this.api.act(c.id, action, reason?.trim()));
    } catch (e) {
      this.claimError.set({
        id: c.id,
        message: refusal(e, action),
      });
    }
  }

  protected async addProof(c: Claim): Promise<void> {
    this.claimError.set(null);
    try {
      this.saved.emit(await this.api.addProof(c.id));
    } catch (e) {
      this.claimError.set({ id: c.id, message: proofRefusal(e) });
    }
  }

  protected async remind(c: Claim): Promise<void> {
    this.claimError.set(null);
    try {
      this.saved.emit(await this.api.remind(c.id));
    } catch (e) {
      this.claimError.set({ id: c.id, message: remindRefusal(e) });
    }
  }

  protected async defer(c: Claim, until: string): Promise<void> {
    this.claimError.set(null);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(until)) {
      this.claimError.set({ id: c.id, message: DEFER_DATE });
      return;
    }
    try {
      this.saved.emit(await this.api.defer(c.id, until));
    } catch (e) {
      this.claimError.set({ id: c.id, message: deferRefusal(e) });
    }
  }

  protected async redate(c: Claim, date: string): Promise<void> {
    this.claimError.set(null);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.claimError.set({ id: c.id, message: REDATE_DATE });
      return;
    }
    try {
      this.saved.emit(await this.api.redate(c.id, date));
    } catch (e) {
      this.claimError.set({ id: c.id, message: redateRefusal(e) });
    }
  }

  protected async reassign(c: Claim, to: string): Promise<void> {
    this.claimError.set(null);
    if (to === '') {
      this.claimError.set({ id: c.id, message: 'Choose the person to reassign the claim to.' });
      return;
    }
    try {
      this.saved.emit(await this.api.reassign(c.id, Number(to)));
    } catch (e) {
      this.claimError.set({ id: c.id, message: reassignRefusal(e) });
    }
  }

  protected async split(c: Claim, amount: string): Promise<void> {
    this.claimError.set(null);
    const part = amount.trim();
    if (!AMOUNT.test(part) || Number(part) <= 0 || Number(part) >= Number(c.amount)) {
      this.claimError.set({ id: c.id, message: SPLIT_AMOUNT });
      return;
    }
    try {
      const { original, split } = await this.api.split(c.id, part);
      this.saved.emit(original);
      this.saved.emit(split);
    } catch (e) {
      this.claimError.set({ id: c.id, message: splitRefusal(e) });
    }
  }

  /** Change a draft's amount from its row, under the claim form's rules. */
  protected async saveAmount(c: Claim, amount: string): Promise<void> {
    this.claimError.set(null);
    const value = amount.trim();
    if (!CLAIM_AMOUNT.test(value)) {
      this.claimError.set({ id: c.id, message: INLINE_AMOUNT });
      return;
    }
    try {
      const claim = await this.api.editAmount(c.id, value);
      if (this.editing() === c.id) this.reset();
      this.saved.emit(claim);
    } catch (e) {
      this.claimError.set({ id: c.id, message: amountRefusal(e, c, value) });
    }
  }

  /** Cancel the open editor, unlocking its claim. */
  protected async cancel(): Promise<void> {
    const id = this.editing();
    if (id === null) return;
    if (this.claimError()?.id === id) this.claimError.set(null);
    try {
      this.saved.emit(await this.api.cancelEditor(id));
    } catch {
      // the editor closes all the same; a lock left behind ends when the sheet's today changes
    }
    if (this.editing() === id) this.reset();
  }

  protected async submit(): Promise<void> {
    if (this.submitting) return;
    this.error.set('');
    this.claimError.set(null);
    const { amount, date, note, corrects, ref } = this.form.getRawValue();
    if (!CLAIM_AMOUNT.test(amount.trim())) {
      this.error.set('Enter an amount with up to two decimals; a negative amount is a correction.');
      return;
    }
    const number = corrects.trim();
    if (number.length > 120) {
      this.error.set(CORRECTS);
      return;
    }
    const reference = ref.trim();
    if (reference.length > 120) {
      this.error.set(REF_LONG);
      return;
    }
    const day = date.trim();
    if (day !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(day)) {
      this.error.set('Enter a date, or leave it blank for the sheet\'s today.');
      return;
    }
    const form = {
      amount: amount.trim(), date: day === '' ? null : day, note, corrects: number === '' ? null : number,
      ref: reference === '' ? null : reference,
    };
    const id = this.editing();
    this.submitting = true;
    try {
      const claim = id === null ? await this.api.add(this.personId(), form) : await this.api.edit(id, form);
      this.saved.emit(claim);
      this.reset();
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 423) this.error.set(CLOSED);
      else if (e instanceof HttpErrorResponse && e.status === 422) this.error.set(RETIRED);
      else if (e instanceof HttpErrorResponse && e.status === 400 && amount.trim().startsWith('-')) this.error.set(CORRECTS);
      else if (e instanceof HttpErrorResponse && e.status === 409 && reference !== '' && this.refClash(id)) {
        this.error.set(REF_USED);
      }
      else if (id !== null && e instanceof HttpErrorResponse && e.status === 409) {
        const c = this.claims().find((x) => x.id === id);
        this.claimError.set({ id, message: c ? editorRefusal(e, c) : NOT_DRAFT });
      }
      else this.error.set(id === null ? 'The claim could not be added.' : 'The claim could not be saved.');
    } finally {
      this.submitting = false;
    }
  }

  /** Whether a conflict on saving the form (an add when `id` is null) can only be its reference
   *  being in use: an add, or an edit of a draft no other user is editing. */
  private refClash(id: number | null): boolean {
    if (id === null) return true;
    const c = this.claims().find((x) => x.id === id);
    return c !== undefined && c.status === 'DRAFT'
      && (c.editLockedBy === null || c.editLockedBy === this.session.user());
  }

  private reset(): void {
    this.editing.set(null);
    this.form.reset({ amount: '', date: '', note: '', corrects: '', ref: '' }, { emitEvent: false });
    this.error.set('');
  }
}
