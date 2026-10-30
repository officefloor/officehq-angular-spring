import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, effect, inject, input, output, signal } from '@angular/core';
import { FigurePipe } from '../../core/figure';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Period, periodOf } from '../periods/periods';
import { VoidReasonList } from '../void-reasons/void-reasons';
import { ClaimTemplateList } from '../claim-templates/claim-templates';

/**
 * DRAFT when added, then OPEN, then SETTLED; a clerk's settle above the clerk limit leaves it PENDING
 * until a steward approves it (SETTLED) or rejects it (OPEN again). A steward can re-settle a SETTLED claim,
 * fixing its due again at today's figure. A DRAFT, OPEN or PENDING claim can be VOID,
 * as can a SETTLED one by a steward within 7 days of settling. A DRAFT can be DELETED (counting nowhere) and
 * restored to a DRAFT on the sheet's today it was deleted. A claim whose due is above the review threshold
 * goes to REVIEW when opened, counting in no total, until a steward reviews it (OPEN).
 */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'PENDING' | 'SETTLED' | 'VOID' | 'DELETED' | 'REVIEW';

export type ClaimAction = 'open' | 'review' | 'settle' | 'approve' | 'reject' | 'resettle' | 'void' | 'delete' | 'restore';

export interface Claim {
  id: number;
  amount: string;
  date: string;
  note: string;
  /** The amount at the rate for the person's grade, or once settled the due fixed then; null when there is no rate. */
  due: string | null;
  status: ClaimStatus;
  /** The number the claim got when opened: the next on the sheet, or within its period (P1-1, X-1); null while a draft. */
  number: string | null;
  /** The user who opened the claim, kept once voided; null while it was never opened. */
  openedBy: string | null;
  /** Whether the person's last statement binds the claim to the figure it had then. */
  stated: boolean;
  /** Fixed when the claim was added: the terms-th business day after its date (no weekend or holiday on the sheet then). */
  dueBy: string;
  /** Whether the claim is OPEN or PENDING and its due-by is before the sheet's today. */
  overdue: boolean;
  /** For a correction (a negative amount), the number of the settled claim it corrects, as shown (3, P1-1); null otherwise. */
  corrects: string | null;
  /** The claim's status log, oldest first: one line per status it has had. */
  log: ClaimLogLine[];
  /** Whether the claim is OPEN and deferred until a date after the sheet's today: it then counts in no total,
   * is not overdue and cannot be settled. */
  deferred: boolean;
  /** The date the claim was last deferred until, kept once that date has passed; null when it was never deferred. */
  deferredUntil: string | null;
  /** How many proofs the claim has. */
  proofs: number;
  /** How many of its proofs are receipts. */
  proofsReceipt: number;
  /** How many of its proofs are photos. */
  proofsPhoto: number;
  /** For a claim split off another, the number of the claim it was split from, as shown; null otherwise. */
  splitFrom: string | null;
  /** The number of the bulk settle batch that settled the claim (to SETTLED or PENDING); null when settled one at a time or never. */
  settleBatch: number | null;
  /** The user whose open editor locks the claim for everyone else; null when no editor holds it. */
  lockedBy: string | null;
  /** Whether the claim was added while its period was soft-closed. */
  lateEntry: boolean;
  /** Whether the claim is REVIEW and its review day plus the sheet's review SLA is before the sheet's today. */
  reviewLate: boolean;
  /** Whether a steward waived the claim's late charge: it then bears none, whatever its overdue state. */
  waived: boolean;
  /** Whether the claim is held: it still counts in every total; a hold no longer leaves it out. */
  held: boolean;
  /** The last sheet's today a reminder was written for the claim; null when it was never reminded. */
  remindedOn: string | null;
  /** The claim's external reference, unique on the sheet without regard to case; null when it has none. */
  ref: string | null;
  /** The claim's tags: trimmed, lower-cased, without repeats, sorted. */
  tags: string[];
  /** The reason from the sheet's list the claim was voided with; null when it is not VOID or was voided without one. */
  voidReason: string | null;
  /** The claim's comments, oldest first. */
  comments: ClaimComment[];
  /** The claim's lines, in the order they were added; with any, its amount is theirs added up. */
  lines: ClaimLine[];
  /** Whether the claim counts in the live totals: OPEN, PENDING or SETTLED, and not left out by a retirement or a deferral (a hold leaves it counting). */
  counts: boolean;
}

/** A line of a claim: its label, quantity (a whole number) and unit amount (two decimals). */
export interface ClaimLine {
  label: string;
  qty: number;
  unit: string;
}

/** A comment on a claim: who wrote it, the sheet's today when it was written, and its text. */
export interface ClaimComment {
  by: string;
  on: string;
  text: string;
}

/** A line of a claim's status log: the status, who changed it, the sheet's today then and its due then (null when none). */
export interface ClaimLogLine {
  status: ClaimStatus;
  by: string;
  on: string;
  due: string | null;
}

/** A snapshot line of a person: the day being left and their due-total on it as shown ('n/a': none). */
export interface Snapshot {
  day: string;
  dueTotal: string;
}

/** A statement line of a person: the statement's date and its due-total as shown ('n/a': none). */
export interface StatementLine {
  date: string;
  dueTotal: string;
}

/** A past retirement of a person: from the date they were retired from to the day they were unretired. */
export interface Retirement {
  from: string;
  to: string;
}

/** A hold on a person from a date to a date (both included); null to-date: open-ended. */
export interface Hold {
  from: string;
  to: string | null;
}

/** Whether the claim is OPEN or PENDING, not bound by a statement, and its person's retirement date is reached
 * on the sheet's today: it then counts in no live total. */
export function retiredOut(c: Claim, retiredNow: boolean): boolean {
  return retiredNow && !c.stated && (c.status === 'OPEN' || c.status === 'PENDING');
}

export interface PersonWithClaims {
  id: number;
  /** The version the person was last saved at; an edit sends the one its editor loaded. */
  version: number;
  name: string;
  grade: string;
  /** The person's own terms in days; null when they have none (their grade's or the sheet's apply). */
  terms: number | null;
  /** The person's contact, trimmed and lower-cased; null when they have none. */
  contact: string | null;
  /** The person's tags: trimmed, lower-cased, without repeats, sorted. */
  tags: string[];
  claims: Claim[];
  /** The amounts of the OPEN, PENDING and SETTLED claims added up, held ones included, but not deferred ones nor unbound ones left out by a retirement. */
  claimed: string;
  /** Claimed at the rate for the person's grade, held to the sheet's cap; null when the grade has no rate. */
  dueTotal: string | null;
  /** What the person's sum of figures is above the sheet's cap; 0.00 when it is not (or is n/a). */
  excess: string;
  /** How many statements were taken for the person. */
  statementCount: number;
  /** The last statement's due-total; null when there is none or it was n/a. */
  statedTotal: string | null;
  /** The sheet's today when the last statement was taken; null when there is none. */
  statementDate: string | null;
  /** Whether a retirement date is set: retired at once, whatever the date. */
  retired: boolean;
  /** The date the person is retired from; no claim dated after it can be added. Null when not retired. */
  retiredFrom: string | null;
  /** The person's past retirements, earliest first: no claim dated after the from and before the to of one can be added. */
  retirements: Retirement[];
  /** The person's snapshot lines, one per SNAPSHOT written for them, oldest first. */
  snapshots: Snapshot[];
  /** The person's statement lines, one per statement still standing, in the order taken. */
  statementLines: StatementLine[];
  /** Whether the person has any hold, whatever its dates. */
  held: boolean;
  /** The person's holds, earliest first: while one covers the sheet's today the person gets no cut of a pool;
   * their claims count in every total. */
  holds: Hold[];
  /** How many of the claims that count are overdue. */
  overdueCount: number;
  /** The due figures of the overdue claims that count, added up. */
  overdueTotal: string;
  /** The ids of the people on the sheet the person named as their patrons. */
  patrons: number[];
  /** Whether they have an OPEN or PENDING claim that counts dated within the 30 days up to and including the sheet's today. */
  active: boolean;
  /** The settled figures of their SETTLED claims dated within the 90 days up to and including the sheet's today, added up. */
  recentSettled: string;
  /** The due figures of their OPEN and PENDING claims that count, by the claim's age on the sheet's today (days from its date; one dated after it is aged 0): 0 to 30 days, 31 to 60, 61 or more. */
  aged0to30: string;
  aged31to60: string;
  aged61: string;
  /** The parts of their due-total before the cap from their SETTLED claims that count (at their frozen figures)
   *  and from their OPEN and PENDING claims that count; a claim with no figure is skipped. */
  settledTotal: string;
  openTotal: string;
  /** The person's aliases, sorted as names are compared. */
  aliases: string[];
  /** Whether their name or an alias is another's on a sheet of the sheet's tree; every edit is refused until it is not. */
  clashing: boolean;
  /** The sheet the person was transferred from; null when they were not transferred here. */
  origin: string | null;
  /** The reason of their flag while it is in force on the sheet's today; null otherwise. */
  flag: string | null;
  /** The user who may approve or reject their PENDING claims as a steward may; null when none. */
  approver: string | null;
  /** Whether the sheet's today has reached their retirement date: their OPEN and PENDING claims, unless bound by a
   * statement, then count in no live total. */
  retiredNow: boolean;
}

const AMOUNT = /^-?\d{1,12}(\.\d{1,2})?$/;
const NUMBER = /^([1-9]\d{0,8}|.{1,100}-[1-9]\d{0,8})$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

// A person's claims, newest first, and the one form that adds a claim or, once a claim's editor is
// opened, edits it. Each claim can be opened, settled, approved, rejected (with a reason), re-settled or voided. A saved or moved claim answers with
// the person, which is handed up as `saved`. Each claim can be selected, to be opened together with
// the others selected.
@Component({
  selector: 'app-claims',
  imports: [FigurePipe],
  template: `
    <ul aria-label="Claims">
      @for (c of person().claims; track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <input
            type="checkbox"
            [id]="'claim-select-' + c.id"
            data-testid="claim-select"
            [checked]="selected().has(c.id)"
            (change)="selectToggled.emit({ id: c.id, checked: checked($event) })"
          />
          <label [for]="'claim-select-' + c.id" class="visually-hidden">Select claim {{ c.amount }} {{ c.date }}</label>
          <span data-testid="claim-amount">{{ c.amount | figure }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span>Ref <span data-testid="claim-ref">{{ c.ref ?? '' }}</span></span>
          <span>Tags <span data-testid="claim-tags">{{ c.tags.join(', ') }}</span></span>
          <span>Due <span data-testid="claim-due">{{ (c.due ?? 'n/a') | figure }}</span></span>
          <span>Status <span data-testid="claim-status">{{ c.status }}</span></span>
          <span>No. <span data-testid="claim-number">{{ c.number ?? '' }}</span></span>
          <span>Opened by <span data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</span></span>
          <span>Stated <span data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</span></span>
          <span>Period <span data-testid="claim-period">{{ periodName(c) }}</span></span>
          <span>Due by <span data-testid="claim-due-by">{{ c.dueBy }}</span></span>
          <span>Overdue <span data-testid="claim-overdue">{{ c.overdue ? 'yes' : 'no' }}</span></span>
          <span>Corrects <span data-testid="claim-corrects">{{ c.corrects ?? '' }}</span></span>
          <span>Deferred <span data-testid="claim-deferred">{{ c.deferred ? 'yes' : 'no' }}</span></span>
          <span>Deferred until <span data-testid="claim-deferred-until">{{ c.deferredUntil ?? '' }}</span></span>
          <span>Proofs <span data-testid="claim-proofs">{{ c.proofs }}</span></span>
          <span>Receipts <span data-testid="claim-proofs-receipt">{{ c.proofsReceipt }}</span></span>
          <span>Photos <span data-testid="claim-proofs-photo">{{ c.proofsPhoto }}</span></span>
          <span>Split from <span data-testid="claim-split-from">{{ c.splitFrom ?? '' }}</span></span>
          <span>Batch <span data-testid="claim-batch">{{ c.settleBatch ?? '' }}</span></span>
          <span>Late entry <span data-testid="claim-late-entry">{{ c.lateEntry ? 'yes' : 'no' }}</span></span>
          <span>Review late <span data-testid="claim-review-late">{{ c.reviewLate ? 'yes' : 'no' }}</span></span>
          <span>Waived <span data-testid="claim-waived">{{ c.waived ? 'yes' : 'no' }}</span></span>
          <button type="button" data-testid="claim-waive" (click)="waive(c)">
            Waive late charge<span class="visually-hidden"> of claim {{ c.amount }} {{ c.date }}</span>
          </button>
          <span>Held <span data-testid="claim-held">{{ c.held ? 'yes' : 'no' }}</span></span>
          <button type="button" data-testid="claim-hold" (click)="toggleHold(c)">
            {{ c.held ? 'Release' : 'Hold' }}<span class="visually-hidden"> claim {{ c.amount }} {{ c.date }}</span>
          </button>
          <span>Reminded <span data-testid="claim-reminded">{{ c.remindedOn ?? '' }}</span></span>
          <button type="button" data-testid="claim-remind" (click)="remind(c)">
            Remind<span class="visually-hidden"> claim {{ c.amount }} {{ c.date }}</span>
          </button>
          <label [for]="'claim-proof-kind-' + c.id">Proof kind<span class="visually-hidden"> for claim {{ c.amount }} {{ c.date }}</span></label>
          <select
            [id]="'claim-proof-kind-' + c.id"
            data-testid="claim-proof-kind"
            (change)="setProofKind(c.id, value($event))"
          >
            <option value="" [selected]="!proofKinds()[c.id]">Choose a kind (receipt)</option>
            <option value="receipt" [selected]="proofKinds()[c.id] === 'receipt'">receipt</option>
            <option value="photo" [selected]="proofKinds()[c.id] === 'photo'">photo</option>
          </select>
          <button type="button" data-testid="claim-proof" (click)="addProof(c)">
            Add proof<span class="visually-hidden"> to claim {{ c.amount }} {{ c.date }}</span>
          </button>
          <button type="button" data-testid="claim-timeline" (click)="showTimeline(c)">
            Timeline<span class="visually-hidden"> of claim {{ c.amount }} {{ c.date }}</span>
          </button>
          @if (timelineErrors()[c.id]; as message) {
            <p role="alert" data-testid="claim-timeline-error">{{ message }}</p>
          }
          <ol aria-label="Timeline">
            @for (l of timelines()[c.id] ?? []; track $index) {
              <li data-testid="claim-timeline-line">{{ l }}</li>
            }
          </ol>
          <ol aria-label="Status log">
            @for (l of c.log; track $index) {
              <li data-testid="claim-log-line">{{ l.status }} by {{ l.by }} on {{ l.on }} due={{ l.due ?? 'n/a' }}</li>
            }
          </ol>
          <ol aria-label="Lines">
            @for (l of c.lines; track $index) {
              <li data-testid="claim-lines-line">{{ l.label }} {{ l.qty }} x {{ l.unit }}</li>
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
          <button type="button" data-testid="claim-line-add" (click)="addLine(c)">
            Add line<span class="visually-hidden"> to claim {{ c.amount }} {{ c.date }}</span>
          </button>
          <ol aria-label="Comments">
            @for (m of c.comments; track $index) {
              <li data-testid="claim-comments-line">{{ m.by }} on {{ m.on }}: {{ m.text }}</li>
            }
          </ol>
          <label [for]="'claim-comment-text-' + c.id">Comment</label>
          <input
            [id]="'claim-comment-text-' + c.id"
            data-testid="claim-comment-text"
            [value]="commentTexts()[c.id] ?? ''"
            (input)="setCommentText(c.id, value($event))"
          />
          <button type="button" data-testid="claim-comment-add" (click)="addComment(c)">
            Add comment<span class="visually-hidden"> to claim {{ c.amount }} {{ c.date }}</span>
          </button>
          <label [for]="'claim-reject-reason-' + c.id">Reject reason</label>
          <input
            [id]="'claim-reject-reason-' + c.id"
            data-testid="claim-reject-reason"
            [value]="rejectReasons()[c.id] ?? ''"
            (input)="setRejectReason(c.id, value($event))"
          />
          <label [for]="'claim-void-reason-' + c.id">Void reason</label>
          <select
            [id]="'claim-void-reason-' + c.id"
            data-testid="claim-void-reason"
            (change)="setVoidReason(c.id, value($event))"
          >
            <option value="" [selected]="!voidReasons()[c.id]">Choose a reason</option>
            @for (r of voidReasonList.reasons(); track r) {
              <option [value]="r" [selected]="voidReasons()[c.id] === r">{{ r }}</option>
            }
          </select>
          <label [for]="'claim-settle-date-' + c.id">Settle as of</label>
          <input
            [id]="'claim-settle-date-' + c.id"
            type="date"
            data-testid="claim-settle-date"
            [value]="settleDates()[c.id] ?? ''"
            (input)="setSettleDate(c.id, value($event))"
          />
          @for (a of actions; track a.action) {
            <button type="button" [attr.data-testid]="'claim-' + a.action" (click)="move(c, a.action)">
              {{ a.label }}<span class="visually-hidden"> claim {{ c.amount }} {{ c.date }}</span>
            </button>
          }
          <label [for]="'claim-defer-date-' + c.id">Defer until</label>
          <input
            [id]="'claim-defer-date-' + c.id"
            type="date"
            data-testid="claim-defer-date"
            [value]="deferDates()[c.id] ?? ''"
            (input)="setDeferDate(c.id, value($event))"
          />
          <button type="button" data-testid="claim-defer" (click)="defer(c)">
            Defer<span class="visually-hidden"> claim {{ c.amount }} {{ c.date }}</span>
          </button>
          <label [for]="'claim-date-new-' + c.id">New date</label>
          <input
            [id]="'claim-date-new-' + c.id"
            type="date"
            data-testid="claim-date-new"
            [value]="newDates()[c.id] ?? ''"
            (input)="setNewDate(c.id, value($event))"
          />
          <button type="button" data-testid="claim-redate" (click)="redate(c)">
            Re-date<span class="visually-hidden"> claim {{ c.amount }} {{ c.date }}</span>
          </button>
          <label [for]="'claim-split-amount-' + c.id">Split off amount</label>
          <input
            [id]="'claim-split-amount-' + c.id"
            inputmode="decimal"
            data-testid="claim-split-amount"
            [value]="splitAmounts()[c.id] ?? ''"
            (input)="setSplitAmount(c.id, value($event))"
          />
          <button type="button" data-testid="claim-split" (click)="split(c)">
            Split<span class="visually-hidden"> claim {{ c.amount }} {{ c.date }}</span>
          </button>
          <label [for]="'claim-settle-part-amount-' + c.id">Part-settle amount</label>
          <input
            [id]="'claim-settle-part-amount-' + c.id"
            inputmode="decimal"
            data-testid="claim-settle-part-amount"
            [value]="partAmounts()[c.id] ?? ''"
            (input)="setPartAmount(c.id, value($event))"
          />
          <button type="button" data-testid="claim-settle-part" (click)="settlePart(c)">
            Part-settle<span class="visually-hidden"> claim {{ c.amount }} {{ c.date }}</span>
          </button>
          <label [for]="'claim-reassign-to-' + c.id">Reassign to</label>
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
          <button type="button" data-testid="claim-reassign" (click)="reassign(c)">
            Reassign<span class="visually-hidden"> claim {{ c.amount }} {{ c.date }}</span>
          </button>
          @if (claimError()?.id === c.id) {
            <p role="alert" data-testid="claim-error">{{ claimError()?.message }}</p>
          }
          <button type="button" data-testid="claim-edit" (click)="edit(c)">
            Edit claim<span class="visually-hidden"> {{ c.amount }} {{ c.date }}</span>
          </button>
          <label [for]="'claim-inline-amount-' + c.id">Amount</label>
          <input
            [id]="'claim-inline-amount-' + c.id"
            inputmode="decimal"
            data-testid="claim-inline-amount"
            [value]="inlineAmounts()[c.id] ?? ''"
            (input)="setInlineAmount(c.id, value($event))"
          />
          <button type="button" data-testid="claim-inline-save" (click)="saveAmount(c)">
            Save amount<span class="visually-hidden"> of claim {{ c.amount }} {{ c.date }}</span>
          </button>
          @if (editing() === c.id) {
            <button type="button" data-testid="claim-cancel" (click)="cancel()">Cancel</button>
          }
        </li>
      }
    </ul>
    <form (submit)="save($event)" [attr.aria-label]="(editing() === null ? 'Add claim for ' : 'Edit claim for ') + person().name">
      <label [for]="fid('amount')">Amount</label>
      <input
        [id]="fid('amount')"
        inputmode="decimal"
        data-testid="claim-form-amount"
        [disabled]="locking()"
        [value]="amount()"
        (input)="amount.set(value($event)); error.set('')"
      />
      <label [for]="fid('date')">Date</label>
      <input
        [id]="fid('date')"
        type="date"
        data-testid="claim-form-date"
        [disabled]="locking()"
        [value]="date()"
        (input)="date.set(value($event)); error.set('')"
      />
      <label [for]="fid('note')">Note</label>
      <input
        [id]="fid('note')"
        data-testid="claim-form-note"
        [disabled]="locking()"
        [value]="note()"
        (input)="note.set(value($event)); error.set('')"
      />
      <label [for]="fid('corrects')">Corrects claim no.</label>
      <input
        [id]="fid('corrects')"
        inputmode="numeric"
        data-testid="claim-form-corrects"
        [disabled]="locking()"
        [value]="corrects()"
        (input)="corrects.set(value($event)); error.set('')"
      />
      <label [for]="fid('ref')">External reference</label>
      <input
        [id]="fid('ref')"
        data-testid="claim-form-ref"
        [disabled]="locking()"
        [value]="ref()"
        (input)="ref.set(value($event)); error.set('')"
      />
      <label [for]="fid('tags')">Tags (comma separated)</label>
      <input
        [id]="fid('tags')"
        data-testid="claim-form-tags"
        [disabled]="locking()"
        [value]="tags()"
        (input)="tags.set(value($event)); error.set('')"
      />
      <button type="submit" data-testid="claim-form-submit" [disabled]="locking()">
        {{ editing() === null ? 'Add claim' : 'Save claim' }}
      </button>
      @if (error()) {
        <p role="alert" data-testid="claim-form-error">{{ error() }}</p>
      }
    </form>
    <label [for]="fid('template')">Template</label>
    <select
      [id]="fid('template')"
      data-testid="claim-template-select"
      (change)="template.set(value($event)); error.set('')"
    >
      <option value="" [selected]="template() === ''">Choose a template</option>
      @for (t of templateList.templates(); track t.name) {
        <option [value]="t.name" [selected]="t.name === template()">{{ t.name }}</option>
      }
    </select>
    <button type="button" data-testid="claim-template-add" (click)="addFromTemplate()">
      Add claim from template<span class="visually-hidden"> for {{ person().name }}</span>
    </button>
  `,
})
export class Claims implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);
  protected readonly voidReasonList = inject(VoidReasonList);
  protected readonly templateList = inject(ClaimTemplateList);

  readonly person = input.required<PersonWithClaims>();
  readonly periods = input<Period[]>([]);
  readonly saved = output<PersonWithClaims>();
  /** The people on the sheet a claim can be reassigned to. */
  readonly people = input<readonly { id: number; name: string }[]>([]);
  /** The ids of the claims selected to be opened together. */
  readonly selected = input<ReadonlySet<number>>(new Set());
  readonly selectToggled = output<{ id: number; checked: boolean }>();

  protected readonly amount = signal('');
  protected readonly date = signal('');
  protected readonly note = signal('');
  protected readonly corrects = signal('');
  protected readonly ref = signal('');
  protected readonly tags = signal('');
  protected readonly editing = signal<number | null>(null);
  /** The name of the template chosen to add a claim from; '' while none is. */
  protected readonly template = signal('');
  protected readonly error = signal('');
  /** An edit button waiting for its claim's lock: the form is held until the answer comes. */
  protected readonly locking = signal(false);
  /** The refusal of the last action taken on a claim's row, shown on that row. */
  protected readonly claimError = signal<{ id: number; message: string } | null>(null);
  /** The reason typed for rejecting each claim, by claim id. */
  protected readonly rejectReasons = signal<Record<number, string>>({});
  /** The date typed to settle each claim as of, by claim id; blank settles it as of today. */
  protected readonly settleDates = signal<Record<number, string>>({});
  /** The void reason chosen for each claim, by claim id. */
  protected readonly voidReasons = signal<Record<number, string>>({});
  /** The date typed for deferring each claim until, by claim id. */
  protected readonly deferDates = signal<Record<number, string>>({});
  /** The new date typed for re-dating each claim, by claim id. */
  protected readonly newDates = signal<Record<number, string>>({});
  /** The amount typed for splitting off each claim, by claim id. */
  protected readonly splitAmounts = signal<Record<number, string>>({});

  /** The amount typed for part-settling each claim, by claim id. */
  protected readonly partAmounts = signal<Record<number, string>>({});
  /** The line typed for each claim, by claim id. */
  protected readonly lineFields = signal<Record<number, { label: string; qty: string; unit: string }>>({});
  /** The comment typed for each claim, by claim id. */
  protected readonly commentTexts = signal<Record<number, string>>({});
  /** The new amount typed on each claim's row, by claim id. */
  protected readonly inlineAmounts = signal<Record<number, string>>({});
  /** The id of the person chosen to reassign each claim to, by claim id. */
  protected readonly reassignTo = signal<Record<number, string>>({});
  /** The proof kind chosen on each claim's row; none chosen adds a receipt. */
  protected readonly proofKinds = signal<Record<number, string>>({});
  /** The audit lines shown for each claim's timeline, newest first, by claim id. */
  protected readonly timelines = signal<Record<number, string[]>>({});
  /** Why a claim's timeline could not be read, by claim id. */
  protected readonly timelineErrors = signal<Record<number, string>>({});
  /** Bumped on every timeline press and reload, so only the latest press's answer is shown. */
  private timelineShows = 0;
  /** The other people on the sheet, whom this person's claims can be reassigned to. */
  protected readonly others = computed(() => this.people().filter((p) => p.id !== this.person().id));
  /** A submit or action still waiting for its answer: another press meanwhile is ignored. */
  private busy = false;
  protected readonly actions: { action: ClaimAction; label: string }[] = [
    { action: 'open', label: 'Open' },
    { action: 'review', label: 'Review' },
    { action: 'settle', label: 'Settle' },
    { action: 'approve', label: 'Approve' },
    { action: 'reject', label: 'Reject' },
    { action: 'resettle', label: 'Re-settle' },
    { action: 'void', label: 'Void' },
    { action: 'delete', label: 'Delete' },
    { action: 'restore', label: 'Restore' },
  ];

  /** The name of the period whose dates contain the claim's due-by; '' when none does. */
  protected periodName(c: Claim): string {
    return periodOf(this.periods(), c.dueBy)?.name ?? '';
  }

  protected setRejectReason(id: number, reason: string): void {
    this.rejectReasons.update((all) => ({ ...all, [id]: reason }));
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  protected setSettleDate(id: number, on: string): void {
    this.settleDates.update((all) => ({ ...all, [id]: on }));
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  protected setVoidReason(id: number, reason: string): void {
    this.voidReasons.update((all) => ({ ...all, [id]: reason }));
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  protected setDeferDate(id: number, until: string): void {
    this.deferDates.update((all) => ({ ...all, [id]: until }));
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  /** Defer an OPEN claim until the date typed, which must be after the sheet's today; a refusal shows on the claim's row. */
  protected async defer(c: Claim): Promise<void> {
    if (this.busy) return;
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const until = (this.deferDates()[c.id] ?? '').trim();
    if (!DATE.test(until)) {
      this.claimError.set({ id: c.id, message: 'Enter the date to defer the claim until.' });
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const person = await firstValueFrom(
        this.http.post<PersonWithClaims>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/defer`,
          { until },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.deferDates.update((all) => ({ ...all, [c.id]: '' }));
      this.saved.emit(person);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refusal = err instanceof HttpErrorResponse && err.status === 409 ? err.error?.message : undefined;
      this.claimError.set({ id: c.id, message: refusal ?? 'The claim cannot be deferred to that date.' });
    } finally {
      this.busy = false;
    }
  }

  protected setNewDate(id: number, date: string): void {
    this.newDates.update((all) => ({ ...all, [id]: date }));
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  /** A steward re-dates an OPEN or PENDING claim to the date typed; a refusal shows on the claim's row. */
  protected async redate(c: Claim): Promise<void> {
    if (this.busy) return;
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const date = (this.newDates()[c.id] ?? '').trim();
    if (!DATE.test(date)) {
      this.claimError.set({ id: c.id, message: 'Enter the new date of the claim.' });
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const person = await firstValueFrom(
        this.http.post<PersonWithClaims>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/redate`,
          { date },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.newDates.update((all) => ({ ...all, [c.id]: '' }));
      this.saved.emit(person);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refusal =
        err instanceof HttpErrorResponse && (err.status === 409 || err.status === 403) ? err.error?.message : undefined;
      this.claimError.set({ id: c.id, message: refusal ?? 'The claim cannot be re-dated to that date.' });
    } finally {
      this.busy = false;
    }
  }

  protected setSplitAmount(id: number, amount: string): void {
    this.splitAmounts.update((all) => ({ ...all, [id]: amount }));
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  /**
   * Split the amount typed off an OPEN claim into a new OPEN claim; the amount must be more than 0.00 and
   * less than the claim's. A refusal shows on the claim's row.
   */
  protected async split(c: Claim): Promise<void> {
    if (this.busy) return;
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const amount = (this.splitAmounts()[c.id] ?? '').trim();
    if (!/^\d{1,12}(\.\d{1,2})?$/.test(amount)) {
      this.claimError.set({ id: c.id, message: 'Enter the amount to split off, with up to two decimals.' });
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const person = await firstValueFrom(
        this.http.post<PersonWithClaims>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/split`,
          { amount },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.splitAmounts.update((all) => ({ ...all, [c.id]: '' }));
      this.saved.emit(person);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refusal =
        err instanceof HttpErrorResponse && (err.status === 409 || err.status === 403) ? err.error?.message : undefined;
      this.claimError.set({ id: c.id, message: refusal ?? 'The claim cannot be split by that amount.' });
    } finally {
      this.busy = false;
    }
  }

  protected setPartAmount(id: number, amount: string): void {
    this.partAmounts.update((all) => ({ ...all, [id]: amount }));
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  /**
   * Part-settle an OPEN claim: split the amount typed off it and settle the new claim at once. A refusal of
   * either shows on the claim's row and changes nothing.
   */
  protected async settlePart(c: Claim): Promise<void> {
    if (this.busy) return;
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const amount = (this.partAmounts()[c.id] ?? '').trim();
    if (!/^\d{1,12}(\.\d{1,2})?$/.test(amount)) {
      this.claimError.set({ id: c.id, message: 'Enter the amount to settle, with up to two decimals.' });
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const person = await firstValueFrom(
        this.http.post<PersonWithClaims>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/settle-part`,
          { amount },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.partAmounts.update((all) => ({ ...all, [c.id]: '' }));
      this.saved.emit(person);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refusal =
        err instanceof HttpErrorResponse && (err.status === 409 || err.status === 403) ? err.error?.message : undefined;
      this.claimError.set({ id: c.id, message: refusal ?? 'The claim cannot be part-settled by that amount.' });
    } finally {
      this.busy = false;
    }
  }

  /** A steward waives an OPEN or PENDING claim's late charge; a refusal shows on the claim's row. */
  protected async waive(c: Claim): Promise<void> {
    if (this.busy) return;
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const person = await firstValueFrom(
        this.http.post<PersonWithClaims>(`/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/waive`, {}, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.saved.emit(person);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refusal =
        err instanceof HttpErrorResponse && (err.status === 409 || err.status === 403) ? err.error?.message : undefined;
      this.claimError.set({ id: c.id, message: refusal ?? 'The claim\'s late charge cannot be waived.' });
    } finally {
      this.busy = false;
    }
  }

  /** Hold an OPEN or PENDING claim, or release it when held; a refusal shows on the claim's row. */
  protected async toggleHold(c: Claim): Promise<void> {
    if (this.busy) return;
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const person = await firstValueFrom(
        this.http.post<PersonWithClaims>(`/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/hold`, {}, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.saved.emit(person);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refusal =
        err instanceof HttpErrorResponse && (err.status === 409 || err.status === 403) ? err.error?.message : undefined;
      this.claimError.set({ id: c.id, message: refusal ?? (c.held ? 'The claim cannot be released.' : 'The claim cannot be held.') });
    } finally {
      this.busy = false;
    }
  }

  /** Remind an OPEN or PENDING claim, once per sheet's today; a refusal shows on the claim's row. */
  protected async remind(c: Claim): Promise<void> {
    if (this.busy) return;
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const person = await firstValueFrom(
        this.http.post<PersonWithClaims>(`/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/remind`, {}, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.saved.emit(person);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refusal =
        err instanceof HttpErrorResponse && (err.status === 409 || err.status === 403) ? err.error?.message : undefined;
      this.claimError.set({ id: c.id, message: refusal ?? 'The claim cannot be reminded.' });
    } finally {
      this.busy = false;
    }
  }

  protected setLineField(id: number, field: 'label' | 'qty' | 'unit', text: string): void {
    this.lineFields.update((all) => ({ ...all, [id]: { ...(all[id] ?? { label: '', qty: '', unit: '' }), [field]: text } }));
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  /** Add the line typed to the claim; a line not filled in rightly, or a refusal, shows on the claim's row. */
  protected async addLine(c: Claim): Promise<void> {
    if (this.busy) return;
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const f = this.lineFields()[c.id] ?? { label: '', qty: '', unit: '' };
    const label = f.label.trim();
    const qty = f.qty.trim();
    const unit = f.unit.trim();
    if (!label || !/^[1-9]\d{0,5}$/.test(qty) || !/^\d{1,12}(\.\d{1,2})?$/.test(unit)) {
      this.claimError.set({
        id: c.id,
        message: 'Enter a label, a whole quantity of at least 1 and a unit amount with up to two decimals.',
      });
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const person = await firstValueFrom(
        this.http.post<PersonWithClaims>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/lines`,
          { label, qty, unit },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.lineFields.update((all) => ({ ...all, [c.id]: { label: '', qty: '', unit: '' } }));
      this.saved.emit(person);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refusal =
        err instanceof HttpErrorResponse && (err.status === 409 || err.status === 403) ? err.error?.message : undefined;
      this.claimError.set({ id: c.id, message: refusal ?? 'The line cannot be added to this claim.' });
    } finally {
      this.busy = false;
    }
  }

  protected setCommentText(id: number, text: string): void {
    this.commentTexts.update((all) => ({ ...all, [id]: text }));
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  /** Comment the claim with the text typed; an empty comment, or a refusal, shows on the claim's row. */
  protected async addComment(c: Claim): Promise<void> {
    if (this.busy) return;
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const text = (this.commentTexts()[c.id] ?? '').trim();
    if (!text) {
      this.claimError.set({ id: c.id, message: 'Enter a comment.' });
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const person = await firstValueFrom(
        this.http.post<PersonWithClaims>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/comments`,
          { text },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.commentTexts.update((all) => ({ ...all, [c.id]: '' }));
      this.saved.emit(person);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refusal =
        err instanceof HttpErrorResponse && (err.status === 409 || err.status === 403) ? err.error?.message : undefined;
      this.claimError.set({ id: c.id, message: refusal ?? 'The comment cannot be added to this claim.' });
    } finally {
      this.busy = false;
    }
  }

  protected setProofKind(id: number, kind: string): void {
    this.proofKinds.update((all) => ({ ...all, [id]: kind }));
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  /** Add a proof of the kind chosen to the claim, raising its proofs count by one; a refusal shows on the claim's row. */
  protected async addProof(c: Claim): Promise<void> {
    if (this.busy) return;
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const person = await firstValueFrom(
        this.http.post<PersonWithClaims>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/proof`,
          { kind: this.proofKinds()[c.id] ?? '' },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.proofKinds.update((all) => ({ ...all, [c.id]: '' }));
      this.saved.emit(person);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refusal = err instanceof HttpErrorResponse && err.status === 409 ? err.error?.message : undefined;
      this.claimError.set({ id: c.id, message: refusal ?? 'A proof cannot be added to this claim.' });
    } finally {
      this.busy = false;
    }
  }

  protected setReassignTo(id: number, personId: string): void {
    this.reassignTo.update((all) => ({ ...all, [id]: personId }));
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  /** A steward moves a claim to the chosen person; a refusal shows on the claim's row. */
  protected async reassign(c: Claim): Promise<void> {
    if (this.busy) return;
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const to = this.reassignTo()[c.id] ?? '';
    if (to === '') {
      this.claimError.set({ id: c.id, message: 'Choose the person to reassign the claim to.' });
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const moved = await firstValueFrom(
        this.http.post<PersonWithClaims[]>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/reassign`,
          { personId: Number(to) },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.reassignTo.update((all) => ({ ...all, [c.id]: '' }));
      for (const person of moved) this.saved.emit(person);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refusal = err instanceof HttpErrorResponse && err.status === 409 ? err.error?.message : undefined;
      this.claimError.set({ id: c.id, message: refusal ?? `A ${c.status} claim cannot be reassigned.` });
    } finally {
      this.busy = false;
    }
  }

  protected fid(field: string): string {
    return `claim-${this.person().id}-${field}`;
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected checked(e: Event): boolean {
    return (e.target as HTMLInputElement).checked;
  }

  /** An editor the signed-in user left open on one of the person's claims is open again once the sheet is re-read. */
  constructor() {
    // A shown timeline belongs to the data it was read with: it goes once the person or the sheet is re-read.
    effect(() => {
      this.person();
      this.session.sheet();
      this.session.loads();
      this.timelineShows++;
      this.timelines.set({});
      this.timelineErrors.set({});
    });
  }

  /** List the audit lines about the claim, newest first, as the audit file holds them. */
  protected async showTimeline(c: Claim): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) return;
    const show = ++this.timelineShows;
    this.timelines.update((all) => ({ ...all, [c.id]: [] }));
    this.timelineErrors.update((all) => ({ ...all, [c.id]: '' }));
    try {
      const lines = await firstValueFrom(
        this.http.get<string[]>(`/api/sheets/${encodeURIComponent(sheet)}/audit/claims/${c.id}`),
      );
      if (show === this.timelineShows) this.timelines.update((all) => ({ ...all, [c.id]: lines }));
    } catch {
      if (show === this.timelineShows) this.timelineErrors.update((all) => ({ ...all, [c.id]: 'Could not read the claim\'s timeline.' }));
    }
  }

  ngOnInit(): void {
    const mine = this.person().claims.find((c) => c.status === 'DRAFT' && c.lockedBy !== null && c.lockedBy === this.session.user());
    if (mine) this.fill(mine);
  }

  /** Why the claim's editor cannot be opened, as far as the screen knows; null when it may be. */
  private editRefusal(c: Claim): string | null {
    if (c.status !== 'DRAFT') return `A ${c.status} claim cannot be edited; void it and add a new one.`;
    const period = periodOf(this.periods(), c.dueBy);
    if (period?.closed) return `The claim is in the closed period ${period.name}, so it cannot be edited.`;
    if (c.lockedBy !== null && c.lockedBy !== this.session.user()) return `${c.lockedBy} is editing this claim.`;
    return null;
  }

  protected setInlineAmount(id: number, amount: string): void {
    this.inlineAmounts.update((all) => ({ ...all, [id]: amount }));
    if (this.claimError()?.id === id) this.claimError.set(null);
  }

  /**
   * Change a DRAFT claim's amount on its row, by the claim form's rules: it is refused on the row when the
   * amount is malformed, the claim cannot be edited (not a DRAFT, in a closed period, or locked by another
   * user), or the user is a viewer. A saved amount closes the user's own editor on the claim.
   */
  protected async saveAmount(c: Claim): Promise<void> {
    if (this.busy) return;
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const amount = (this.inlineAmounts()[c.id] ?? '').trim();
    if (!AMOUNT.test(amount)) {
      this.claimError.set({ id: c.id, message: 'Enter an amount with up to two decimals; a negative amount is a correction.' });
      return;
    }
    const refusal = this.editRefusal(c);
    if (refusal !== null) {
      this.claimError.set({ id: c.id, message: refusal });
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const person = await firstValueFrom(
        this.http.put<PersonWithClaims>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/amount`,
          { amount },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.inlineAmounts.update((all) => ({ ...all, [c.id]: '' }));
      if (this.editing() === c.id) this.reset();
      this.saved.emit(person);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refused =
        err instanceof HttpErrorResponse && (err.status === 409 || err.status === 403) ? err.error?.message : undefined;
      this.claimError.set({ id: c.id, message: refused || 'The claim\'s amount cannot be changed to that.' });
    } finally {
      this.busy = false;
    }
  }

  /**
   * Open a claim's editor, locking the claim for other users until it is saved or cancelled (or the sheet's
   * today changes). A claim that cannot be edited (not a DRAFT, in a closed period, or locked by another
   * user), or a viewer's edit, is refused on its row and takes no lock.
   */
  protected async edit(c: Claim): Promise<void> {
    if (this.busy) return;
    this.claimError.set(null);
    const refusal = this.editRefusal(c);
    if (refusal !== null) {
      this.claimError.set({ id: c.id, message: refusal });
      return;
    }
    const sheet = this.session.sheet();
    if (!sheet) return;
    const previous = this.editing();
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    this.locking.set(true);
    try {
      const person = await firstValueFrom(
        this.http.post<PersonWithClaims>(`/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/lock`, null, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      if (previous !== null && previous !== c.id) this.unlock(previous);
      this.saved.emit(person);
      this.fill(person.claims.find((x) => x.id === c.id) ?? c);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refused =
        err instanceof HttpErrorResponse && (err.status === 409 || err.status === 403) ? err.error?.message : undefined;
      this.claimError.set({ id: c.id, message: refused || 'The claim cannot be edited now; another user may be editing it.' });
    } finally {
      this.busy = false;
      this.locking.set(false);
    }
  }

  private fill(c: Claim): void {
    this.editing.set(c.id);
    this.amount.set(c.amount);
    this.date.set(c.date);
    this.note.set(c.note);
    this.corrects.set(c.corrects ?? '');
    this.ref.set(c.ref ?? '');
    this.tags.set(c.tags.join(', '));
    this.error.set('');
  }

  /** Close the claim's editor without saving, releasing its lock. */
  protected cancel(): void {
    const id = this.editing();
    if (id !== null) {
      if (this.claimError()?.id === id) this.claimError.set(null);
      this.unlock(id);
    }
    this.reset();
  }

  /** Release the signed-in user's lock on a claim; nothing to show either way. */
  private unlock(id: number): void {
    const sheet = this.session.sheet();
    if (!sheet) return;
    const headers = { 'X-User': this.session.user() ?? '' };
    this.http.post(`/api/sheets/${encodeURIComponent(sheet)}/claims/${id}/unlock`, null, { headers }).subscribe({
      error: () => undefined,
    });
  }

  protected reset(): void {
    this.editing.set(null);
    this.amount.set('');
    this.date.set('');
    this.note.set('');
    this.corrects.set('');
    this.ref.set('');
    this.tags.set('');
    this.error.set('');
  }

  /** Add a DRAFT claim with the chosen template's amount and note now, as a claim with no date; a refusal shows on the form. */
  protected async addFromTemplate(): Promise<void> {
    if (this.busy) return;
    this.error.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const name = this.template();
    if (name === '') {
      this.error.set('Choose a template to add a claim from.');
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const person = await firstValueFrom(
        this.http.post<PersonWithClaims>(
          `/api/sheets/${encodeURIComponent(sheet)}/people/${this.person().id}/claims/from-template`,
          { name },
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      this.saved.emit(person);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const answer = err instanceof HttpErrorResponse ? err : null;
      this.error.set(answer?.error?.message ?? 'Could not add the claim from the template.');
    } finally {
      this.busy = false;
    }
  }

  protected async save(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.error.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const amount = this.amount().trim();
    const date = this.date().trim();
    const note = this.note();
    const corrects = this.corrects().trim();
    const ref = this.ref().trim();
    if (!AMOUNT.test(amount)) {
      this.error.set('Enter an amount with up to two decimals; a negative amount is a correction.');
      return;
    }
    if (corrects !== '' && !NUMBER.test(corrects)) {
      this.error.set('Enter the number of the claim this corrects, or leave it blank.');
      return;
    }
    if (date !== '' && !DATE.test(date)) {
      this.error.set('Enter a date, or leave it blank for the entry date (or today).');
      return;
    }
    const id = this.editing();
    const base = `/api/sheets/${encodeURIComponent(sheet)}`;
    const body = { amount, date: date === '' ? null : date, note, corrects: corrects === '' ? null : corrects,
      ref: ref === '' ? null : ref, tags: this.tags() };
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const person = await firstValueFrom(
        id === null
          ? this.http.post<PersonWithClaims>(`${base}/people/${this.person().id}/claims`, body, { headers })
          : this.http.put<PersonWithClaims>(`${base}/claims/${id}`, body, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.saved.emit(person);
      this.reset();
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      const refusal = err instanceof HttpErrorResponse && err.status === 409 ? err.error?.message : undefined;
      this.error.set(refusal ?? (id === null ? 'Could not add the claim.' : 'Could not save the claim.'));
    } finally {
      this.busy = false;
    }
  }

  /**
   * Open, review, settle, approve, reject, re-settle, void, delete or restore a claim; a change its status does not allow is refused on its
   * row, as is a reject without a reason.
   */
  protected async move(c: Claim, action: ClaimAction): Promise<void> {
    if (this.busy) return;
    this.claimError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const reason = (this.rejectReasons()[c.id] ?? '').trim();
    if (action === 'reject' && reason === '') {
      this.claimError.set({ id: c.id, message: 'Enter a reason to reject the claim.' });
      return;
    }
    const voidReason = this.voidReasons()[c.id] ?? '';
    if (action === 'void' && this.voidReasonList.reasons().length > 0 && !this.voidReasonList.reasons().includes(voidReason)) {
      this.claimError.set({ id: c.id, message: 'Choose a reason to void the claim.' });
      return;
    }
    const on = (this.settleDates()[c.id] ?? '').trim();
    if (action === 'settle') this.settleDates.update((all) => ({ ...all, [c.id]: '' }));
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const person = await firstValueFrom(
        this.http.post<PersonWithClaims>(
          `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/${action}`,
          action === 'reject'
            ? { reason }
            : action === 'void'
              ? { reason: voidReason === '' ? null : voidReason }
              : action === 'settle' && on !== ''
                ? { on }
                : null,
          { headers },
        ),
      );
      if (this.session.sheet() !== sheet) return;
      if (action === 'reject') this.rejectReasons.update((all) => ({ ...all, [c.id]: '' }));
      if (action === 'void') this.voidReasons.update((all) => ({ ...all, [c.id]: '' }));
      this.saved.emit(person);
    } catch (err) {
      if (this.session.sheet() !== sheet) return;
      if (err instanceof HttpErrorResponse && err.status === 403) {
        this.claimError.set({ id: c.id, message: err.error?.message || `You opened this claim, so another user must ${action} it.` });
        return;
      }
      const refusal = err instanceof HttpErrorResponse && err.status === 409 ? err.error?.message : undefined;
      this.claimError.set({ id: c.id, message: refusal ?? `A ${c.status} claim cannot be ${PAST[action]}.` });
    } finally {
      this.busy = false;
    }
  }
}

const PAST: Record<ClaimAction, string> = {
  open: 'opened',
  review: 'reviewed',
  settle: 'settled',
  approve: 'approved',
  reject: 'rejected',
  resettle: 're-settled',
  void: 'voided',
  delete: 'deleted',
  restore: 'restored',
};
