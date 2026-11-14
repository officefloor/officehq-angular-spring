import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { PeriodStore, periodOf } from '../periods/periods';
import { NO_RATE, PersonGrade, Rate, RateStore, dueCents, gradeOn, rateOn } from '../rates/rates';
import { VoidReasonStore } from '../void-reasons/void-reasons';
import { ClaimTemplateStore } from '../claim-templates/claim-templates';

/** Where a claim is in its life: DRAFT, then OPEN, then SETTLED; an OPEN claim a clerk settles above
 *  the clerk limit is PENDING until a steward approves it (SETTLED) or rejects it (OPEN again); a
 *  DRAFT, OPEN or PENDING claim can be VOID, and a steward may void a SETTLED one within 7 days of
 *  its settlement. A DRAFT can be DELETED, counting nowhere, and restored to a DRAFT on the same
 *  sheet-today. A claim whose due is above the review threshold goes to REVIEW when opened, counting
 *  nowhere until a steward reviews it (OPEN); a REVIEW claim can be VOID. */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'PENDING' | 'SETTLED' | 'VOID' | 'DELETED' | 'REVIEW';

/** A status change a claim's buttons ask for. */
type ClaimAction = 'open' | 'review' | 'settle' | 'approve' | 'reject' | 'void' | 'resettle' | 'delete' | 'restore';

export interface Claim {
  id: number;
  personId: number;
  /** Two decimals, e.g. "10.00". */
  amount: string;
  date: string;
  note: string;
  status: ClaimStatus;
  /** The claim's number, given when it is opened and kept when it is voided: sheet-wide (1, 2, ...)
   *  or, under period numbering, <period>-<n> or X-<n>; null for a claim never opened. */
  number: string | null;
  /** The due fixed when the claim was settled (two decimals); null until then, or when its grade
   *  had no rate to work from. */
  settledDue: string | null;
  /** The user who opened the claim; null for a claim never opened. */
  openedBy: string | null;
  /** Whether the person's last statement bound the claim to the figure it had then. */
  stated: boolean;
  /** The figure the statement bound the claim to (two decimals); null when unbound or it had none. */
  statedDue: string | null;
  /** The number of the claim a correction (a negative amount) corrects; null when it is not one. */
  corrects: string | null;
  /** The date the claim's live figures round by (yyyy-mm-dd): its own, or for a correction the date
   *  of the claim it corrects. */
  roundsBy: string;
  /** The date the claim is due by (yyyy-mm-dd), fixed when it was added: its date plus the terms of
   *  its person's grade in force on that date. */
  dueBy: string;
  /** The claim's status log, oldest first: one line per status it has had, as
   *  <STATUS> by <user> on <date> due=<due>. */
  log: string[];
  /** The date the claim was deferred until (yyyy-mm-dd); null when it never was. */
  deferredUntil: string | null;
  /** The last day a reminder was written for the claim (yyyy-mm-dd); null when none was. */
  remindedOn: string | null;
  /** How many proofs were added to the claim. */
  proofs: number;
  /** The number of the claim this claim was split off; null when it was not. */
  splitFrom: string | null;
  /** The user whose open editor locks the claim for everyone else; null when no one's does. */
  lockedBy: string | null;
  /** Whether the claim was added while the period its date falls in was soft-closed. */
  lateEntry: boolean;
  /** The claim's external reference, unique on the sheet without regard to case; null when it has none. */
  ref: string | null;
  /** The claim's tags, lower case and sorted; empty when it has none. */
  tags: string[];
  /** The reason on the sheet's list the claim was voided with; null when it was given none. */
  voidReason: string | null;
  /** The claim's comments, oldest first, each as <user> on <date>: <text>. */
  comments: string[];
  /** The number of the settle batch that settled the claim; null when a bulk settle did not. */
  batch: number | null;
  /** The claim's lines, oldest first, each as <label> <qty> x <unit>; empty when it has none. A claim
   *  with lines has them added up as its amount. */
  lines: string[];
}

/** What a split did: the claim that kept the rest of its amount, and the new claim split off it. */
interface Split {
  kept: Claim;
  added: Claim;
}

/** Whether a claim is deferred on the sheet's today: it is OPEN and today is before the date it was
 *  deferred until. From that date on it is an OPEN claim like any other. */
export function deferred(claim: Claim, today: string | null): boolean {
  return claim.status === 'OPEN' && claim.deferredUntil !== null && today !== null && today < claim.deferredUntil;
}

/** The status a claim had at the end of a day, as its status log records it: the last status it
 *  moved to on or before that day; null when it was added after it. */
export function statusAsOf(claim: Claim, day: string): ClaimStatus | null {
  let status: ClaimStatus | null = null;
  for (const line of claim.log) {
    const match = /^(\S+) by .* on (\d{4}-\d{2}-\d{2}) due=\S+$/.exec(line);
    if (match && match[2] <= day) status = match[1] as ClaimStatus;
  }
  return status;
}

/** Whether a claim is a correction: its amount is negative. */
export function isCorrection(claim: Claim): boolean {
  return cents(claim.amount) < 0;
}

/** Whether a claim counts toward what is claimed and due: only OPEN, PENDING and SETTLED ones do. */
export function counts(claim: Claim): boolean {
  return isOpen(claim) || claim.status === 'SETTLED';
}

/** Whether a claim is open: OPEN, or PENDING a steward's approval. */
export function isOpen(claim: Claim): boolean {
  return claim.status === 'OPEN' || claim.status === 'PENDING';
}

/** An amount in cents, from the two-decimal string the server sends. */
export function cents(amount: string): number {
  return Math.round(Number(amount) * 100);
}

/** What a late charge works from: the sheet's late percent (two decimals) in force on a date, its today and
 *  a claim's grace days (see graceOn in the settings); and what a claim's figures round by: the sheet's
 *  rounding mode in force on a date (half-up or half-even; null for none), else its policy date (blank for
 *  its today). */
export interface Late {
  pct: (date: string) => string;
  today: string | null;
  policyDate: string;
  rounding: (date: string) => string | null;
  grace: (claim: Claim) => number;
}

/** Whether a claim's live figures round half up: by the rounding mode in force on the date it rounds by
 *  when one is; else it rounds by a date before the sheet's policy date (its today when the policy date
 *  is blank); otherwise they round half to even. */
function roundsHalfUp(claim: Claim, late: Late): boolean {
  const mode = late.rounding(claim.roundsBy);
  if (mode !== null) return mode === 'half-up';
  const policy = late.policyDate !== '' ? late.policyDate : late.today;
  return policy !== null && claim.roundsBy < policy;
}

/** A claim's figure in cents: the figure a statement bound it to, unless it is PENDING (no statement
 *  binds it then); otherwise its amount times the rate in force on its date among its grade's rates;
 *  null when it has no figure. The floor is not applied here (see claimDueCents). */
function figureCents(claim: Claim, rates: Rate[], halfUp: boolean): number | null {
  if (claim.stated && claim.status !== 'PENDING') return claim.statedDue === null ? null : cents(claim.statedDue);
  const rate = rateOn(rates, claim.date);
  if (rate === null) return null;
  return dueCents(cents(claim.amount), rate, halfUp);
}

/** A claim's due in cents: the due fixed when it was settled; otherwise the greater of the sheet's floor
 *  in force on its date (none for a correction) and its figure plus its late charge: for every full 30
 *  days an OPEN or PENDING claim is overdue, the late percent in force on that boundary (its due-by plus
 *  30, 60, 90 ... days) of the figure plus the charges of the boundaries before it (compounding), rounded
 *  like a due, the charges added up (a statement never binds
 *  the charge; settling freezes the due; a correction has none); null when it has no figure. */
export function claimDueCents(claim: Claim, rates: Rate[], floor: (date: string) => number, late: Late): number | null {
  if (claim.settledDue !== null) return cents(claim.settledDue);
  const halfUp = roundsHalfUp(claim, late);
  const figure = figureCents(claim, rates, halfUp);
  if (figure === null) return null;
  if (isCorrection(claim)) return figure;
  const grace = late.grace(claim);
  const periods = Math.floor(daysOverdue(claim, late.today, grace) / 30);
  let charge = 0;
  for (let boundary = 1; boundary <= periods; boundary++) {
    const pct = late.pct(plusDays(claim.dueBy, grace + 30 * boundary));
    if (cents(pct) !== 0) charge += dueCents(figure + charge, pct, halfUp);
  }
  return Math.max(figure + charge, floor(claim.date));
}

/** A date (yyyy-mm-dd) some days on. */
export function plusDays(date: string, days: number): string {
  return new Date(Date.parse(date) + days * 86_400_000).toISOString().slice(0, 10);
}

/** How many days an OPEN or PENDING claim is past its due-by plus its grace days on the sheet's today; 0
 *  when it is not overdue. */
function daysOverdue(claim: Claim, today: string | null, grace: number): number {
  if (!overdue(claim, today, grace) || today === null) return 0;
  return Math.round((Date.parse(today) - Date.parse(plusDays(claim.dueBy, grace))) / 86_400_000);
}

/** Whether a claim is overdue: it is OPEN or PENDING, not deferred, and its due-by plus its grace days
 *  (those of its person's grade in force on its date; none by default) is before the sheet's today. */
export function overdue(claim: Claim, today: string | null, grace = 0): boolean {
  return isOpen(claim) && today !== null && plusDays(claim.dueBy, grace) < today && !deferred(claim, today);
}

/** Cents shown with two decimals. */
export function money(total: number): string {
  return (total / 100).toFixed(2);
}

const PAST: Record<ClaimAction, string> = {
  open: 'opened',
  review: 'reviewed',
  settle: 'settled',
  approve: 'approved',
  reject: 'rejected',
  void: 'voided',
  resettle: 're-settled',
  delete: 'deleted',
  restore: 'restored',
};

/** An amount of 0.00 or more with up to two decimals. */
export const AMOUNT = /^\d+(\.\d{1,2})?$/;

/** A claim's amount: any amount with up to two decimals; a negative one is a correction. */
const CLAIM_AMOUNT = /^-?\d+(\.\d{1,2})?$/;

/** A line's quantity: a whole number. */
const QTY = /^\d+$/;

/** A claim number a correction names: sheet-wide (1, 2, ...) or within a period (P1-1, X-1). */
const NUMBER = /^\S+$/;

// A person's claims, newest first, and the one form that adds a claim or, once a claim's editor is
// opened, saves its changes.
@Component({
  selector: 'app-person-claims',
  template: `
    <form (submit)="submit($event)">
      <label [for]="'claim-amount-' + personId()">Amount</label>
      <input
        [id]="'claim-amount-' + personId()"
        data-testid="claim-form-amount"
        inputmode="decimal"
        [value]="amount()"
        (input)="amount.set(value($event)); error.set(null)"
      />
      <label [for]="'claim-date-' + personId()">Date</label>
      <input
        [id]="'claim-date-' + personId()"
        type="date"
        data-testid="claim-form-date"
        [value]="date()"
        (input)="date.set(value($event)); error.set(null)"
      />
      <label [for]="'claim-note-' + personId()">Note</label>
      <input
        [id]="'claim-note-' + personId()"
        data-testid="claim-form-note"
        [value]="note()"
        (input)="note.set(value($event)); error.set(null)"
      />
      <label [for]="'claim-corrects-' + personId()">Corrects claim no.</label>
      <input
        [id]="'claim-corrects-' + personId()"
        data-testid="claim-form-corrects"
        [value]="corrects()"
        (input)="corrects.set(value($event)); error.set(null)"
      />
      <label [for]="'claim-ref-' + personId()">Reference</label>
      <input
        [id]="'claim-ref-' + personId()"
        data-testid="claim-form-ref"
        [value]="ref()"
        (input)="ref.set(value($event)); error.set(null)"
      />
      <label [for]="'claim-tags-' + personId()">Tags (comma separated)</label>
      <input
        [id]="'claim-tags-' + personId()"
        data-testid="claim-form-tags"
        [value]="tags()"
        (input)="tags.set(value($event)); error.set(null)"
      />
      <button type="submit" data-testid="claim-form-submit">
        {{ editingId() === null ? 'Add claim' : 'Save claim' }}
      </button>
    </form>
    <label [for]="'claim-template-' + personId()">Template</label>
    <select
      [id]="'claim-template-' + personId()"
      data-testid="claim-template-select"
      (change)="template.set(value($event)); error.set(null)"
    >
      <option value="" [selected]="template() === ''">Choose a template</option>
      @for (t of templates.templates(); track t.id) {
        <option [value]="t.name" [selected]="template() === t.name">{{ t.name }}</option>
      }
    </select>
    <button type="button" data-testid="claim-template-add" (click)="addFromTemplate()">Add from template</button>
    @if (error(); as message) {
      <p role="alert" data-testid="claim-form-error">{{ message }}</p>
    }
    <ul>
      @for (c of claims(); track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <input
            type="checkbox"
            data-testid="claim-select"
            [attr.aria-label]="'Select claim ' + c.id"
            [checked]="selectedIds().has(c.id)"
            (change)="selectedChange.emit({ id: c.id, selected: checked($event) })"
          />
          <span data-testid="claim-amount">{{ c.amount }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span data-testid="claim-ref">{{ c.ref ?? '' }}</span>
          <span data-testid="claim-tags">{{ c.tags.join(', ') }}</span>
          <span data-testid="claim-batch">{{ c.batch ?? '' }}</span>
          <span data-testid="claim-due">{{ dueOf(c) }}</span>
          <span data-testid="claim-due-by">{{ dueBy(c) }}</span>
          <span data-testid="claim-overdue">{{ isOverdue(c) ? 'yes' : 'no' }}</span>
          <span data-testid="claim-status">{{ c.status }}</span>
          <span data-testid="claim-number">{{ c.number ?? '' }}</span>
          <span data-testid="claim-corrects">{{ c.corrects ?? '' }}</span>
          <span data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</span>
          <span data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</span>
          <span data-testid="claim-period">{{ periodNameOf(c) }}</span>
          <span data-testid="claim-deferred">{{ isDeferred(c) ? 'yes' : 'no' }}</span>
          <span data-testid="claim-late-entry">{{ c.lateEntry ? 'yes' : 'no' }}</span>
          <span data-testid="claim-deferred-until">{{ c.deferredUntil ?? '' }}</span>
          <span data-testid="claim-reminded">{{ c.remindedOn ?? '' }}</span>
          <span data-testid="claim-proofs">{{ c.proofs }}</span>
          <span data-testid="claim-split-from">{{ c.splitFrom ?? '' }}</span>
          <ol data-testid="claim-log" [attr.aria-label]="'Status log of claim ' + c.id">
            @for (line of c.log; track $index) {
              <li data-testid="claim-log-line">{{ line }}</li>
            }
          </ol>
          <button type="button" data-testid="claim-open" [attr.aria-label]="'Open claim ' + c.id" (click)="act(c, 'open')">
            Open
          </button>
          <button type="button" data-testid="claim-review" [attr.aria-label]="'Review claim ' + c.id" (click)="act(c, 'review')">
            Review
          </button>
          <button type="button" data-testid="claim-settle" [attr.aria-label]="'Settle claim ' + c.id" (click)="act(c, 'settle')">
            Settle
          </button>
          <button type="button" data-testid="claim-approve" [attr.aria-label]="'Approve claim ' + c.id" (click)="act(c, 'approve')">
            Approve
          </button>
          <label [for]="'claim-reject-reason-' + c.id">Reject reason</label>
          <input
            [id]="'claim-reject-reason-' + c.id"
            data-testid="claim-reject-reason"
            [value]="rejectReasons()[c.id] ?? ''"
            (input)="setRejectReason(c.id, value($event))"
          />
          <button type="button" data-testid="claim-reject" [attr.aria-label]="'Reject claim ' + c.id" (click)="act(c, 'reject')">
            Reject
          </button>
          <button type="button" data-testid="claim-remind" [attr.aria-label]="'Remind claim ' + c.id" (click)="remind(c)">
            Remind
          </button>
          <label [for]="'claim-defer-date-' + c.id">Defer until</label>
          <input
            [id]="'claim-defer-date-' + c.id"
            type="date"
            data-testid="claim-defer-date"
            [value]="deferDates()[c.id] ?? ''"
            (input)="setDeferDate(c.id, value($event))"
          />
          <button type="button" data-testid="claim-defer" [attr.aria-label]="'Defer claim ' + c.id" (click)="defer(c)">
            Defer
          </button>
          <label [for]="'claim-date-new-' + c.id">Re-date to</label>
          <input
            [id]="'claim-date-new-' + c.id"
            type="date"
            data-testid="claim-date-new"
            [value]="newDates()[c.id] ?? ''"
            (input)="setNewDate(c.id, value($event))"
          />
          <button type="button" data-testid="claim-redate" [attr.aria-label]="'Re-date claim ' + c.id" (click)="redate(c)">
            Re-date
          </button>
          <label [for]="'claim-split-amount-' + c.id">Split off</label>
          <input
            [id]="'claim-split-amount-' + c.id"
            data-testid="claim-split-amount"
            inputmode="decimal"
            [value]="splitAmounts()[c.id] ?? ''"
            (input)="setSplitAmount(c.id, value($event))"
          />
          <button type="button" data-testid="claim-split" [attr.aria-label]="'Split claim ' + c.id" (click)="split(c)">
            Split
          </button>
          <ol data-testid="claim-lines" [attr.aria-label]="'Lines of claim ' + c.id">
            @for (line of c.lines; track $index) {
              <li data-testid="claim-lines-line">{{ line }}</li>
            }
          </ol>
          <label [for]="'claim-line-label-' + c.id">Line label</label>
          <input
            [id]="'claim-line-label-' + c.id"
            data-testid="claim-line-label"
            [value]="lineFields()[c.id]?.label ?? ''"
            (input)="setLineField(c.id, 'label', value($event))"
          />
          <label [for]="'claim-line-qty-' + c.id">Quantity</label>
          <input
            [id]="'claim-line-qty-' + c.id"
            data-testid="claim-line-qty"
            inputmode="numeric"
            [value]="lineFields()[c.id]?.qty ?? ''"
            (input)="setLineField(c.id, 'qty', value($event))"
          />
          <label [for]="'claim-line-unit-' + c.id">Unit amount</label>
          <input
            [id]="'claim-line-unit-' + c.id"
            data-testid="claim-line-unit"
            inputmode="decimal"
            [value]="lineFields()[c.id]?.unit ?? ''"
            (input)="setLineField(c.id, 'unit', value($event))"
          />
          <button type="button" data-testid="claim-line-add" [attr.aria-label]="'Add a line to claim ' + c.id" (click)="addLine(c)">
            Add line
          </button>
          <ol data-testid="claim-comments" [attr.aria-label]="'Comments on claim ' + c.id">
            @for (line of c.comments; track $index) {
              <li data-testid="claim-comments-line">{{ line }}</li>
            }
          </ol>
          <label [for]="'claim-comment-text-' + c.id">Comment</label>
          <input
            [id]="'claim-comment-text-' + c.id"
            data-testid="claim-comment-text"
            [value]="commentTexts()[c.id] ?? ''"
            (input)="setCommentText(c.id, value($event))"
          />
          <button
            type="button"
            data-testid="claim-comment-add"
            [attr.aria-label]="'Comment on claim ' + c.id"
            (click)="addComment(c)"
          >
            Comment
          </button>
          <button type="button" data-testid="claim-proof" [attr.aria-label]="'Add a proof to claim ' + c.id" (click)="addProof(c)">
            Add proof
          </button>
          <label [for]="'claim-void-reason-' + c.id">Void reason</label>
          <select
            [id]="'claim-void-reason-' + c.id"
            data-testid="claim-void-reason"
            (change)="setVoidReason(c.id, value($event))"
          >
            <option value="" [selected]="!voidReasonChoice()[c.id]">Choose a reason</option>
            @for (r of voidReasons.reasons(); track r.id) {
              <option [value]="r.reason" [selected]="voidReasonChoice()[c.id] === r.reason">{{ r.reason }}</option>
            }
          </select>
          <button type="button" data-testid="claim-void" [attr.aria-label]="'Void claim ' + c.id" (click)="act(c, 'void')">
            Void
          </button>
          <button type="button" data-testid="claim-delete" [attr.aria-label]="'Delete claim ' + c.id" (click)="act(c, 'delete')">
            Delete
          </button>
          <button type="button" data-testid="claim-restore" [attr.aria-label]="'Restore claim ' + c.id" (click)="act(c, 'restore')">
            Restore
          </button>
          <button
            type="button"
            data-testid="claim-resettle"
            [attr.aria-label]="'Re-settle claim ' + c.id"
            (click)="act(c, 'resettle')"
          >
            Re-settle
          </button>
          <label [for]="'claim-reassign-to-' + c.id">Move to</label>
          <select
            [id]="'claim-reassign-to-' + c.id"
            data-testid="claim-reassign-to"
            (change)="setReassignTo(c.id, value($event))"
          >
            <option value="" [selected]="!reassignTo()[c.id]">Choose a person</option>
            @for (o of others(); track o.id) {
              <option [value]="o.id" [selected]="reassignTo()[c.id] === '' + o.id">{{ o.name }}</option>
            }
          </select>
          <button type="button" data-testid="claim-reassign" [attr.aria-label]="'Move claim ' + c.id" (click)="reassign(c)">
            Move
          </button>
          <button type="button" data-testid="claim-edit" [attr.aria-label]="'Edit claim ' + c.id" (click)="edit(c)">
            Edit
          </button>
          <label [for]="'claim-inline-amount-' + c.id">New amount</label>
          <input
            [id]="'claim-inline-amount-' + c.id"
            data-testid="claim-inline-amount"
            inputmode="decimal"
            [value]="inlineAmounts()[c.id] ?? ''"
            (input)="setInlineAmount(c.id, value($event))"
          />
          <button
            type="button"
            data-testid="claim-inline-save"
            [attr.aria-label]="'Save the amount of claim ' + c.id"
            (click)="saveInline(c)"
          >
            Save amount
          </button>
          @if (editingId() === c.id) {
            <button type="button" data-testid="claim-cancel" (click)="cancel()">Cancel</button>
          }
          @if (rowError(); as e) {
            @if (e.id === c.id) {
              <p role="alert" data-testid="claim-error">{{ e.message }}</p>
            }
          }
        </li>
      }
    </ul>
  `,
})
export class PersonClaims {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);
  private readonly periods = inject(PeriodStore);
  protected readonly voidReasons = inject(VoidReasonStore);
  protected readonly templates = inject(ClaimTemplateStore);
  private readonly rates = inject(RateStore);

  readonly personId = input.required<number>();
  readonly claims = input.required<Claim[]>();
  /** The person's grades, each in force from its from-date. */
  readonly grades = input.required<PersonGrade[]>();
  /** The sheet's floor in cents in force on a date: the least the live due of a claim of that date can be. */
  readonly floor = input.required<(date: string) => number>();
  /** The sheet's late percent (two decimals) in force on a date. */
  readonly latePct = input.required<(date: string) => string>();
  /** The sheet's policy date (yyyy-mm-dd); blank for its today. */
  readonly policyDate = input.required<string>();
  /** The sheet's rounding mode in force on a date (half-up or half-even); null for none. */
  readonly roundingOn = input.required<(date: string) => string | null>();
  /** The grace days in force on a date for a grade (0 for none). */
  readonly graceOn = input.required<(grade: string | null, date: string) => number>();
  /** The ids of the claims selected to act on together. */
  readonly selectedIds = input.required<ReadonlySet<number>>();
  /** The people on the sheet, in the order they were added: a claim can move to any other. */
  readonly people = input.required<readonly { id: number; name: string }[]>();
  /** Whether the signed-in user is a viewer on the sheet, who can change nothing. */
  readonly viewer = input(false);
  readonly saved = output<Claim>();
  /** A claim moved to another person: listed as their newest. */
  readonly moved = output<Claim>();
  /** A claim's checkbox ticked or cleared. */
  readonly selectedChange = output<{ id: number; selected: boolean }>();

  protected readonly amount = signal('');
  protected readonly date = signal('');
  protected readonly note = signal('');
  protected readonly corrects = signal('');
  protected readonly ref = signal('');
  protected readonly tags = signal('');
  protected readonly editingId = signal<number | null>(null);
  /** The name of the claim template chosen to add a claim from; blank for none. */
  protected readonly template = signal('');
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer; a second submit meanwhile is dropped. */
  private readonly busy = signal(false);
  /** The error from the last status change refused, and the claim it belongs to. */
  protected readonly rowError = signal<{ id: number; message: string } | null>(null);
  /** The reject reason typed on each claim's row, by claim id. */
  protected readonly rejectReasons = signal<Record<number, string>>({});

  /** The void reason chosen on each claim's row, by claim id (a reason on the sheet's list, or blank). */
  protected readonly voidReasonChoice = signal<Record<number, string>>({});

  /** The defer-until date typed on each claim's row, by claim id. */
  protected readonly deferDates = signal<Record<number, string>>({});

  /** The new date typed on each claim's row to re-date it to, by claim id. */
  protected readonly newDates = signal<Record<number, string>>({});

  /** The amount typed on each claim's row to split off it, by claim id. */
  protected readonly splitAmounts = signal<Record<number, string>>({});

  /** The comment typed on each claim's row, by claim id. */
  protected readonly commentTexts = signal<Record<number, string>>({});

  /** The line typed on each claim's row to add to it, by claim id. */
  protected readonly lineFields = signal<Record<number, { label: string; qty: string; unit: string }>>({});

  /** The new amount typed on each claim's row to save in place, by claim id. */
  protected readonly inlineAmounts = signal<Record<number, string>>({});

  /** The person chosen on each claim's row to move it to, by claim id (a person id, or blank). */
  protected readonly reassignTo = signal<Record<number, string>>({});
  /** The people a claim of this person can move to: everyone else on the sheet. */
  protected readonly others = computed(() => this.people().filter((p) => p.id !== this.personId()));
  /** Whether the editor left open on a claim the user still locks has been looked for since the sheet loaded. */
  private restored = false;

  constructor() {
    // A claim the user still locks (an editor left open before the sheet was re-loaded) opens in
    // the editor again, once the person's claims first show.
    effect(() => {
      const claims = this.claims();
      if (this.restored || claims.length === 0) return;
      this.restored = true;
      const user = untracked(this.session.user);
      const mine = claims.find((c) => c.status === 'DRAFT' && user !== null && c.lockedBy === user);
      if (mine) untracked(() => this.fill(mine));
    });
    // A change of the sheet's today releases every lock, so it closes the editor too.
    let today: string | null | undefined;
    effect(() => {
      const now = this.session.today();
      if (today !== undefined && now !== today) untracked(() => this.reset());
      today = now;
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected checked(event: Event): boolean {
    return (event.target as HTMLInputElement).checked;
  }

  /** The claim's due (see claimDueCents), or n/a when it has no figure. */
  protected dueOf(claim: Claim): string {
    const late = {
      pct: this.latePct(),
      today: this.session.today(),
      policyDate: this.policyDate(),
      rounding: this.roundingOn(),
      grace: (c: Claim) => this.graceOf(c),
    };
    const due = claimDueCents(claim, this.rates.ofGradeOn(this.grades(), claim.date), this.floor(), late);
    return due === null ? NO_RATE : money(due);
  }

  protected dueBy(claim: Claim): string {
    return claim.dueBy;
  }

  protected isOverdue(claim: Claim): boolean {
    return overdue(claim, this.session.today(), this.graceOf(claim));
  }

  /** The claim's grace days: those in force on its date for the person's grade in force then. */
  private graceOf(claim: Claim): number {
    return this.graceOn()(gradeOn(this.grades(), claim.date), claim.date);
  }

  /** Whether the row shows the claim deferred: the date it was deferred until is still after the
   *  sheet's today, whatever its status now. */
  protected isDeferred(claim: Claim): boolean {
    const today = this.session.today();
    return claim.deferredUntil !== null && today !== null && today < claim.deferredUntil;
  }

  /** The name of the period the claim's due-by falls in; empty when it falls in none. */
  protected periodNameOf(claim: Claim): string {
    return periodOf(this.periods.periods(), claim.dueBy)?.name ?? '';
  }

  /** Open the editor on a DRAFT claim, locking it for other users until it is saved or cancelled.
   *  Any other claim cannot be edited (an OPEN one is voided and added again), nor can one in a
   *  closed period, one another user's editor locks, or any claim by a viewer: each is refused on
   *  the row and takes no lock. */
  protected async edit(claim: Claim): Promise<void> {
    this.rowError.set(null);
    const refused = this.editRefusal(claim);
    if (refused !== null) {
      this.rowError.set({ id: claim.id, message: refused });
      return;
    }
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const previous = this.editingId();
    this.fill(claim);
    const base = `/api/sheets/${encodeURIComponent(sheet)}/people/${this.personId()}/claims`;
    try {
      const locked = await firstValueFrom(this.http.post<Claim>(`${base}/${claim.id}/lock`, null));
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(locked);
      if (previous !== null && previous !== claim.id) void this.unlock(sheet, previous);
    } catch (e) {
      if (this.session.sheetId() !== sheet) return;
      if (this.editingId() === claim.id) {
        // The editor goes back to the claim it had open before, whose lock the user still holds.
        const back = previous !== claim.id ? this.claims().find((c) => c.id === previous) : undefined;
        if (back) this.fill(back);
        else this.reset();
      }
      const forbidden = e instanceof HttpErrorResponse && e.status === 403;
      this.rowError.set({
        id: claim.id,
        message: forbidden
          ? 'The claim cannot be edited: your role may not change this sheet.'
          : 'The claim cannot be edited: only a draft outside a closed period can, and not while another user is editing it.',
      });
    }
  }

  /** Why the edit button is refused on a claim, as far as the screen can tell; null when it is not. */
  private editRefusal(claim: Claim): string | null {
    const user = this.session.user();
    if (claim.status !== 'DRAFT') return `A ${claim.status} claim cannot be edited; only a draft can.`;
    if (this.viewer()) {
      return 'The claim cannot be edited: a viewer can change nothing on this sheet.';
    }
    if (periodOf(this.periods.periods(), claim.dueBy)?.closed) {
      return `The claim cannot be edited: it is due by a day in the closed period ${periodOf(this.periods.periods(), claim.dueBy)?.name}.`;
    }
    if (claim.lockedBy !== null && claim.lockedBy !== user) {
      return `The claim cannot be edited: ${claim.lockedBy} is editing it.`;
    }
    return null;
  }

  /** Cancel the editor: close it and release the claim's lock. */
  protected cancel(): void {
    const id = this.editingId();
    const sheet = this.session.sheetId();
    this.reset();
    if (id !== null && sheet) void this.unlock(sheet, id);
  }

  /** Release the user's lock on a claim; the row shows it unlocked once the answer comes. */
  private async unlock(sheet: string, id: number): Promise<void> {
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${this.personId()}/claims/${id}/lock`;
    try {
      const released = await firstValueFrom(this.http.delete<Claim>(url));
      if (this.session.sheetId() === sheet) this.saved.emit(released);
    } catch {
      // A lock already released (the sheet's today changed) or the claim gone: nothing to show.
    }
  }

  /** Show a claim's values in the editor. */
  private fill(claim: Claim): void {
    this.rowError.set(null);
    this.editingId.set(claim.id);
    this.amount.set(claim.amount);
    this.date.set(claim.date);
    this.note.set(claim.note);
    this.corrects.set(claim.corrects ?? '');
    this.ref.set(claim.ref ?? '');
    this.tags.set(claim.tags.join(', '));
    this.error.set(null);
  }

  protected setRejectReason(id: number, reason: string): void {
    this.rejectReasons.update((reasons) => ({ ...reasons, [id]: reason }));
    if (this.rowError()?.id === id) this.rowError.set(null);
  }

  protected setVoidReason(id: number, reason: string): void {
    this.voidReasonChoice.update((chosen) => ({ ...chosen, [id]: reason }));
    if (this.rowError()?.id === id) this.rowError.set(null);
  }

  protected setReassignTo(id: number, personId: string): void {
    this.reassignTo.update((chosen) => ({ ...chosen, [id]: personId }));
    if (this.rowError()?.id === id) this.rowError.set(null);
  }

  protected setDeferDate(id: number, date: string): void {
    this.deferDates.update((dates) => ({ ...dates, [id]: date }));
    if (this.rowError()?.id === id) this.rowError.set(null);
  }

  /** Defer an OPEN claim until the date typed on its row; a claim that is not OPEN, a date not after
   *  the sheet's today, or a claim dated in a closed period, is refused on the row. */
  protected async defer(claim: Claim): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const until = (this.deferDates()[claim.id] ?? '').trim();
    if (until === '') {
      this.rowError.set({ id: claim.id, message: 'Type the date the claim is deferred until.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${this.personId()}/claims/${claim.id}/defer`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, { until }));
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(saved);
    } catch {
      this.rowError.set({
        id: claim.id,
        message: 'The claim could not be deferred: only an open claim outside a closed period can be, to a date after today.',
      });
    }
  }

  /** Remind an OPEN or PENDING claim; a claim in any other status, or one already reminded on the
   *  sheet's today, is refused on the row. */
  protected async remind(claim: Claim): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${this.personId()}/claims/${claim.id}/remind`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, {}));
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(saved);
    } catch {
      this.rowError.set({
        id: claim.id,
        message: 'The claim could not be reminded: only an open or pending claim can be, once a day.',
      });
    }
  }

  protected setNewDate(id: number, date: string): void {
    this.newDates.update((dates) => ({ ...dates, [id]: date }));
    if (this.rowError()?.id === id) this.rowError.set(null);
  }

  /** Re-date an OPEN or PENDING claim to the date typed on its row; anyone but a steward, a claim
   *  that is not OPEN or PENDING, a claim due by a day in a closed period, or a new date
   *  after its person's retirement, is refused on the row. */
  protected async redate(claim: Claim): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const date = (this.newDates()[claim.id] ?? '').trim();
    if (date === '') {
      this.rowError.set({ id: claim.id, message: 'Type the date to re-date the claim to.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${this.personId()}/claims/${claim.id}/redate`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, { date }));
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(saved);
    } catch (e) {
      const forbidden = e instanceof HttpErrorResponse && e.status === 403;
      this.rowError.set({
        id: claim.id,
        message: forbidden
          ? 'The claim could not be re-dated: only a steward can re-date a claim.'
          : 'The claim could not be re-dated: only an open or pending claim outside a closed period can be, to a date not after its person retired.',
      });
    }
  }

  protected setInlineAmount(id: number, amount: string): void {
    this.inlineAmounts.update((amounts) => ({ ...amounts, [id]: amount }));
    if (this.rowError()?.id === id) this.rowError.set(null);
  }

  /** Save the amount typed on a DRAFT claim's row, keeping its date, note and correction, by the
   *  claim form's rules: an amount not in its form, a claim that cannot be edited, one another
   *  user's editor locks, one due by a day in a closed period, or any claim by a viewer, is refused
   *  on the row. */
  protected async saveInline(claim: Claim): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const amount = (this.inlineAmounts()[claim.id] ?? '').trim();
    if (!CLAIM_AMOUNT.test(amount)) {
      this.rowError.set({ id: claim.id, message: 'Type an amount with up to two decimals.' });
      return;
    }
    const negative = amount.startsWith('-') && Number(amount) !== 0;
    if (negative !== (claim.corrects !== null)) {
      this.rowError.set({
        id: claim.id,
        message: negative
          ? 'A negative amount is a correction: only a correction takes one.'
          : 'A correction keeps a negative amount.',
      });
      return;
    }
    const refused = this.editRefusal(claim);
    if (refused !== null) {
      this.rowError.set({ id: claim.id, message: refused });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${this.personId()}/claims/${claim.id}`;
    try {
      const saved = await firstValueFrom(
        this.http.put<Claim>(url, { amount, date: claim.date, note: claim.note, corrects: claim.corrects, ref: claim.ref, tags: claim.tags.join(',') }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.setInlineAmount(claim.id, '');
      // The save released the user's own lock, so an editor left open on the claim closes.
      if (this.editingId() === claim.id) this.reset();
      this.saved.emit(saved);
    } catch (e) {
      const forbidden = e instanceof HttpErrorResponse && e.status === 403;
      this.rowError.set({
        id: claim.id,
        message: forbidden
          ? 'The amount could not be saved: your role may not change this sheet.'
          : 'The amount could not be saved: only a draft outside a closed period can be edited, and not while another user is editing it.',
      });
    }
  }

  protected setSplitAmount(id: number, amount: string): void {
    this.splitAmounts.update((amounts) => ({ ...amounts, [id]: amount }));
    if (this.rowError()?.id === id) this.rowError.set(null);
  }

  /** Split the amount typed on its row off an OPEN claim: the claim keeps the rest, and the new OPEN
   *  claim is listed first. A claim that is not OPEN or is deferred, one in a closed period, an
   *  amount not above 0.00 and below the claim's, or a role that may not open a claim, is refused on
   *  the row. */
  protected async split(claim: Claim): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const amount = (this.splitAmounts()[claim.id] ?? '').trim();
    if (!AMOUNT.test(amount) || cents(amount) <= 0 || cents(amount) >= cents(claim.amount)) {
      this.rowError.set({ id: claim.id, message: "Type an amount above 0.00 and below the claim's to split off." });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${this.personId()}/claims/${claim.id}/split`;
    try {
      const result = await firstValueFrom(this.http.post<Split>(url, { amount }));
      if (this.session.sheetId() !== sheet) return;
      this.setSplitAmount(claim.id, '');
      this.saved.emit(result.kept);
      this.saved.emit(result.added);
    } catch (e) {
      const forbidden = e instanceof HttpErrorResponse && e.status === 403;
      this.rowError.set({
        id: claim.id,
        message: forbidden
          ? 'The claim could not be split: your role may not open a claim on this sheet.'
          : 'The claim could not be split: only an open claim outside a closed period, not deferred, can be.',
      });
    }
  }

  /** Add a proof to a claim, raising its proofs count by one; a SETTLED or VOID claim, or one dated
   *  in a closed period, is refused on the row. */
  protected async addProof(claim: Claim): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${this.personId()}/claims/${claim.id}/proof`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, null));
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(saved);
    } catch {
      this.rowError.set({
        id: claim.id,
        message: 'A proof could not be added: a settled or voided claim, or one in a closed period, takes no more.',
      });
    }
  }

  protected setLineField(id: number, field: 'label' | 'qty' | 'unit', text: string): void {
    this.lineFields.update((all) => ({ ...all, [id]: { ...(all[id] ?? { label: '', qty: '', unit: '' }), [field]: text } }));
    if (this.rowError()?.id === id) this.rowError.set(null);
  }

  /** Add the line typed on its row to a DRAFT claim, which then takes its lines added up as its
   *  amount; a blank label, a quantity that is not a whole number of at least 1, a unit amount not in
   *  its form, a claim that is not a draft, one due by a day in a closed period or one another user's
   *  editor locks, is refused on the row. */
  protected async addLine(claim: Claim): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const typed = this.lineFields()[claim.id] ?? { label: '', qty: '', unit: '' };
    const label = typed.label.trim();
    const qty = typed.qty.trim();
    const unit = typed.unit.trim();
    if (label === '' || !QTY.test(qty) || Number(qty) < 1 || !AMOUNT.test(unit)) {
      this.rowError.set({
        id: claim.id,
        message: 'Type a label, a whole quantity of at least 1 and a unit amount with up to two decimals.',
      });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${this.personId()}/claims/${claim.id}/lines`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, { label, qty: Number(qty), unit }));
      if (this.session.sheetId() !== sheet) return;
      this.lineFields.update((all) => ({ ...all, [claim.id]: { label: '', qty: '', unit: '' } }));
      if (this.editingId() === claim.id) this.amount.set(saved.amount);
      this.saved.emit(saved);
    } catch (e) {
      const forbidden = e instanceof HttpErrorResponse && e.status === 403;
      this.rowError.set({
        id: claim.id,
        message: forbidden
          ? 'The line could not be added: your role may not change this sheet.'
          : 'The line could not be added: only a draft outside a closed period takes lines, and not while another user is editing it.',
      });
    }
  }

  protected setCommentText(id: number, text: string): void {
    this.commentTexts.update((texts) => ({ ...texts, [id]: text }));
    if (this.rowError()?.id === id) this.rowError.set(null);
  }

  /** Comment on a claim with the text typed on its row; an empty comment, or any comment by a viewer,
   *  on an archived sheet or in the as-of view, is refused on the row. */
  protected async addComment(claim: Claim): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const text = (this.commentTexts()[claim.id] ?? '').trim();
    if (text === '') {
      this.rowError.set({ id: claim.id, message: 'Type the comment to add to the claim.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${this.personId()}/claims/${claim.id}/comments`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, { text }));
      if (this.session.sheetId() !== sheet) return;
      this.setCommentText(claim.id, '');
      this.saved.emit(saved);
    } catch {
      this.rowError.set({ id: claim.id, message: 'The comment could not be added: this sheet takes no changes from you now.' });
    }
  }

  /** A steward moves a DRAFT, OPEN or PENDING claim to the person chosen on its row; anyone else, a
   *  SETTLED or VOID claim, or no person chosen, is refused on the row. */
  protected async reassign(claim: Claim): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const to = this.reassignTo()[claim.id] ?? '';
    if (to === '') {
      this.rowError.set({ id: claim.id, message: 'Choose the person to move the claim to.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${this.personId()}/claims/${claim.id}/reassign`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, { toPersonId: Number(to) }));
      if (this.session.sheetId() !== sheet) return;
      this.setReassignTo(claim.id, '');
      this.moved.emit(saved);
    } catch (e) {
      const forbidden = e instanceof HttpErrorResponse && e.status === 403;
      this.rowError.set({
        id: claim.id,
        message: forbidden
          ? 'The claim could not be moved: your role may not move a claim on this sheet.'
          : 'The claim could not be moved: a settled or voided claim, or a correction, stays where it is.',
      });
    }
  }

  protected reset(): void {
    this.editingId.set(null);
    this.amount.set('');
    this.date.set('');
    this.note.set('');
    this.corrects.set('');
    this.ref.set('');
    this.tags.set('');
    this.error.set(null);
  }

  /** Add a DRAFT claim from the chosen template: its amount and note as they are now, with no
   *  date, by every rule of the claim form; a refusal shows beside the form. */
  protected async addFromTemplate(): Promise<void> {
    if (this.busy()) return;
    this.error.set(null);
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const template = this.template();
    if (template === '') {
      this.error.set('Choose a template to add a claim from.');
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${this.personId()}/claims/from-template`;
    this.busy.set(true);
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, { template }));
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(saved);
    } catch (e) {
      const forbidden = e instanceof HttpErrorResponse && e.status === 403;
      this.error.set(
        forbidden
          ? 'The claim could not be added: your role may not change this sheet.'
          : `The claim could not be added from the template ${template}.`,
      );
    } finally {
      this.busy.set(false);
    }
  }

  /** Open, settle, approve, reject (with the reason typed on its row), void or re-settle a claim; a clerk
   *  settling a due above the clerk limit leaves it PENDING. A change its status does not allow, the
   *  user who opened it settling or approving it, anyone but a steward approving or rejecting it, or
   *  a reject with no reason, is refused on the row. */
  protected async act(claim: Claim, action: ClaimAction): Promise<void> {
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const reason = (this.rejectReasons()[claim.id] ?? '').trim();
    if (action === 'reject' && reason === '') {
      this.rowError.set({ id: claim.id, message: 'Type the reason the claim is rejected.' });
      return;
    }
    const voidReason = this.voidReasonChoice()[claim.id] ?? '';
    if (action === 'void' && voidReason === '' && this.voidReasons.reasons().length > 0) {
      this.rowError.set({ id: claim.id, message: 'Choose the reason the claim is voided.' });
      return;
    }
    const url =
      `/api/sheets/${encodeURIComponent(sheet)}/people/${this.personId()}/claims/${claim.id}/${action}`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(
          url,
          action === 'reject' ? { reason } : action === 'void' ? { reason: voidReason || null } : null,
        ),
      );
      if (this.session.sheetId() !== sheet) return;
      if (action === 'reject') this.setRejectReason(claim.id, '');
      this.saved.emit(saved);
    } catch (e) {
      const forbidden = e instanceof HttpErrorResponse && e.status === 403;
      this.rowError.set({
        id: claim.id,
        message:
          forbidden && action === 'settle'
            ? 'The claim could not be settled: whoever opened it cannot settle it, and your role must be allowed to settle.'
            : !forbidden && (action === 'settle' || action === 'approve')
              ? `The claim could not be ${PAST[action]}: one due above the proof threshold needs a proof first, and its status must allow it.`
            : !forbidden && action === 'delete'
              ? 'The claim could not be deleted: only a draft outside a closed period can.'
            : !forbidden && action === 'restore'
              ? 'The claim could not be restored: only a deleted claim can, on the same day it was deleted.'
            : !forbidden && action === 'open'
              ? 'The claim could not be opened: one due above the note threshold needs a note first, its status and period must allow it, and its person must be under the cap on open claims its period sets.'
            : forbidden && action === 'review'
              ? 'The claim could not be reviewed: only a steward can review a claim.'
            : forbidden && action === 'resettle'
              ? 'The claim could not be re-settled: only a steward can re-settle a claim.'
              : !forbidden && action === 'void' && this.voidReasons.reasons().length > 0
                ? 'The claim could not be voided: choose a reason from the list, and its status must allow it.'
              : !forbidden && action === 'void' && claim.status === 'SETTLED'
                ? 'The claim could not be voided: only a steward voids a settled claim, within 7 days of its settlement and outside a closed period.'
              : forbidden
                ? action === 'open' || action === 'void' || action === 'delete' || action === 'restore'
                  ? `The claim could not be ${PAST[action]}: your role may not ${action} a claim on this sheet.`
                  : `The claim could not be ${PAST[action]}: only a steward who did not open it can.`
              : `The claim could not be ${PAST[action]}.`,
      });
    }
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy()) return;
    this.error.set(null);
    this.rowError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const amount = this.amount().trim();
    if (!CLAIM_AMOUNT.test(amount)) {
      this.error.set('Type an amount with up to two decimals.');
      return;
    }
    const corrects = this.corrects().trim();
    const negative = amount.startsWith('-') && Number(amount) !== 0;
    if (negative && !NUMBER.test(corrects)) {
      this.error.set('A negative amount is a correction: type the number of the settled claim it corrects.');
      return;
    }
    if (!negative && corrects !== '') {
      this.error.set('Only a negative amount corrects a claim: leave the claim number blank.');
      return;
    }
    const body = {
      amount,
      date: this.date().trim() || null,
      note: this.note().trim(),
      corrects: corrects === '' ? null : corrects,
      ref: this.ref().trim() || null,
      tags: this.tags().trim() || null,
    };
    const id = this.editingId();
    const base = `/api/sheets/${encodeURIComponent(sheet)}/people/${this.personId()}/claims`;
    this.busy.set(true);
    try {
      const saved = await firstValueFrom(
        id === null ? this.http.post<Claim>(base, body) : this.http.put<Claim>(`${base}/${id}`, body),
      );
      if (this.session.sheetId() !== sheet) return;
      this.saved.emit(saved);
      this.reset();
    } catch {
      const what = negative ? 'correction' : 'claim';
      this.error.set(
        id === null
          ? `The ${what} could not be added: its reference may already be used on the sheet.`
          : `The ${what} could not be saved: its reference may already be used on the sheet.`,
      );
    } finally {
      this.busy.set(false);
    }
  }
}
