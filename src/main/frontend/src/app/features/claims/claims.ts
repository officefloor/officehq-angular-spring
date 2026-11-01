import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Late, Session } from '../../core/session';
import { Period, closedOn, periodOn } from '../periods/periods';
import { NA, dueCents } from '../rates/rates';

/**
 * DRAFT when added, then OPEN, then SETTLED; a clerk's settling above the clerk limit makes it
 * PENDING until a steward approves it (SETTLED) or rejects it (OPEN). A DRAFT, OPEN or PENDING
 * claim can be made VOID.
 */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'PENDING' | 'SETTLED' | 'VOID';

/** A status change a claim's buttons ask for. */
type ClaimAction = 'open' | 'settle' | 'void' | 'approve' | 'reject' | 'resettle';

export interface Claim {
  id: number;
  personId: number;
  /** Always two decimals, e.g. "2.50". */
  amount: string;
  date: string;
  note: string;
  status: ClaimStatus;
  /** Given when the claim is opened and kept once VOID; null while DRAFT. */
  /** The sheet-wide number, or <period name>-<n> (X-<n> in no period) when numbered by period; null while DRAFT. */
  number: string | null;
  /** The due fixed when it was settled, two decimals; null while its due is live. */
  settledDue: string | null;
  /** The user who opened it; null while DRAFT. They cannot settle it. */
  openedBy: string | null;
  /** Bound by its person's last statement, to the due it had then; not while PENDING, when its due is live. */
  stated: boolean;
  /** The due bound by its person's last statement, two decimals; null while not stated, or stated with none. */
  statedDue: string | null;
  /** A correction's (negative amount's) number of the SETTLED claim it corrects; null for any other claim. */
  corrects: string | null;
  /** The date it is due by (yyyy-mm-dd), fixed when it was added; it does not move with its date or the terms. */
  dueBy: string;
  /** Its status log, oldest first: a line for each status it has had, DRAFT when added. */
  log: ClaimLogLine[];
  /** The date (yyyy-mm-dd) it is deferred until; null unless deferred. Deferred only while OPEN and before it. */
  deferredUntil: string | null;
  /** How many proofs it has; one above the proof threshold in force on its date needs one to be settled or approved. */
  proofs: number;
  /** Its external reference, unique on the sheet without regard to case; null when it has none. */
  ref: string | null;
  /** The reason it was voided with, from the sheet's list; null unless voided with one. */
  voidReason: string | null;
  /** Its tags, trimmed, lower-cased, each once and sorted; empty when it has none. */
  tags: string[];
  /** The number of the claim it was split off; null unless split off one. */
  splitFrom: string | null;
  /** The user whose open editor locks it for everyone else; null while no editor holds it. */
  lockedBy: string | null;
  /** Whether it was added while the period its due-by falls in was soft-closed. */
  lateEntry: boolean;
  /** The last sheet's today (yyyy-mm-dd) a reminder was written for it; null until reminded. */
  remindedOn: string | null;
  /** The number of the bulk settle batch that settled it; null when settled one at a time or not settled. */
  batch: number | null;
  /** Its comments, oldest first. */
  comments: ClaimComment[];
}

/** A comment on a claim: who wrote it, the sheet's today (yyyy-mm-dd) when written, and its text. */
export interface ClaimComment {
  by: string;
  date: string;
  text: string;
}

/** A status a claim was given, by whom, on the sheet's today then, with its due then (null: none). */
export interface ClaimLogLine {
  status: ClaimStatus;
  by: string;
  date: string;
  due: string | null;
}

/** A person's rate on a date: the rate then of the grade they had then, or null when it has none. */
export type PctOn = (date: string) => string | null;

/** A person's grace days for a claim dated on a date: those of the grade they had then, in force then; 0 when none. */
export type GraceOn = (date: string) => number;

/** The sheet's floor in force on a date, in cents. */
export type FloorOn = (date: string) => number;

/** Open: OPEN, or PENDING, which is handled like it. */
export function isOpen(c: Claim): boolean {
  return c.status === 'OPEN' || c.status === 'PENDING';
}

/** Only open (OPEN or PENDING) and SETTLED claims count toward what is claimed and due. */
export function counts(c: Claim): boolean {
  return isOpen(c) || c.status === 'SETTLED';
}

/** Whether its due is now bound by its person's last statement: stated and not PENDING. */
export function isBound(c: Claim): boolean {
  return c.stated && c.status !== 'PENDING';
}

/**
 * Deferred: OPEN with a deferred-until date after the sheet's today. It counts in no total, is not
 * overdue, cannot be settled and is left unbound by a statement until today reaches that date.
 */
export function isDeferred(c: Claim, today: string | null): boolean {
  return c.status === 'OPEN' && c.deferredUntil !== null && today !== null && today < c.deferredUntil;
}

/**
 * Whether the claim counts for its person, given whether its date falls within one of their holds and
 * the sheet's today: an open claim dated within a hold does not, unless bound by a statement, nor does a deferred one.
 */
export function countsFor(c: Claim, heldOnDate: boolean, today: string | null): boolean {
  return counts(c) && (!heldOnDate || !isOpen(c) || isBound(c)) && !isDeferred(c, today);
}

/**
 * The status the claim had at the end of the date, as its status log records it: the last line dated
 * on or before it; null when it had none then (added after it). A claim with no log lines (added
 * before the log was kept) is taken to have had its status now.
 */
export function statusAt(c: Claim, date: string): ClaimStatus | null {
  if (c.log.length === 0) return c.status;
  let at: ClaimStatus | null = null;
  for (const line of c.log) if (line.date <= date) at = line.status;
  return at;
}

/**
 * Whether the claim counts for a period ending on `end`: by the status it had then (open or SETTLED),
 * less a deferred one and one still open now (OPEN or PENDING) dated within a hold and not bound, as the
 * live rules say: a claim since settled or voided is no longer kept out by a hold.
 */
export function countsAt(c: Claim, end: string, heldOnDate: boolean, today: string | null): boolean {
  const status = statusAt(c, end);
  if (status === null) return false;
  const counts = status === 'OPEN' || status === 'PENDING' || status === 'SETTLED';
  const openNow = c.status === 'OPEN' || c.status === 'PENDING';
  return counts && (!heldOnDate || !openNow || isBound(c)) && !isDeferred(c, today);
}

/** A claim's amount as cents, so sums are exact; a correction's is negative. */
export function cents(amount: string): number {
  const negative = amount.startsWith('-');
  const [whole, frac = ''] = (negative ? amount.slice(1) : amount).split('.');
  const size = Number(whole) * 100 + Number(frac.padEnd(2, '0'));
  return negative ? -size : size;
}

/** A correction: a claim with a negative amount. Its due has no floor and no late charge. */
export function isCorrection(c: Claim): boolean {
  return cents(c.amount) < 0;
}

/**
 * The date a claim's live figures round by: a correction's corrected claim's date (found among its
 * person's claims, `theirs`), else its own.
 */
export function roundsOn(c: Claim, theirs: Claim[]): string {
  if (c.corrects === null) return c.date;
  return theirs.find((o) => o.number === c.corrects)?.date ?? c.date;
}

/** Whether a claim rounding by the given date rounds half up: dated before the policy date; else half to even. */
function halfUp(basis: string, late: Late): boolean {
  return late.policy !== null && basis < late.policy;
}

/**
 * A claim's due in cents: the due fixed when it was settled (late charge frozen in), else the greater of
 * the floor in force on its date (`floorOn`, in cents) and its figure — the rate figure bound by its
 * person's last statement, else its amount times its person's rate on its date (as `pctOn` gives it) —
 * with its late charge (by the late percent in force on each 30-day boundary) on top; a correction has
 * no floor and no charge. Null when it has none. Its live figures round by the date `basis` (see {@link roundsOn}).
 * Its grace days (`graceOn`) put off when it is overdue and its charge's 30-day boundaries.
 */
export function claimDueCents(c: Claim, pctOn: PctOn, floorOn: FloorOn, late: Late, basis: string, graceOn: GraceOn): number | null {
  if (c.settledDue !== null) return cents(c.settledDue);
  const up = halfUp(basis, late);
  const figure = figureCents(c, pctOn, up);
  if (figure === null) return null;
  const charged = figure + lateChargeCents(c, figure, late, up, graceOn);
  return isCorrection(c) ? charged : Math.max(charged, floorOn(c.date));
}

/** A claim's figure in cents: the rate figure bound by its person's last statement, else its rate figure (no floor). */
function figureCents(c: Claim, pctOn: PctOn, halfUp: boolean): number | null {
  if (isBound(c)) return c.statedDue === null ? null : cents(c.statedDue);
  const pct = pctOn(c.date);
  if (pct === null) return null;
  return dueCents(cents(c.amount), pct, halfUp);
}

/**
 * The late charge in cents on an overdue claim with the given figure, worked out per 30-day boundary: for each full
 * 30 days past its due-by plus its grace days, the late percent in force on that boundary date (the due-by plus the
 * grace plus 30, 60, 90 ... days) of it plus the charges of the boundaries before it (compounding), rounded like a
 * due, and the charges added up; 0 when not overdue or a
 * correction. Never bound by a statement.
 */
export function lateChargeCents(c: Claim, figureCents: number, late: Late, halfUp: boolean, graceOn: GraceOn): number {
  if (late.today === null || isCorrection(c) || !isOverdue(c, late.today, graceOn)) return 0;
  const dueBy = Date.parse(`${lateFrom(c, graceOn)}T00:00:00Z`);
  let total = 0;
  for (let step = 1; ; step++) {
    const boundary = new Date(dueBy + step * 30 * 86_400_000).toISOString().slice(0, 10);
    if (boundary > late.today) return total;
    total += dueCents(figureCents + total, late.pctOn(boundary), halfUp);
  }
}

/** The date (yyyy-mm-dd) past which a claim is late: its due-by plus its grace days; the due-by shown is unchanged. */
export function lateFrom(c: Claim, graceOn: GraceOn): string {
  const grace = graceOn(c.date);
  if (grace === 0) return c.dueBy;
  return new Date(Date.parse(`${c.dueBy}T00:00:00Z`) + grace * 86_400_000).toISOString().slice(0, 10);
}

/** Overdue: OPEN or PENDING, not deferred, with its due-by plus its grace days before the sheet's today. */
export function isOverdue(c: Claim, today: string | null, graceOn: GraceOn): boolean {
  return isOpen(c) && today !== null && lateFrom(c, graceOn) < today && !isDeferred(c, today);
}

/** Why a change to a claim due inside a closed period is refused. */
const CLOSED = 'No claim due inside a closed period can be changed.';

/** Why a claim dated after its person's retirement date is refused. */
export const RETIRED = 'No claim can be dated after its person\'s retirement date.';

/** Cents as an amount with two decimals; a minus sign when negative. */
export function money(cents: number): string {
  const size = Math.abs(cents);
  return `${cents < 0 ? '-' : ''}${Math.floor(size / 100)}.${String(size % 100).padStart(2, '0')}`;
}

/** What each action makes of a claim, for why it was refused. */
/** Why a void without a reason from the sheet's list is refused. */
const VOID_REASON_NEEDED = 'Choose a reason from the sheet\'s void reasons to void the claim.';

const PAST: Record<ClaimAction, string> = {
  open: 'opened', settle: 'settled', void: 'voided', approve: 'approved', reject: 'rejected',
  resettle: 're-settled',
};

/** Why a claim's correction is refused. */
const CORRECTION = 'A negative amount is a correction: name the number of a SETTLED claim of this person that it corrects.';

/** Why a claim's reference is refused. */
export const DUPLICATE_REF = 'That reference is already used on this sheet (whatever its case).';

/** Whether the server refused a reference already used on the sheet. */
export function isDuplicateRef(e: unknown): boolean {
  return e instanceof HttpErrorResponse && e.status === 409 && e.error?.type === 'duplicate-ref';
}

/** Why a claim cannot be edited: only a DRAFT can; an OPEN or PENDING one is voided and added again. */
function notEditable(status: ClaimStatus | undefined): string {
  return status === 'OPEN' || status === 'PENDING'
    ? `An ${status} claim cannot be edited; void it and add a new one.`
    : `${status ? 'A ' + status : 'This'} claim cannot be edited; only a DRAFT can.`;
}

// One person's claims, newest first, with one form to add a claim or edit one, and buttons to
// open, settle or void each claim, approve or reject (with a reason) a PENDING one, re-settle a SETTLED one, move one to another person, split an amount off an OPEN one, and a checkbox to select each for a bulk change.
@Component({
  selector: 'app-claims',
  imports: [ReactiveFormsModule],
  template: `
    <form [formGroup]="form" (ngSubmit)="save()" [attr.aria-label]="(editingId() === null ? 'Add claim for ' : 'Edit claim for ') + personName()">
      <label [for]="fieldId('amount')">Amount</label>
      <input [id]="fieldId('amount')" data-testid="claim-form-amount" formControlName="amount" inputmode="decimal" />
      <label [for]="fieldId('date')">Date</label>
      <input [id]="fieldId('date')" type="date" data-testid="claim-form-date" formControlName="date" />
      <label [for]="fieldId('note')">Note</label>
      <input [id]="fieldId('note')" data-testid="claim-form-note" formControlName="note" />
      <label [for]="fieldId('corrects')">Corrects claim no.</label>
      <input [id]="fieldId('corrects')" data-testid="claim-form-corrects" formControlName="corrects" />
      <label [for]="fieldId('ref')">Reference</label>
      <input [id]="fieldId('ref')" data-testid="claim-form-ref" formControlName="ref" />
      <label [for]="fieldId('tags')">Tags (comma separated)</label>
      <input [id]="fieldId('tags')" data-testid="claim-form-tags" formControlName="tags" />
      <button type="submit" data-testid="claim-form-submit">{{ editingId() === null ? 'Add claim' : 'Save claim' }}</button>
      @if (error()) {
        <p role="alert" class="error" data-testid="claim-form-error">{{ error() }}</p>
      }
    </form>
    <ul [attr.aria-label]="'Claims for ' + personName()">
      @for (c of claims(); track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <input type="checkbox" data-testid="claim-select" [checked]="selected().has(c.id)"
                 (change)="toggled.emit({ id: c.id, checked: $any($event.target).checked })"
                 [attr.aria-label]="'Select claim of ' + c.amount + ' on ' + c.date" />
          <span>No. <span data-testid="claim-number">{{ c.number ?? '' }}</span></span>
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span>Ref <span data-testid="claim-ref">{{ c.ref ?? '' }}</span></span>
          <span>Tags <span data-testid="claim-tags">{{ c.tags.join(', ') }}</span></span>
          <span>Period <span data-testid="claim-period">{{ periodOf(c) }}</span></span>
          <span>Due <span data-testid="claim-due">{{ dueOf(c) }}</span></span>
          <span data-testid="claim-status">{{ c.status }}</span>
          <span>Opened by <span data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</span></span>
          <span>Stated <span data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</span></span>
          <span>Due by <span data-testid="claim-due-by">{{ c.dueBy }}</span></span>
          <span>Overdue <span data-testid="claim-overdue">{{ overdueOf(c) ? 'yes' : 'no' }}</span></span>
          <span>Deferred <span data-testid="claim-deferred">{{ deferredOf(c) ? 'yes' : 'no' }}</span></span>
          <span>Deferred until <span data-testid="claim-deferred-until">{{ c.deferredUntil ?? '' }}</span></span>
          <span>Proofs <span data-testid="claim-proofs">{{ c.proofs }}</span></span>
          <span>Corrects <span data-testid="claim-corrects">{{ c.corrects ?? '' }}</span></span>
          <span>Split from <span data-testid="claim-split-from">{{ c.splitFrom ?? '' }}</span></span>
          <span>Late entry <span data-testid="claim-late-entry">{{ c.lateEntry ? 'yes' : 'no' }}</span></span>
          <span>Reminded <span data-testid="claim-reminded">{{ c.remindedOn ?? '' }}</span></span>
          <span>Batch <span data-testid="claim-batch">{{ c.batch ?? '' }}</span></span>
          <ol [attr.aria-label]="'Status log of claim of ' + c.amount + ' on ' + c.date">
            @for (line of c.log; track $index) {
              <li data-testid="claim-log-line">{{ line.status }} by {{ line.by }} on {{ line.date }} due={{ line.due ?? na }}</li>
            }
          </ol>
          <ol [attr.aria-label]="'Comments on claim of ' + c.amount + ' on ' + c.date">
            @for (comment of c.comments; track $index) {
              <li data-testid="claim-comments-line">{{ comment.by }} on {{ comment.date }}: {{ comment.text }}</li>
            }
          </ol>
          <label [for]="fieldId('comment-' + c.id)">Comment</label>
          <input [id]="fieldId('comment-' + c.id)" data-testid="claim-comment-text" #commentText
                 (input)="clearClaimError(c.id)" />
          <button type="button" data-testid="claim-comment-add" (click)="addComment(c, commentText)"
                  [attr.aria-label]="'Comment on claim of ' + c.amount + ' on ' + c.date">Add comment</button>
          <button type="button" data-testid="claim-open" (click)="act(c, 'open')"
                  [attr.aria-label]="'Open claim of ' + c.amount + ' on ' + c.date">Open</button>
          <button type="button" data-testid="claim-settle" (click)="act(c, 'settle')"
                  [attr.aria-label]="'Settle claim of ' + c.amount + ' on ' + c.date">Settle</button>
          @if (session.voidReasons().length > 0) {
            <label [for]="fieldId('void-' + c.id)">Void reason</label>
            <select [id]="fieldId('void-' + c.id)" data-testid="claim-void-reason" #voidReason
                    (change)="clearClaimError(c.id)">
              <option value="">Choose a reason</option>
              @for (r of session.voidReasons(); track r) {
                <option [value]="r">{{ r }}</option>
              }
            </select>
            <button type="button" data-testid="claim-void" (click)="act(c, 'void', voidReason.value)"
                    [attr.aria-label]="'Void claim of ' + c.amount + ' on ' + c.date">Void</button>
          } @else {
            <button type="button" data-testid="claim-void" (click)="act(c, 'void')"
                    [attr.aria-label]="'Void claim of ' + c.amount + ' on ' + c.date">Void</button>
          }
          <button type="button" data-testid="claim-approve" (click)="act(c, 'approve')"
                  [attr.aria-label]="'Approve claim of ' + c.amount + ' on ' + c.date">Approve</button>
          <button type="button" data-testid="claim-resettle" (click)="act(c, 'resettle')"
                  [attr.aria-label]="'Re-settle claim of ' + c.amount + ' on ' + c.date">Re-settle</button>
          <label [for]="fieldId('reject-' + c.id)">Reject reason</label>
          <input [id]="fieldId('reject-' + c.id)" data-testid="claim-reject-reason" #rejectReason />
          <button type="button" data-testid="claim-reject" (click)="act(c, 'reject', rejectReason.value)"
                  [attr.aria-label]="'Reject claim of ' + c.amount + ' on ' + c.date">Reject</button>
          <button type="button" data-testid="claim-proof" (click)="addProof(c)"
                  [attr.aria-label]="'Add a proof to claim of ' + c.amount + ' on ' + c.date">Add proof</button>
          <button type="button" data-testid="claim-remind" (click)="remind(c)"
                  [attr.aria-label]="'Remind claim of ' + c.amount + ' on ' + c.date">Remind</button>
          <label [for]="fieldId('defer-' + c.id)">Defer until</label>
          <input [id]="fieldId('defer-' + c.id)" type="date" data-testid="claim-defer-date" #deferDate
                 (input)="clearClaimError(c.id)" />
          <button type="button" data-testid="claim-defer" (click)="defer(c, deferDate.value)"
                  [attr.aria-label]="'Defer claim of ' + c.amount + ' on ' + c.date">Defer</button>
          <label [for]="fieldId('redate-' + c.id)">New date</label>
          <input [id]="fieldId('redate-' + c.id)" type="date" data-testid="claim-date-new" #redateDate
                 (input)="clearClaimError(c.id)" />
          <button type="button" data-testid="claim-redate" (click)="redate(c, redateDate.value)"
                  [attr.aria-label]="'Re-date claim of ' + c.amount + ' on ' + c.date">Re-date</button>
          <label [for]="fieldId('reassign-' + c.id)">Move to</label>
          <select [id]="fieldId('reassign-' + c.id)" data-testid="claim-reassign-to" #reassignTo
                  (change)="clearClaimError(c.id)">
            @for (o of others(); track o.id) {
              <option [value]="o.id">{{ o.name }}</option>
            }
          </select>
          <button type="button" data-testid="claim-reassign" (click)="reassign(c, reassignTo.value)"
                  [attr.aria-label]="'Move claim of ' + c.amount + ' on ' + c.date">Move</button>
          <label [for]="fieldId('split-' + c.id)">Split off</label>
          <input [id]="fieldId('split-' + c.id)" data-testid="claim-split-amount" inputmode="decimal" #splitAmount
                 (input)="clearClaimError(c.id)" />
          <button type="button" data-testid="claim-split" (click)="split(c, splitAmount.value)"
                  [attr.aria-label]="'Split claim of ' + c.amount + ' on ' + c.date">Split</button>
          @if (editingId() === c.id) {
            <button type="button" data-testid="claim-cancel" (click)="cancel()">Cancel</button>
          } @else {
            <button type="button" data-testid="claim-edit" (click)="edit(c)"
                    [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date">Edit</button>
          }
          <label [for]="fieldId('inline-' + c.id)">New amount</label>
          <input [id]="fieldId('inline-' + c.id)" data-testid="claim-inline-amount" inputmode="decimal" #inlineAmount
                 (input)="clearClaimError(c.id)" />
          <button type="button" data-testid="claim-inline-save" (click)="saveAmount(c, inlineAmount)"
                  [attr.aria-label]="'Save amount of claim of ' + c.amount + ' on ' + c.date">Save amount</button>
          @if (claimError()?.id === c.id) {
            <p role="alert" class="error" data-testid="claim-error">{{ claimError()?.message }}</p>
          }
        </li>
      }
    </ul>
  `,
})
export class Claims {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(Session);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  readonly claims = input.required<Claim[]>();
  /** The person's rate on a date, of the grade they had then. */
  readonly pctOn = input.required<PctOn>();
  /** The person's grace days for a claim dated on a date, of the grade they had then. */
  readonly graceOn = input.required<GraceOn>();
  /** The sheet's periods, for the one each claim's due-by falls in. */
  readonly periods = input.required<Period[]>();
  /** A claim added, edited or given a new status, as the store now holds it. */
  readonly saved = output<Claim>();
  /** The other people on the sheet a claim may be moved to. */
  readonly others = input.required<{ id: number; name: string }[]>();
  /** A claim moved to another person, as the store now holds it. */
  readonly moved = output<Claim>();
  /** The ids of the claims selected for a bulk change. */
  readonly selected = input.required<ReadonlySet<number>>();
  /** A claim's checkbox ticked or cleared. */
  readonly toggled = output<{ id: number; checked: boolean }>();

  protected readonly na = NA;
  protected readonly editingId = signal<number | null>(null);
  /** A submit is under way; another one waits for its answer rather than storing twice. */
  private saving = false;
  /** The lock the editor last opened is taking; a save of it waits for it, and is dropped if it was refused. */
  private locking: Promise<boolean> = Promise.resolve(true);
  protected readonly error = signal('');
  /** The error from the last action on a claim's row, if it was refused. */
  protected readonly claimError = signal<{ id: number; message: string } | null>(null);
  protected readonly form = new FormGroup({
    amount: new FormControl('', { nonNullable: true }),
    date: new FormControl('', { nonNullable: true }),
    note: new FormControl('', { nonNullable: true }),
    corrects: new FormControl('', { nonNullable: true }),
    ref: new FormControl('', { nonNullable: true }),
    tags: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    // An editor this user left open (its lock survives a re-load of the sheet) opens again.
    effect(() => {
      const claims = this.claims();
      untracked(() => {
        if (this.editingId() !== null) return;
        const user = this.session.user();
        const mine = claims.find((c) => c.status === 'DRAFT' && user !== null && c.lockedBy === user);
        if (mine) this.openEditor(mine);
      });
    });
  }

  protected fieldId(field: string): string {
    return `claim-${this.personId()}-${field}`;
  }

  /** The due fixed when it was settled, else its figure with its late charge on top. */
  protected dueOf(c: Claim): string {
    const due = claimDueCents(c, this.pctOn(), this.session.floorCentsOn, this.session.late(), roundsOn(c, this.claims()), this.graceOn());
    return due === null ? NA : money(due);
  }

  protected overdueOf(c: Claim): boolean {
    return isOverdue(c, this.session.today(), this.graceOn());
  }

  protected deferredOf(c: Claim): boolean {
    return isDeferred(c, this.session.today());
  }

  /** The name of the period its due-by falls in, or blank when none. */
  protected periodOf(c: Claim): string {
    return periodOn(this.periods(), c.dueBy)?.name ?? '';
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }

  private reset(): void {
    this.editingId.set(null);
    this.form.reset({ amount: '', date: '', note: '', corrects: '', ref: '', tags: '' }, { emitEvent: false });
    this.error.set('');
  }

  private openEditor(c: Claim): void {
    this.editingId.set(c.id);
    this.form.setValue({ amount: c.amount, date: c.date, note: c.note, corrects: c.corrects ?? '',
      ref: c.ref ?? '', tags: c.tags.join(', ') },
      { emitEvent: false });
    this.error.set('');
  }

  /**
   * Open a claim's editor, locking the claim for every other user until it is saved or cancelled; a claim
   * that cannot be edited, one another user's editor locks, or a user whose role may change nothing is refused.
   */
  protected async edit(c: Claim): Promise<void> {
    this.claimError.set(null);
    if (closedOn(this.periods(), c.dueBy)) {
      this.claimError.set({ id: c.id, message: CLOSED });
      return;
    }
    if (c.status !== 'DRAFT') {
      this.claimError.set({ id: c.id, message: notEditable(c.status) });
      return;
    }
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const open = this.editingId();
    // The editor opens at once with the claim as shown, so what is typed while the lock is taken is
    // kept; a save waits for the lock, and a refused lock closes the editor again.
    this.openEditor(c);
    const locking = this.lock(sheet, c, open);
    this.locking = locking;
    await locking;
  }

  /** Take the lock of the claim whose editor has opened, after releasing the one left open; whether it was taken. */
  private async lock(sheet: string, c: Claim, open: number | null): Promise<boolean> {
    if (open !== null && open !== c.id) await this.release(sheet, open);
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/edit`;
    try {
      const claim = await firstValueFrom(this.http.post<Claim>(url, null, { headers: this.headers() }));
      if (this.session.sheetId() !== sheet) return false;
      this.saved.emit(claim);
      return true;
    } catch (e) {
      if (this.session.sheetId() !== sheet) return false;
      if (this.editingId() === c.id) this.reset();
      const message = e instanceof HttpErrorResponse && e.status === 423
        ? CLOSED
        : e instanceof HttpErrorResponse && e.status === 409
        ? notEditable(undefined)
        : e instanceof HttpErrorResponse && e.status === 412
        ? 'Another user is editing this claim; it can be edited once they save or cancel.'
        : e instanceof HttpErrorResponse && e.status === 403
        ? 'Your role may not edit a claim on this sheet.'
        : 'Could not edit that claim.';
      this.claimError.set({ id: c.id, message });
      return false;
    }
  }

  /** Close the editor, releasing the claim's lock. */
  protected async cancel(): Promise<void> {
    const sheet = this.session.sheetId();
    const id = this.editingId();
    this.claimError.set(null);
    if (sheet && id !== null) await this.release(sheet, id);
    if (this.session.sheetId() !== sheet) return;
    this.reset();
  }

  /** Release this user's lock on a claim whose editor closes. */
  private async release(sheet: string, id: number): Promise<void> {
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${id}/cancel`;
    try {
      const claim = await firstValueFrom(this.http.post<Claim>(url, null, { headers: this.headers() }));
      if (this.session.sheetId() === sheet) this.saved.emit(claim);
    } catch {
      // the lock goes anyway when the sheet's today changes
    }
  }

  /**
   * Open, settle or void a claim, approve or reject (with a reason) a PENDING one, re-settle a
   * SETTLED one at its figure today, or (a steward, within 7 days of settling) void it; a change its
   * status does not allow is refused.
   */
  protected async act(c: Claim, action: ClaimAction, reason = ''): Promise<void> {
    this.claimError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const why = reason.trim().replace(/\s+/g, ' ');
    if (action === 'reject' && (why === '' || why.length > 200)) {
      this.claimError.set({ id: c.id, message: 'Give a reason for rejecting the claim (at most 200 characters).' });
      return;
    }
    if (action === 'void' && this.session.voidReasons().length > 0 && !this.session.voidReasons().includes(why)) {
      this.claimError.set({ id: c.id, message: VOID_REASON_NEEDED });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/${action}`;
    const body = action === 'reject' || (action === 'void' && why !== '') ? { reason: why } : null;
    try {
      const claim = await firstValueFrom(this.http.post<Claim>(url, body, { headers: this.headers() }));
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(claim);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const message = e instanceof HttpErrorResponse && e.status === 423
        ? CLOSED
        : e instanceof HttpErrorResponse && e.status === 409
        ? `A ${c.status} claim cannot be ${PAST[action]}.`
        : e instanceof HttpErrorResponse && e.status === 410
        ? 'A SETTLED claim can be voided only within 7 days of its settlement.'
        : e instanceof HttpErrorResponse && e.status === 412
        ? `A deferred claim cannot be settled until ${c.deferredUntil}.`
        : e instanceof HttpErrorResponse && e.status === 428
        ? `A claim due above the proof threshold cannot be ${PAST[action]} without a proof.`
        : e instanceof HttpErrorResponse && e.status === 422 && action === 'void'
        ? VOID_REASON_NEEDED
        : e instanceof HttpErrorResponse && e.status === 422 && action === 'open'
        ? 'A claim due above the note threshold cannot be opened with an empty note.'
        : e instanceof HttpErrorResponse && e.status === 403
        ? action === 'settle' && c.openedBy === this.session.user()
          ? 'The user who opened a claim cannot settle it.'
          : action === 'void' && c.status === 'SETTLED'
          ? 'Only a steward can void a SETTLED claim.'
          : action === 'open' || action === 'settle' || action === 'void'
          ? `Your role may not ${action} a claim on this sheet.`
          : action === 'approve' && c.openedBy === this.session.user()
          ? 'The user who opened a claim cannot approve it.'
          : action === 'resettle'
          ? 'Only a steward can re-settle a SETTLED claim.'
          : 'Only a steward can approve or reject a PENDING claim.'
        : 'Could not change that claim.';
      this.claimError.set({ id: c.id, message });
    }
  }

  protected clearClaimError(id: number): void {
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  /** Raise a claim's proofs count by one; a SETTLED or VOID claim, or one due inside a closed period, is refused. */
  protected async addProof(c: Claim): Promise<void> {
    this.claimError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/proof`;
    try {
      const claim = await firstValueFrom(this.http.post<Claim>(url, null, { headers: this.headers() }));
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(claim);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const message = e instanceof HttpErrorResponse && e.status === 423
        ? CLOSED
        : e instanceof HttpErrorResponse && e.status === 409
        ? `A ${c.status} claim cannot be given a proof.`
        : 'Could not add a proof to that claim.';
      this.claimError.set({ id: c.id, message });
    }
  }

  /** Comment on a claim; an empty comment is refused. */
  protected async addComment(c: Claim, field: HTMLInputElement): Promise<void> {
    this.claimError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const text = field.value.trim();
    if (text === '') {
      this.claimError.set({ id: c.id, message: 'Enter a comment.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/comments`;
    try {
      const claim = await firstValueFrom(this.http.post<Claim>(url, { text }, { headers: this.headers() }));
      if (this.session.sheetId() !== sheet) return;
      field.value = '';
      this.saved.emit(claim);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const message = e instanceof HttpErrorResponse && e.status === 400
        ? 'Enter a comment (at most 500 characters).'
        : 'Could not comment on that claim.';
      this.claimError.set({ id: c.id, message });
    }
  }

  /** Remind an OPEN or PENDING claim, once per sheet's today; any other claim, or a second reminder that day, is refused. */
  protected async remind(c: Claim): Promise<void> {
    this.claimError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/remind`;
    try {
      const claim = await firstValueFrom(this.http.post<Claim>(url, null, { headers: this.headers() }));
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(claim);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const message = e instanceof HttpErrorResponse && e.status === 409
        ? (c.status === 'OPEN' || c.status === 'PENDING'
          ? 'This claim was already reminded today.'
          : `A ${c.status} claim cannot be reminded; only an OPEN or PENDING one can.`)
        : 'Could not remind that claim.';
      this.claimError.set({ id: c.id, message });
    }
  }

  /**
   * Defer an OPEN claim until a date after the sheet's today; a claim that is not OPEN, a date not after
   * today, or a claim due inside a closed period is refused.
   */
  protected async defer(c: Claim, until: string): Promise<void> {
    this.claimError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const date = until.trim();
    const today = this.session.today();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || (today !== null && date <= today)) {
      this.claimError.set({ id: c.id, message: 'Enter a date after today to defer the claim until.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/defer`;
    try {
      const claim = await firstValueFrom(this.http.post<Claim>(url, { until: date }, { headers: this.headers() }));
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(claim);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const message = e instanceof HttpErrorResponse && e.status === 423
        ? CLOSED
        : e instanceof HttpErrorResponse && e.status === 409
        ? `A ${c.status} claim cannot be deferred; only an OPEN one can.`
        : e instanceof HttpErrorResponse && e.status === 422
        ? 'Enter a date after today to defer the claim until.'
        : 'Could not defer that claim.';
      this.claimError.set({ id: c.id, message });
    }
  }

  /**
   * A steward re-dates an OPEN or PENDING claim; anyone else, any other claim, a claim due inside a closed
   * period, or a new date after its person's retirement is refused.
   */
  protected async redate(c: Claim, value: string): Promise<void> {
    this.claimError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const date = value.trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.claimError.set({ id: c.id, message: 'Enter the new date of the claim.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/redate`;
    try {
      const claim = await firstValueFrom(this.http.post<Claim>(url, { date }, { headers: this.headers() }));
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(claim);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const message = e instanceof HttpErrorResponse && e.status === 423
        ? CLOSED
        : e instanceof HttpErrorResponse && e.status === 409
        ? `A ${c.status} claim cannot be re-dated; only an OPEN or PENDING one can.`
        : e instanceof HttpErrorResponse && e.status === 403
        ? 'Only a steward can re-date a claim.'
        : e instanceof HttpErrorResponse && e.status === 422
        ? 'The new date is after the person\'s retirement date.'
        : 'Could not re-date that claim.';
      this.claimError.set({ id: c.id, message });
    }
  }

  /** A user whose role may reassign moves a DRAFT, OPEN or PENDING claim to another person; a SETTLED or VOID one cannot move. */
  protected async reassign(c: Claim, to: string): Promise<void> {
    this.claimError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    if (to === '') {
      this.claimError.set({ id: c.id, message: 'Choose another person to move the claim to.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/reassign`;
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>(url, { personId: Number(to) }, { headers: this.headers() }));
      if (this.session.sheetId() !== sheet) return;
      if (this.editingId() === c.id) this.reset();
      this.moved.emit(claim);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const message = e instanceof HttpErrorResponse && e.status === 423
        ? CLOSED
        : e instanceof HttpErrorResponse && e.status === 409
        ? `A ${c.status} claim cannot be moved; only a DRAFT, OPEN or PENDING one can.`
        : e instanceof HttpErrorResponse && e.status === 403
        ? 'Your role may not move a claim to another person on this sheet.'
        : e instanceof HttpErrorResponse && e.status === 422
        ? RETIRED
        : 'Could not move that claim.';
      this.claimError.set({ id: c.id, message });
    }
  }

  /**
   * Split an amount (more than 0.00 and less than its amount) off an OPEN claim into a new OPEN claim; a
   * PENDING, deferred or settled claim, one due inside a closed period, or a user whose role may not open a
   * claim is refused.
   */
  protected async split(c: Claim, amount: string): Promise<void> {
    this.claimError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const value = amount.trim();
    if (!/^\d{1,12}(\.\d{1,2})?$/.test(value) || cents(value) <= 0 || cents(value) >= cents(c.amount)) {
      this.claimError.set({ id: c.id, message: 'Enter an amount more than 0.00 and less than the claim\'s amount to split off.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/split`;
    try {
      const result = await firstValueFrom(
        this.http.post<{ original: Claim; split: Claim }>(url, { amount: value }, { headers: this.headers() }));
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(result.original);
      this.saved.emit(result.split);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const message = e instanceof HttpErrorResponse && e.status === 423
        ? CLOSED
        : e instanceof HttpErrorResponse && e.status === 409
        ? `A ${c.status} claim cannot be split; only an OPEN one can.`
        : e instanceof HttpErrorResponse && e.status === 410
        ? 'A SETTLED claim can be voided only within 7 days of its settlement.'
        : e instanceof HttpErrorResponse && e.status === 412
        ? `A deferred claim cannot be split until ${c.deferredUntil}.`
        : e instanceof HttpErrorResponse && e.status === 403
        ? 'Your role may not open a claim on this sheet, so it may not split one.'
        : e instanceof HttpErrorResponse && e.status === 422
        ? 'Enter an amount more than 0.00 and less than the claim\'s amount to split off.'
        : 'Could not split that claim.';
      this.claimError.set({ id: c.id, message });
    }
  }

  /**
   * Change a DRAFT claim's amount in its row, keeping its date, note and correction: the claim form's
   * rules apply, so a claim that cannot be edited, one another user's editor locks, one due inside a
   * closed period, a malformed amount, or a user whose role may change nothing is refused.
   */
  protected async saveAmount(c: Claim, entry: HTMLInputElement): Promise<void> {
    this.claimError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const amount = entry.value.trim();
    if (!/^-?\d{1,12}(\.\d{1,2})?$/.test(amount)) {
      this.claimError.set({ id: c.id, message: 'Enter an amount with at most two decimals, negative only for a correction.' });
      return;
    }
    const correction = amount.startsWith('-') && cents(amount) < 0;
    if (correction ? c.corrects === null : c.corrects !== null) {
      this.claimError.set({ id: c.id, message: correction
        ? CORRECTION
        : 'A correction keeps a negative amount; enter one below 0.00.' });
      return;
    }
    const body = { amount, date: c.date, note: c.note, corrects: c.corrects, ref: c.ref, tags: c.tags.join(',') };
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}`;
    try {
      const claim = await firstValueFrom(this.http.put<Claim>(url, body, { headers: this.headers() }));
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(claim);
      entry.value = '';
      // The save released this user's lock, so an editor left open on the claim closes.
      if (this.editingId() === c.id) this.reset();
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      const message = e instanceof HttpErrorResponse && e.status === 423
        ? CLOSED
        : e instanceof HttpErrorResponse && e.status === 409
        ? notEditable(c.status === 'DRAFT' ? undefined : c.status)
        : e instanceof HttpErrorResponse && e.status === 412
        ? 'Another user is editing this claim; it can be edited once they save or cancel.'
        : e instanceof HttpErrorResponse && e.status === 403
        ? 'Your role may not edit a claim on this sheet.'
        : e instanceof HttpErrorResponse && e.status === 422
        ? RETIRED
        : e instanceof HttpErrorResponse && e.status === 400 && correction
        ? CORRECTION
        : 'Could not save that amount.';
      this.claimError.set({ id: c.id, message });
    }
  }

  protected async save(): Promise<void> {
    if (this.saving) return;
    this.error.set('');
    if (this.editingId() !== null) {
      this.saving = true;
      const locked = await this.locking;
      this.saving = false;
      if (!locked) return;
    }
    this.claimError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const raw = this.form.getRawValue();
    const amount = raw.amount.trim();
    const date = raw.date.trim();
    const corrects = raw.corrects.trim();
    if (!/^-?\d{1,12}(\.\d{1,2})?$/.test(amount)) {
      this.error.set('Enter an amount with at most two decimals, negative only for a correction.');
      return;
    }
    const correction = amount.startsWith('-') && cents(amount) < 0;
    if (correction ? !/^\S{1,220}$/.test(corrects) : corrects !== '') {
      this.error.set(correction ? CORRECTION : 'Only a correction (a negative amount) names a claim it corrects.');
      return;
    }
    if (date && !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.error.set('Enter a date, or leave it blank for today.');
      return;
    }
    const body = { amount, date: date || null, note: raw.note.trim(), corrects: corrects || null,
      ref: raw.ref.trim() || null, tags: raw.tags };
    const id = this.editingId();
    const base = `/api/sheets/${encodeURIComponent(sheet)}`;
    this.saving = true;
    try {
      const claim = await firstValueFrom(
        id === null
          ? this.http.post<Claim>(`${base}/people/${this.personId()}/claims`, body, { headers: this.headers() })
          : this.http.put<Claim>(`${base}/claims/${id}`, body, { headers: this.headers() }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(claim);
      this.reset();
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      if (id !== null && e instanceof HttpErrorResponse && e.status === 412) {
        this.claimError.set({ id, message: 'Another user is editing this claim; it can be edited once they save or cancel.' });
        return;
      }
      if (isDuplicateRef(e)) {
        this.error.set(DUPLICATE_REF);
        return;
      }
      if (id !== null && e instanceof HttpErrorResponse && e.status === 409) {
        const status = this.claims().find((c) => c.id === id)?.status;
        this.claimError.set({ id, message: notEditable(status) });
        return;
      }
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 423
          ? CLOSED
          : e instanceof HttpErrorResponse && e.status === 422
          ? RETIRED
          : e instanceof HttpErrorResponse && e.status === 400 && correction
          ? CORRECTION
          : id === null ? 'Could not add that claim.' : 'Could not save that claim.',
      );
    } finally {
      this.saving = false;
    }
  }
}
