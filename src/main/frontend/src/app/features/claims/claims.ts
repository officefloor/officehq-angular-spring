import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, effect, inject, input, output, signal, untracked } from '@angular/core';
import { FigPipe } from '../../core/locale';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { PeriodStore, PeriodView, inClosedPeriod, periodOn } from '../periods/periods';
import { DatedGrade, RateStore, RateView, atLeast, dueOn, gradeOn, rateOn, sumSkippingNa } from '../rates/rates';
import { SettingsStore } from '../settings/settings';
import { VoidReasonStore } from '../void-reasons/void-reasons';
import { ClaimTemplateStore } from '../claim-templates/claim-templates';

export interface Claim {
  id: number;
  personId: number;
  amount: string;
  date: string;
  note: string;
  // The claim's external reference, unique on the sheet without regard to case; null for a claim with none.
  ref: string | null;
  // The claim's tags, trimmed, lower-cased, without repeats and sorted; none for a claim with none.
  tags: string[];
  status: ClaimStatus;
  // Given when the claim is opened: the sheet's next (1, 2, ...) or, while the sheet numbers by period, its
  // period's next (P1-1; X-1 for no period); null for a claim never opened.
  number: string | null;
  // The due fixed when the claim was settled; null while its due is live.
  settledDue: string | null;
  // The user who opened the claim; null for a claim never opened. That user cannot settle it.
  openedBy: string | null;
  editedBy: string | null;
  // Whether the person's last statement binds the claim, and the figure it binds it to (null for n/a).
  stated: boolean;
  statedDue: string | null;
  // The number of the settled claim a correction (a negative amount) corrects; null for any other claim.
  corrects: string | null;
  // The date the claim's rounding follows: its own, or for a correction the date of the claim it corrects.
  roundsOn: string;
  // The date the claim is due by: fixed when it was added, from the terms for its person's grade on its date.
  dueBy: string;
  // The date the claim was deferred until; null for one never deferred. See deferred().
  deferredUntil: string | null;
  // The last sheet's today a reminder was written for the claim; null for one never reminded.
  remindedOn: string | null;
  // The sheet's today a steward waived the claim's late charge; null for one never waived. See waivedOn().
  waivedOn: string | null;
  // Whether the claim is held; a held claim still counts in every total.
  held: boolean;
  // How many proofs the claim has. One whose due is above the proof threshold in force on its date cannot be
  // settled or approved while it has none, nor while it lacks one of each of the sheet's proof kinds.
  proofs: number;
  // How many of its proofs are receipts, and how many photos.
  proofReceipts: number;
  proofPhotos: number;
  // The number of the claim this one was split off; null for a claim not split off another.
  splitFrom: string | null;
  // The claim's status log, oldest first: a line for each status it has had, from its DRAFT on.
  log: ClaimLogLine[];
  // The user whose open editor holds the claim, so no one else may edit it; null while none does.
  lockedBy: string | null;
  // The mark the claim was added at: a forward-only rate applies only to claims with a greater one than its own;
  // null for a claim just added (the newest).
  mark: number | null;
  // Whether the claim was added while its period was soft-closed.
  lateEntry: boolean;
  // The reason the claim was voided with, chosen from the sheet's list; null for one voided without one, or not voided.
  voidReason: string | null;
  // The claim's comments, in the order they were written.
  comments: ClaimComment[];
  // The number of the bulk settle batch that settled the claim (or made it PENDING); null for none.
  batch: number | null;
  // The claim's lines, in the order they were added; while it has any, its amount is the sum of theirs.
  lines: ClaimLine[];
}

// A line of a claim: its label, a whole quantity of at least 1 and a unit amount (two decimals).
export interface ClaimLine {
  label: string;
  qty: number;
  unit: string;
}

// A comment on a claim: who wrote it, the sheet's today then, and its text.
export interface ClaimComment {
  by: string;
  on: string | null;
  text: string;
}

// A status the claim came to have: who made the change, the sheet's today then, and the claim's due then (null
// for n/a).
export interface ClaimLogLine {
  status: ClaimStatus;
  by: string;
  on: string | null;
  due: string | null;
}

// A clerk settling a claim whose due is above the clerk limit makes it PENDING, for a steward to approve (SETTLED)
// or reject (OPEN again). A deleted DRAFT is DELETED, counts nowhere, and on the day it was deleted can be restored.
// Opening a claim whose due is above the review threshold in force on its date makes it REVIEW, unnumbered and
// counting nowhere, until a steward reviews it (OPEN).
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'PENDING' | 'SETTLED' | 'VOID' | 'DELETED' | 'REVIEW';

type ClaimAction = 'open' | 'review' | 'settle' | 'resettle' | 'approve' | 'reject' | 'void' | 'delete' | 'restore';

/** Only open, pending and settled claims count toward what is claimed and due. */
export function counts(c: Claim): boolean {
  return isOpen(c) || c.status === 'SETTLED';
}

/**
 * The status the claim had at the end of the day, as its log records it: the last line logged on or before it (a
 * line with no date counts as before any day); null when the claim was added after it. A claim with no log has its
 * status now.
 */
export function statusAtEndOf(c: Pick<Claim, 'status' | 'log'>, day: string): ClaimStatus | null {
  if (c.log.length === 0) return c.status;
  let then: ClaimStatus | null = null;
  for (const line of c.log) if (line.on === null || line.on <= day) then = line.status;
  return then;
}

/** Whether the claim is open: OPEN or PENDING. An open claim goes overdue and bears the late charge. */
export function isOpen(c: Claim): boolean {
  return c.status === 'OPEN' || c.status === 'PENDING';
}

/** Whether the person's last statement binds the claim's figure (a pending claim is bound like any other). */
export function isBound(c: Claim): boolean {
  return c.stated;
}

/** Whether the claim is a correction: one with a negative amount. */
export function isCorrection(c: Claim): boolean {
  return c.amount.startsWith('-');
}

/**
 * Whether the claim's live figures round half up: by the rounding mode in force on the date its rounding follows
 * when one is; else when that date is before the policy date (the sheet's today when the setting is blank). Else
 * they round half to even.
 */
export function roundsHalfUp(c: Claim, late: Rounding): boolean {
  const mode = late.rounding(c.roundsOn);
  if (mode !== null) return mode === 'half-up';
  return late.policyDate !== null && c.roundsOn < late.policyDate;
}

/**
 * The figure of a claim: its settled due, else the due a statement bound it to, else its
 * rate figure at the rate in force on its date (no floor), rounded as the rounding mode or the policy date decides
 * for it; null (n/a) with none.
 */
export function figureOf(c: Claim, rates: readonly RateView[], late: Rounding): string | null {
  if (c.settledDue !== null) return c.settledDue;
  if (isBound(c)) return c.statedDue;
  return dueOn(c.amount, rateOn(rates, c.date), roundsHalfUp(c, late));
}

/** Whether the claim keeps a figure whatever the rates: a settled or bound one with a figure. */
export function keepsFigure(c: Claim): boolean {
  return c.settledDue !== null || (isBound(c) && c.statedDue !== null);
}

/** Whether the claim was deferred until a date after the sheet's today, whatever its status: as the row shows it. */
export function deferredUntilAfter(c: Claim, today: string | null): boolean {
  return c.deferredUntil !== null && today !== null && today < c.deferredUntil;
}

/**
 * Whether a claim is deferred: OPEN and deferred until a date after the sheet's today. Until today reaches that
 * date it counts in no total, is not overdue and cannot be settled; from then on it is an OPEN claim like any other.
 */
export function deferred(c: Claim, today: string | null): boolean {
  return c.status === 'OPEN' && c.deferredUntil !== null && today !== null && today < c.deferredUntil;
}

/** Whether a claim's late charge is waived on the date: waived on or before it, so it bears no late charge. */
export function waivedOn(c: Claim, today: string | null): boolean {
  return c.waivedOn !== null && today !== null && c.waivedOn <= today;
}

/** Whether a claim makes its person active: open (or pending) and dated within the 30 days up to and including today. */
export function activeOn(c: Claim, today: string | null): boolean {
  return isOpen(c) && today !== null && plusDays(today, -29) <= c.date && c.date <= today;
}

/**
 * The date after which a claim is overdue: its due-by plus the grace days (those its person's grade in force on its
 * date has in force on that date). Its late charge's 30-day boundaries count from it; the due-by shown is unchanged.
 */
export function lateFrom(c: Claim, grace: number): string {
  return grace > 0 ? plusDays(c.dueBy, grace) : c.dueBy;
}

/** Whether a claim is overdue: open (or pending), not deferred, and its due-by plus its grace is before the sheet's today. */
export function overdue(c: Claim, today: string | null, grace = 0): boolean {
  return isOpen(c) && today !== null && !deferred(c, today) && lateFrom(c, grace) < today;
}

/**
 * Whether a claim is late in review: REVIEW, with a review SLA set, and its review day (the sheet's today when it
 * last went to REVIEW) plus the SLA days is before the sheet's today.
 */
export function reviewLate(c: Claim, today: string | null, sla: number | null): boolean {
  if (c.status !== 'REVIEW' || today === null || sla === null) return false;
  const on = [...c.log].reverse().find((l) => l.status === 'REVIEW')?.on ?? null;
  return on !== null && plusDays(on, sla) < today;
}

/** The grace days of the claim: those its person's grade in force on its date has in force on that date. */
export function graceOf(c: Claim, grades: readonly DatedGrade[], late: Lateness): number {
  return late.grace(gradeOn(grades, c.date), c.date);
}

/** The floor in force on a date. */
export type FloorOn = (date: string) => string;

/**
 * What decides how a claim's live figures round: the rounding mode in force on a date (null for none), else the
 * policy date in force (the sheet's today when the setting is blank).
 */
export interface Rounding {
  policyDate: string | null;
  rounding: (date: string) => 'half-up' | 'half-even' | null;
}

/**
 * What the late charge follows: the sheet's today and the late percent in force on a date; and how a claim's live
 * figures round.
 */
export interface Lateness extends Rounding {
  today: string | null;
  latePct: (date: string) => string;
  /** The grace days the grade has in force on a date; 0 with none. */
  grace: (grade: string | null, date: string) => number;
}

/** The whole days from one date (yyyy-mm-dd) to another. */
function daysBetween(from: string, to: string): number {
  const utc = (d: string) => {
    const [y, m, day] = d.split('-').map(Number);
    return Date.UTC(y, m - 1, day);
  };
  return Math.round((utc(to) - utc(from)) / 86_400_000);
}

/** The date (yyyy-mm-dd) the given whole days after another. */
export function plusDays(date: string, days: number): string {
  const [y, m, d] = date.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}

/**
 * The due a claim shows and totals: a settled claim's frozen due; else its live due, its floored figure (the greater
 * of the floor in force on its date and its figure) plus the late charge worked out on that floored figure: for an
 * overdue claim, for each full 30 days it is overdue, the late percent in force on that boundary date (its due-by
 * plus its grace, plus 30, 60, 90 ... days) of the floored figure plus the charges of the boundaries before it (it compounds), rounded like the claim; a boundary whose percent is 0.00 adds nothing. A claim a statement binds shows the due it
 * bound, charge and floor included. A correction has neither; a waived claim bears no charge.
 */
export function dueOf(c: Claim, rates: readonly RateView[], floor: FloorOn, late: Lateness, grace = 0): string | null {
  const figure = figureOf(c, rates, late);
  if (figure === null || c.settledDue !== null || isBound(c) || isCorrection(c)) return figure;
  const floored = atLeast(figure, floor(c.date));
  if (!overdue(c, late.today, grace) || waivedOn(c, late.today)) return floored;
  const from = lateFrom(c, grace);
  const periods = Math.floor(daysBetween(from, late.today!) / 30);
  const halfUp = roundsHalfUp(c, late);
  let due = floored;
  for (let k = 1; k <= periods; k++) {
    due = sumSkippingNa([due, dueOn(due, late.latePct(plusDays(from, 30 * k)), halfUp)]);
  }
  return due;
}

const CLOSED = 'That claim is due by a date in a closed period, so it cannot change.';
const LOCKED = 'Another user is editing this claim; try again once they are done.';
const LINED = 'This claim has lines, so its amount is the sum of its lines and cannot be typed.';

// The line being typed for a claim, as typed.
interface LineFields {
  label: string;
  qty: string;
  unit: string;
}

/** Whether two amounts with up to two decimals are the same amount. */
function sameAmount(a: string, b: string): boolean {
  return Math.round(Number(a) * 100) === Math.round(Number(b) * 100);
}

export const AMOUNT = /^\d+(\.\d{1,2})?$/;

/** A claim form's amount: a negative one is a correction. */
const SIGNED_AMOUNT = /^-?\d+(\.\d{1,2})?$/;

/**
 * The sum of the counted claims' amounts, leaving out claims that correct another, with two decimals; added in whole
 * cents so nothing drifts.
 */
export function claimedTotal(claims: readonly Claim[]): string {
  const cents = claims.filter(c => counts(c) && !c.corrects).reduce((sum, c) => sum + Math.round(Number(c.amount) * 100), 0);
  return (cents / 100).toFixed(2);
}

// One person's claims, newest first: one form adds a claim or, with a claim's editor open, saves it.
@Component({
  selector: 'app-claims',
  imports: [FigPipe],
  template: `
    <form (submit)="submit($event)" [attr.aria-label]="(editingId() === null ? 'Add claim for ' : 'Edit claim for ') + personName()">
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
      <label [for]="'claim-corrects-' + personId()">Corrects claim no. (for a negative amount)</label>
      <input
        [id]="'claim-corrects-' + personId()"
        data-testid="claim-form-corrects"
        inputmode="numeric"
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
      @if (error(); as err) {
        <p role="alert" data-testid="claim-form-error">{{ err | fig }}</p>
      }
    </form>
    <div role="group" [attr.aria-label]="'Add a claim from a template for ' + personName()">
      <label [for]="'claim-template-' + personId()">Template</label>
      <select
        [id]="'claim-template-' + personId()"
        data-testid="claim-template-select"
        (change)="template.set(value($event)); error.set(null)"
      >
        <option value="" [selected]="template() === ''">Choose a template</option>
        @for (t of templates.list(); track t.id) {
          <option [value]="t.name" [selected]="template() === t.name">{{ t.name }}</option>
        }
      </select>
      <button type="button" data-testid="claim-template-add" (click)="addFromTemplate()">
        Add claim from template<span class="visually-hidden"> for {{ personName() }}</span>
      </button>
    </div>

    <table>
      <caption class="visually-hidden">Claims of {{ personName() }}</caption>
      <thead>
        <tr>
          <th scope="col"><span class="visually-hidden">Select</span></th><th scope="col">No.</th><th scope="col">Amount</th><th scope="col">Date</th><th scope="col">Note</th><th scope="col">Reference</th><th scope="col">Tags</th><th scope="col">Due</th><th scope="col">Due by</th><th scope="col">Overdue</th><th scope="col">Deferred</th><th scope="col">Deferred until</th><th scope="col">Reminded</th><th scope="col">Waived</th><th scope="col">Held</th><th scope="col">Status</th><th scope="col">Review late</th><th scope="col">Batch</th><th scope="col">Opened by</th><th scope="col">Stated</th><th scope="col">Period</th><th scope="col">Late entry</th><th scope="col">Corrects</th><th scope="col">Proofs</th><th scope="col">Receipts</th><th scope="col">Photos</th><th scope="col">Split from</th><th scope="col">Log</th><th scope="col">Comments</th><th scope="col">Lines</th><th scope="col">Timeline</th>
          <th scope="col"><span class="visually-hidden">Actions</span></th>
        </tr>
      </thead>
      <tbody>
        @for (c of claims(); track c.id) {
          <tr [attr.data-testid]="'claim-row-' + c.id">
            <td>
              <input
                type="checkbox"
                data-testid="claim-select"
                [attr.aria-label]="'Select claim of ' + c.amount + ' on ' + c.date"
                [checked]="selected().has(c.id)"
                (change)="selectedChange.emit({ id: c.id, checked: checked($event) })"
              />
            </td>
            <td data-testid="claim-number">{{ (c.number ?? '') | fig }}</td>
            <td data-testid="claim-amount">{{ c.amount | fig }}</td>
            <td data-testid="claim-date">{{ c.date | fig }}</td>
            <td data-testid="claim-note">{{ c.note | fig }}</td>
            <td data-testid="claim-ref">{{ (c.ref ?? '') | fig }}</td>
            <td data-testid="claim-tags">{{ (c.tags.join(', ')) | fig }}</td>
            <td data-testid="claim-due">{{ (dueOf(c, ratesOf(c), settings.floor(), settings.lateness(), graceOn(c)) ?? 'n/a') | fig }}</td>
            <td data-testid="claim-due-by">{{ c.dueBy | fig }}</td>
            <td data-testid="claim-overdue">{{ (overdue(c, session.sheetToday(), graceOn(c)) ? 'yes' : 'no') | fig }}</td>
            <td data-testid="claim-deferred">{{ (deferredUntilAfter(c, session.sheetToday()) ? 'yes' : 'no') | fig }}</td>
            <td data-testid="claim-deferred-until">{{ (c.deferredUntil ?? '') | fig }}</td>
            <td data-testid="claim-reminded">{{ (c.remindedOn ?? '') | fig }}</td>
            <td data-testid="claim-waived">{{ (c.waivedOn !== null ? 'yes' : 'no') | fig }}</td>
            <td data-testid="claim-held">{{ (c.held ? 'yes' : 'no') | fig }}</td>
            <td data-testid="claim-status">{{ c.status | fig }}</td>
            <td data-testid="claim-review-late">{{ (reviewLate(c, session.sheetToday(), settings.reviewSla()) ? 'yes' : 'no') | fig }}</td>
            <td data-testid="claim-batch">{{ (c.batch ?? '') | fig }}</td>
            <td data-testid="claim-opened-by">{{ (c.openedBy ?? '') | fig }}</td>
            <td data-testid="claim-stated">{{ (c.stated ? 'yes' : 'no') | fig }}</td>
            <td data-testid="claim-period">{{ periodName(c) | fig }}</td>
            <td data-testid="claim-late-entry">{{ (c.lateEntry ? 'yes' : 'no') | fig }}</td>
            <td data-testid="claim-corrects">{{ (c.corrects ?? '') | fig }}</td>
            <td data-testid="claim-proofs">{{ c.proofs | fig }}</td>
            <td data-testid="claim-proofs-receipt">{{ c.proofReceipts | fig }}</td>
            <td data-testid="claim-proofs-photo">{{ c.proofPhotos | fig }}</td>
            <td data-testid="claim-split-from">{{ (c.splitFrom ?? '') | fig }}</td>
            <td>
              <ol class="claim-log" [attr.aria-label]="'Status log of claim of ' + c.amount + ' on ' + c.date">
                @for (l of c.log; track $index) {
                  <li data-testid="claim-log-line">{{ l.status }} by {{ l.by }} on {{ l.on ?? '' }} due={{ l.due ?? 'n/a' }}</li>
                }
              </ol>
            </td>
            <td>
              <ol class="claim-comments" [attr.aria-label]="'Comments on claim of ' + c.amount + ' on ' + c.date">
                @for (m of c.comments; track $index) {
                  <li data-testid="claim-comments-line">{{ m.by }} on {{ m.on ?? '' }}: {{ m.text }}</li>
                }
              </ol>
              <label [for]="'claim-comment-text-' + c.id">Comment</label>
              <input
                [id]="'claim-comment-text-' + c.id"
                data-testid="claim-comment-text"
                [value]="commentTexts()[c.id] ?? ''"
                (input)="setCommentText(c.id, value($event))"
              />
              <button type="button" data-testid="claim-comment-add" (click)="comment(c)">
                Add comment<span class="visually-hidden"> to claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
            </td>
            <td>
              <ol class="claim-lines" [attr.aria-label]="'Lines of claim of ' + c.amount + ' on ' + c.date">
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
                inputmode="numeric"
                data-testid="claim-line-qty"
                [value]="lineFields()[c.id]?.qty ?? ''"
                (input)="setLineField(c.id, 'qty', value($event))"
              />
              <label [for]="'claim-line-unit-' + c.id">Unit amount</label>
              <input
                [id]="'claim-line-unit-' + c.id"
                inputmode="decimal"
                data-testid="claim-line-unit"
                [value]="lineFields()[c.id]?.unit ?? ''"
                (input)="setLineField(c.id, 'unit', value($event))"
              />
              <button type="button" data-testid="claim-line-add" (click)="addLine(c)">
                Add line<span class="visually-hidden"> to claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
            </td>
            <td>
              <button type="button" data-testid="claim-timeline" (click)="showTimeline(c)">
                Timeline<span class="visually-hidden"> of claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <ol class="claim-timeline" [attr.aria-label]="'Timeline of claim of ' + c.amount + ' on ' + c.date">
                @for (line of timelines()[c.id] ?? []; track $index) {
                  <li data-testid="claim-timeline-line">{{ line | fig }}</li>
                }
              </ol>
            </td>
            <td>
              <button type="button" data-testid="claim-open" (click)="act(c, 'open')">
                Open<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <button type="button" data-testid="claim-review" (click)="act(c, 'review')">
                Review<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <label [for]="'claim-settle-date-' + c.id">Settle as of</label>
              <input
                [id]="'claim-settle-date-' + c.id"
                type="date"
                data-testid="claim-settle-date"
                [value]="settleDates()[c.id] ?? ''"
                (input)="setSettleDate(c.id, value($event))"
              />
              <button type="button" data-testid="claim-settle" (click)="act(c, 'settle')">
                Settle<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <button type="button" data-testid="claim-resettle" (click)="act(c, 'resettle')">
                Re-settle<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <button type="button" data-testid="claim-approve" (click)="act(c, 'approve')">
                Approve<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <label [for]="'claim-reject-reason-' + c.id">Reason to reject</label>
              <input
                [id]="'claim-reject-reason-' + c.id"
                data-testid="claim-reject-reason"
                [value]="rejectReasons()[c.id] ?? ''"
                (input)="setRejectReason(c.id, value($event))"
              />
              <button type="button" data-testid="claim-reject" (click)="act(c, 'reject')">
                Reject<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <label [for]="'claim-defer-date-' + c.id">Defer until</label>
              <input
                [id]="'claim-defer-date-' + c.id"
                type="date"
                data-testid="claim-defer-date"
                [value]="deferDates()[c.id] ?? ''"
                (input)="setDeferDate(c.id, value($event))"
              />
              <button type="button" data-testid="claim-defer" (click)="defer(c)">
                Defer<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <label [for]="'claim-date-new-' + c.id">New date</label>
              <input
                [id]="'claim-date-new-' + c.id"
                type="date"
                data-testid="claim-date-new"
                [value]="redates()[c.id] ?? ''"
                (input)="setRedate(c.id, value($event))"
              />
              <button type="button" data-testid="claim-redate" (click)="redate(c)">
                Re-date<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <label [for]="'claim-split-amount-' + c.id">Amount to split off</label>
              <input
                [id]="'claim-split-amount-' + c.id"
                inputmode="decimal"
                data-testid="claim-split-amount"
                [value]="splitAmounts()[c.id] ?? ''"
                (input)="setSplitAmount(c.id, value($event))"
              />
              <button type="button" data-testid="claim-split" (click)="split(c)">
                Split<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <label [for]="'claim-settle-part-amount-' + c.id">Amount to settle</label>
              <input
                [id]="'claim-settle-part-amount-' + c.id"
                inputmode="decimal"
                data-testid="claim-settle-part-amount"
                [value]="settlePartAmounts()[c.id] ?? ''"
                (input)="setSettlePartAmount(c.id, value($event))"
              />
              <button type="button" data-testid="claim-settle-part" (click)="settlePart(c)">
                Part-settle<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <button type="button" data-testid="claim-remind" (click)="remind(c)">
                Remind<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <button type="button" data-testid="claim-hold" (click)="hold(c)">
                {{ c.held ? 'Release' : 'Hold' }}<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <button type="button" data-testid="claim-waive" (click)="waive(c)">
                Waive late charge<span class="visually-hidden"> of claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <label [for]="'claim-proof-kind-' + c.id">Proof kind</label>
              <select
                [id]="'claim-proof-kind-' + c.id"
                data-testid="claim-proof-kind"
                (change)="setProofKind(c.id, value($event))"
              >
                <option value="" [selected]="!proofKindChoices()[c.id]">Receipt (default)</option>
                <option value="receipt" [selected]="proofKindChoices()[c.id] === 'receipt'">Receipt</option>
                <option value="photo" [selected]="proofKindChoices()[c.id] === 'photo'">Photo</option>
              </select>
              <button type="button" data-testid="claim-proof" (click)="addProof(c)">
                Add proof<span class="visually-hidden"> to claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              @if (voidReasons.list().length > 0) {
                <label [for]="'claim-void-reason-' + c.id">Reason to void</label>
                <select
                  [id]="'claim-void-reason-' + c.id"
                  data-testid="claim-void-reason"
                  (change)="setVoidReason(c.id, value($event))"
                >
                  <option value="" [selected]="!voidReasonChoices()[c.id]">Choose a reason</option>
                  @for (r of voidReasons.list(); track r.id) {
                    <option [value]="r.reason" [selected]="voidReasonChoices()[c.id] === r.reason">{{ r.reason }}</option>
                  }
                </select>
              }
              <button type="button" data-testid="claim-void" (click)="act(c, 'void')">
                Void<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <button type="button" data-testid="claim-delete" (click)="act(c, 'delete')">
                Delete<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <button type="button" data-testid="claim-restore" (click)="act(c, 'restore')">
                Restore<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <label [for]="'claim-reassign-to-' + c.id">Move to</label>
              <select
                [id]="'claim-reassign-to-' + c.id"
                data-testid="claim-reassign-to"
                (change)="setReassignTo(c.id, value($event))"
              >
                <option value="" [selected]="!reassignTo()[c.id]">Choose a person</option>
                @for (q of people(); track q.id) {
                  @if (q.id !== c.personId) {
                    <option [value]="q.id" [selected]="reassignTo()[c.id] === '' + q.id">{{ q.name }}</option>
                  }
                }
              </select>
              <button type="button" data-testid="claim-reassign" (click)="reassign(c)">
                Move<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <label [for]="'claim-inline-amount-' + c.id">New amount</label>
              <input
                [id]="'claim-inline-amount-' + c.id"
                inputmode="decimal"
                data-testid="claim-inline-amount"
                [value]="inlineAmounts()[c.id] ?? ''"
                (input)="setInlineAmount(c.id, value($event))"
              />
              <button type="button" data-testid="claim-inline-save" (click)="saveInline(c)">
                Save amount<span class="visually-hidden"> of claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              <button type="button" data-testid="claim-edit" (click)="edit(c)">
                Edit<span class="visually-hidden"> claim of {{ c.amount }} on {{ c.date }}</span>
              </button>
              @if (editingId() === c.id) {
                <button type="button" data-testid="claim-cancel" (click)="cancel()">Cancel</button>
              }
              @if (rowErrors()[c.id]; as err) {
                <p role="alert" data-testid="claim-error">{{ err | fig }}</p>
              }
            </td>
          </tr>
        }
      </tbody>
    </table>
  `,
})
export class Claims {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(Session);
  private readonly periods = inject(PeriodStore);
  protected readonly settings = inject(SettingsStore);
  protected readonly voidReasons = inject(VoidReasonStore);
  private readonly rateStore = inject(RateStore);
  protected readonly templates = inject(ClaimTemplateStore);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  readonly claims = input.required<Claim[]>();
  // Every grade recorded for the person; a claim's due uses the rate in force on its date for the grade in force then.
  readonly grades = input<readonly DatedGrade[]>([]);
  // The date the person is retired from; no claim dated after it can be added or saved. Null while not retired.
  readonly retiredFrom = input<string | null>(null);
  // The retirements the person was unretired from; no claim dated after a from and before its to can be added or saved.
  readonly pastRetirements = input<readonly { from: string; to: string }[]>([]);
  // Every person on the sheet, whom a steward can move a claim to.
  readonly people = input<readonly { id: number; name: string }[]>([]);
  // Every claim on the sheet, whose references a claim's own must not repeat.
  readonly sheetClaims = input<readonly Claim[]>([]);
  readonly saved = output<Claim>();
  // The claims selected for a bulk action, by id; a checkbox's change is reported, not kept here.
  readonly selected = input<ReadonlySet<number>>(new Set());
  readonly selectedChange = output<{ id: number; checked: boolean }>();

  protected readonly amount = signal('');
  protected readonly date = signal('');
  protected readonly note = signal('');
  protected readonly corrects = signal('');
  protected readonly ref = signal('');
  protected readonly tags = signal('');
  // The template chosen to add a claim from, by name.
  protected readonly template = signal('');
  protected readonly editingId = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  // A submit in flight: a second submit before it answers is ignored, so it stores one claim.
  private submitting = false;
  // The store's answer to the edit button while it is awaited: whether the claim is held for this user. The editor
  // opens at once; a save waits for it, and a refusal closes the editor.
  private locking: Promise<boolean> | null = null;
  // Each claim's refused status change, by claim id.
  protected readonly rowErrors = signal<Record<number, string>>({});
  // The reason typed to reject each pending claim, by claim id.
  protected readonly rejectReasons = signal<Record<number, string>>({});
  // The date typed to settle each claim as of, by claim id; blank settles it as it stands now.
  protected readonly settleDates = signal<Record<number, string>>({});
  // The void reason chosen for each claim, by claim id.
  protected readonly voidReasonChoices = signal<Record<number, string>>({});
  // The proof kind chosen for each claim's next proof, by claim id; none chosen (a receipt) when missing.
  protected readonly proofKindChoices = signal<Record<number, string>>({});
  // The date typed to defer each claim until, by claim id.
  protected readonly deferDates = signal<Record<number, string>>({});
  // The new date typed for each claim, by claim id.
  protected readonly redates = signal<Record<number, string>>({});
  // The amount typed to split off each claim, by claim id.
  protected readonly splitAmounts = signal<Record<number, string>>({});
  // The amount typed to part-settle each claim, by claim id.
  protected readonly settlePartAmounts = signal<Record<number, string>>({});
  // The person (by id) chosen to move each claim to, by claim id.
  protected readonly reassignTo = signal<Record<number, string>>({});
  // The comment typed for each claim, by claim id.
  protected readonly commentTexts = signal<Record<number, string>>({});
  // The timeline shown for each claim, by claim id: its audit lines, newest first.
  protected readonly timelines = signal<Record<number, readonly string[]>>({});
  // Each timeline press is numbered, so only the latest one's answer shows.
  private timelinePress = 0;
  // The line typed for each claim, by claim id.
  protected readonly lineFields = signal<Record<number, LineFields>>({});
  // The amount typed in each draft's own row, by claim id.
  protected readonly inlineAmounts = signal<Record<number, string>>({});

  protected readonly dueOf = dueOf;

  constructor() {
    // The store says which claim this user's editor holds: a re-read sheet shows it open again, and an editor whose
    // claim it no longer holds (released when the sheet's today changed) is closed.
    effect(() => {
      const user = this.session.user();
      const claims = this.claims();
      untracked(() => {
        const editing = this.editingId();
        const mine = claims.find((c) => c.lockedBy !== null && c.lockedBy === user);
        if (editing !== null && !this.locking && claims.some((c) => c.id === editing && c.lockedBy !== user)) {
          this.reset();
        }
        if (this.editingId() === null && mine) this.openEditor(mine);
      });
    });
    // A today change releases every claim, so an open editor is closed with it.
    let today: string | null | undefined;
    effect(() => {
      const now = this.session.sheetToday();
      if (today !== undefined && now !== today) untracked(() => this.reset());
      today = now;
    });
  }

  /** Every rate that applies to the claim for its person's grade in force on its date. */
  protected ratesOf(c: Claim): readonly RateView[] {
    return this.rateStore.byClaim()(c).get(gradeOn(this.grades(), c.date)) ?? [];
  }
  /** The claim's grace days: those its person's grade in force on its date has in force on that date. */
  protected graceOn(c: Claim): number {
    return graceOf(c, this.grades(), this.settings.lateness());
  }
  protected readonly overdue = overdue;
  protected readonly reviewLate = reviewLate;
  protected readonly deferred = deferred;
  protected readonly deferredUntilAfter = deferredUntilAfter;

  protected value(e: Event): string {
    return (e.target as HTMLInputElement | HTMLSelectElement).value;
  }

  protected checked(e: Event): boolean {
    return (e.target as HTMLInputElement).checked;
  }

  /** The name of the period the claim belongs to, the one its due-by falls in; empty when none does. */
  protected periodName(c: Claim): string {
    return periodOn(this.periods.list(), c.dueBy)?.name ?? '';
  }

  /** Opens the claim's editor, which holds the claim for this user until it is saved or cancelled. */
  protected async edit(c: Claim): Promise<void> {
    this.clearRowError(c.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const user = this.session.user();
    // a viewer is refused by the store, which knows whether a delegation lets them act
    if (this.session.sheetArchived()) {
      this.setRowError(c.id, 'Your role may not edit a claim on this sheet.');
      return;
    }
    if (c.status !== 'DRAFT') {
      // an open claim is changed by voiding it and adding a new one
      this.setRowError(c.id, `A ${c.status} claim cannot be edited; only a draft can. Void it and add a new one.`);
      return;
    }
    if (inClosedPeriod(this.periods.list(), c.dueBy)) {
      this.setRowError(c.id, CLOSED);
      return;
    }
    if (c.lockedBy !== null && c.lockedBy !== user) {
      this.setRowError(c.id, LOCKED);
      return;
    }
    const was = this.editingId();
    this.openEditor(c);
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/lock`;
    const locking = firstValueFrom(this.http.post<Claim>(url, null)).then(
      (held) => {
        // the store released the claim this user's editor held before
        const prev = was !== null && was !== c.id ? this.claims().find((x) => x.id === was) : undefined;
        if (prev) this.saved.emit({ ...prev, lockedBy: null });
        this.saved.emit(held);
        return true;
      },
      (e: unknown) => {
        this.refuseEdit(c, e);
        return false;
      },
    );
    this.locking = locking;
    await locking;
    if (this.locking === locking) this.locking = null;
  }

  protected setInlineAmount(id: number, amount: string): void {
    this.inlineAmounts.update((a) => ({ ...a, [id]: amount }));
    this.clearRowError(id);
  }

  /** Saves the amount typed in the claim's row, keeping its other fields: the claim form's rules apply. */
  protected async saveInline(c: Claim): Promise<void> {
    this.clearRowError(c.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const user = this.session.user();
    // a viewer is refused by the store, which knows whether a delegation lets them act
    if (this.session.sheetArchived()) {
      this.setRowError(c.id, 'Your role may not edit a claim on this sheet.');
      return;
    }
    const amount = (this.inlineAmounts()[c.id] ?? '').trim();
    if (!SIGNED_AMOUNT.test(amount)) {
      this.setRowError(c.id, 'Enter an amount with up to two decimals.');
      return;
    }
    const correction = amount.startsWith('-');
    if (correction && !c.corrects) {
      this.setRowError(c.id, 'A negative amount is a correction: enter the number of the settled claim it corrects.');
      return;
    }
    if (c.status !== 'DRAFT') {
      this.setRowError(c.id, `A ${c.status} claim cannot be edited; only a draft can. Void it and add a new one.`);
      return;
    }
    if (c.lines.length > 0 && !sameAmount(amount, c.amount)) {
      this.setRowError(c.id, LINED);
      return;
    }
    if (inClosedPeriod(this.periods.list(), c.dueBy)) {
      this.setRowError(c.id, CLOSED);
      return;
    }
    if (c.lockedBy !== null && c.lockedBy !== user) {
      this.setRowError(c.id, LOCKED);
      return;
    }
    const retiredFrom = this.retiredFrom();
    if (retiredFrom !== null && c.date > retiredFrom) {
      this.setRowError(c.id, `${this.personName()} is retired from ${retiredFrom}, so a claim cannot be dated after it.`);
      return;
    }
    const past = this.pastRetirementCovering(c.date);
    if (past) {
      this.setRowError(c.id, `${this.personName()} was retired from ${past.from} to ${past.to}, so a claim cannot be dated within it.`);
      return;
    }
    const form = { amount, date: c.date, note: c.note, corrects: correction ? c.corrects : null, ref: c.ref, tags: c.tags.join(',') };
    try {
      const saved = await firstValueFrom(
        this.http.put<Claim>(`/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}`, form),
      );
      this.saved.emit(saved);
      this.inlineAmounts.update(({ [c.id]: _, ...rest }) => rest);
    } catch (e) {
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      this.setRowError(
        c.id,
        status === 403
          ? 'Your role may not edit a claim on this sheet.'
          : status === 423
            ? LOCKED
            : `This ${c.status} claim cannot be edited, or its period is closed.`,
      );
    }
  }

  private refuseEdit(c: Claim, e: unknown): void {
    if (this.editingId() === c.id) this.reset();
    const status = e instanceof HttpErrorResponse ? e.status : 0;
    const err =
      status === 403
        ? 'Your role may not edit a claim on this sheet.'
        : status === 423
          ? LOCKED
          : `This ${c.status} claim cannot be edited, or its period is closed.`;
    this.setRowError(c.id, err);
  }

  private openEditor(c: Claim): void {
    this.editingId.set(c.id);
    this.amount.set(c.amount);
    this.date.set(c.date);
    this.note.set(c.note);
    this.corrects.set(c.corrects ?? '');
    this.ref.set(c.ref ?? '');
    this.tags.set(c.tags.join(', '));
    this.error.set(null);
  }

  protected setSettleDate(id: number, date: string): void {
    this.settleDates.update((d) => ({ ...d, [id]: date }));
    this.clearRowError(id);
  }

  protected setRejectReason(id: number, reason: string): void {
    this.rejectReasons.update((r) => ({ ...r, [id]: reason }));
    this.clearRowError(id);
  }

  protected setProofKind(id: number, kind: string): void {
    this.proofKindChoices.update((k) => ({ ...k, [id]: kind }));
    this.clearRowError(id);
  }

  protected setVoidReason(id: number, reason: string): void {
    this.voidReasonChoices.update((r) => ({ ...r, [id]: reason }));
    this.clearRowError(id);
  }

  /**
   * The period the claim belongs to when it has a cap the person's OPEN or PENDING claims in it already reach;
   * undefined otherwise.
   */
  private periodAtCap(c: Claim): PeriodView | undefined {
    const p = periodOn(this.periods.list(), c.dueBy);
    if (!p || p.cap === null) return undefined;
    const held = this.claims().filter(
      (o) => o.id !== c.id && (o.status === 'OPEN' || o.status === 'PENDING') && p.from <= o.dueBy && o.dueBy <= p.to,
    ).length;
    return held >= p.cap ? p : undefined;
  }

  protected async act(c: Claim, action: ClaimAction): Promise<void> {
    this.clearRowError(c.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/${action}`;
    if (inClosedPeriod(this.periods.list(), c.dueBy)) {
      this.setRowError(c.id, CLOSED);
      return;
    }
    const capped = action === 'open' && c.status === 'DRAFT' ? this.periodAtCap(c) : undefined;
    if (capped) {
      this.setRowError(c.id, `${this.personName()} already has ${capped.cap} open claims in period ${capped.name}.`);
      return;
    }
    const reason = (this.rejectReasons()[c.id] ?? '').trim();
    if (action === 'reject' && !reason) {
      this.setRowError(c.id, 'Enter a reason to reject this claim.');
      return;
    }
    const settleOn = (this.settleDates()[c.id] ?? '').trim();
    const today = this.session.sheetToday();
    if (
      action === 'settle' &&
      settleOn !== '' &&
      (!/^\d{4}-\d{2}-\d{2}$/.test(settleOn) || (today !== null && settleOn > today))
    ) {
      this.setRowError(c.id, `Enter a date on or before ${today ?? 'today'} to settle this claim as of.`);
      return;
    }
    const voidReason = this.voidReasonChoices()[c.id] ?? '';
    const listed = this.voidReasons.list();
    if (action === 'void' && listed.length > 0 && !listed.some((r) => r.reason === voidReason)) {
      this.setRowError(c.id, 'Choose a reason from the list to void this claim.');
      return;
    }
    try {
      const body =
        action === 'reject'
          ? { reason }
          : action === 'void'
            ? { reason: voidReason || null }
            : action === 'settle'
              ? { on: settleOn || null }
              : null;
      this.saved.emit(await firstValueFrom(this.http.post<Claim>(url, body)));
      if (action === 'settle') this.settleDates.update(({ [c.id]: _, ...rest }) => rest);
      if (action === 'reject') this.rejectReasons.update(({ [c.id]: _, ...rest }) => rest);
      if (action === 'void') this.voidReasonChoices.update(({ [c.id]: _, ...rest }) => rest);
    } catch (e) {
      const opener = c.openedBy !== null && c.openedBy === this.session.user();
      const editor = c.editedBy !== null && c.editedBy === this.session.user();
      const forbidden = e instanceof HttpErrorResponse && e.status === 403;
      const err =
        action === 'delete' && c.status !== 'DRAFT'
          ? `A ${c.status} claim cannot be deleted; only a draft can.`
          : action === 'restore' && c.status === 'DELETED'
          ? 'A deleted claim can only be restored on the day it was deleted.'
          : action === 'restore'
          ? `A ${c.status} claim cannot be restored; only a deleted one can.`
          : action === 'settle' && settleOn !== '' && e instanceof HttpErrorResponse && e.status === 422
          ? `This claim did not exist on ${settleOn}, so it cannot be settled as of then.`
          : action === 'settle' && deferred(c, this.session.sheetToday())
          ? `This claim is deferred until ${c.deferredUntil}, so it cannot be settled yet.`
          : action === 'void' && e instanceof HttpErrorResponse && e.status === 422
          ? 'Choose a reason from the list to void this claim.'
          : action === 'void' && c.status === 'SETTLED'
          ? 'Only a steward can void a settled claim, and only within 7 days of its settlement.'
          : (action === 'open' || action === 'settle' || action === 'void') && forbidden
          ? `Your role may not ${action} a claim on this sheet.`
          : action === 'settle' && opener
          ? 'You opened this claim, so another user must settle it.'
          : action === 'settle' && editor
          ? 'You last edited this claim, so another user must settle it.'
          : action === 'review' && forbidden
          ? 'Only a steward can review a claim.'
          : action === 'review' && c.status !== 'REVIEW'
          ? `A ${c.status} claim cannot be reviewed; only one in REVIEW can.`
          : (action === 'approve' || action === 'reject') && forbidden
            ? 'Only a steward or the person\'s approver can approve or reject a pending claim.'
            : action === 'resettle' && forbidden
              ? 'Only a steward can re-settle a claim.'
              : action === 'approve' && opener
                ? 'You opened this claim, so another steward must approve it.'
                : action === 'approve' && editor
                ? 'You last edited this claim, so another steward must approve it.'
                : ((action === 'settle' && c.status === 'OPEN') || (action === 'approve' && c.status === 'PENDING')) &&
                    c.proofs === 0
                  ? 'This claim\'s due is above the proof threshold, so it needs a proof first.'
                  : (action === 'settle' && c.status === 'OPEN') || (action === 'approve' && c.status === 'PENDING')
                    ? 'This claim cannot be settled or approved yet: its due may be above the proof threshold and it needs a proof of each of the sheet\'s proof kinds first.'
                  : action === 'open' && c.status === 'DRAFT' && e instanceof HttpErrorResponse && e.status === 409
                    ? 'This claim cannot be opened: its person may be at the open-claim cap of its period, or it needs a note first.'
                    : `A ${c.status} claim cannot be changed that way, or its period is closed.`;
      this.setRowError(c.id, err);
    }
  }

  /**
   * Raises the claim's proofs count by one, of the kind chosen (a receipt when none is); a SETTLED, VOID or DELETED
   * claim, or one in a closed period, takes none.
   */
  protected async addProof(c: Claim): Promise<void> {
    this.clearRowError(c.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    if (c.status === 'SETTLED' || c.status === 'VOID' || c.status === 'DELETED') {
      this.setRowError(c.id, `A ${c.status} claim cannot take a proof.`);
      return;
    }
    if (inClosedPeriod(this.periods.list(), c.dueBy)) {
      this.setRowError(c.id, CLOSED);
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/proof`;
    const kind = this.proofKindChoices()[c.id] || null;
    try {
      this.saved.emit(await firstValueFrom(this.http.post<Claim>(url, { kind })));
      this.proofKindChoices.update(({ [c.id]: _, ...rest }) => rest);
    } catch {
      this.setRowError(c.id, `This ${c.status} claim cannot take a proof.`);
    }
  }

  /** Writes a reminder for an OPEN or PENDING claim, once per sheet's today. */
  protected async remind(c: Claim): Promise<void> {
    this.clearRowError(c.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    if (!isOpen(c)) {
      this.setRowError(c.id, `A ${c.status} claim cannot be reminded; only an OPEN or PENDING one can.`);
      return;
    }
    const today = this.session.sheetToday();
    if (today !== null && c.remindedOn === today) {
      this.setRowError(c.id, `This claim was already reminded on ${today}.`);
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/remind`;
    try {
      this.saved.emit(await firstValueFrom(this.http.post<Claim>(url, null)));
    } catch {
      this.setRowError(c.id, `This ${c.status} claim cannot be reminded.`);
    }
  }

  /** A steward waives an OPEN or PENDING claim's late charge, once. */
  protected async waive(c: Claim): Promise<void> {
    this.clearRowError(c.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    if (!isOpen(c)) {
      this.setRowError(c.id, `A ${c.status} claim's late charge cannot be waived; only an OPEN or PENDING one's can.`);
      return;
    }
    if (c.waivedOn !== null) {
      this.setRowError(c.id, `This claim's late charge was already waived on ${c.waivedOn}.`);
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/waive`;
    try {
      this.saved.emit(await firstValueFrom(this.http.post<Claim>(url, null)));
    } catch {
      this.setRowError(c.id, 'Only a steward can waive a late charge.');
    }
  }

  /** Holds an OPEN or PENDING claim, or releases a held one; any other status is refused. */
  protected async hold(c: Claim): Promise<void> {
    this.clearRowError(c.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    if (!isOpen(c)) {
      this.setRowError(c.id, `A ${c.status} claim cannot be held or released; only an OPEN or PENDING one can.`);
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/hold`;
    try {
      this.saved.emit(await firstValueFrom(this.http.post<Claim>(url, null)));
    } catch {
      this.setRowError(c.id, 'The claim could not be held or released.');
    }
  }

  protected setCommentText(id: number, text: string): void {
    this.commentTexts.update((t) => ({ ...t, [id]: text }));
    this.clearRowError(id);
  }

  /** Adds the typed comment to the claim, by this user on the sheet's today. An empty comment is refused. */
  protected async comment(c: Claim): Promise<void> {
    this.clearRowError(c.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const text = (this.commentTexts()[c.id] ?? '').trim();
    if (text === '') {
      this.setRowError(c.id, 'Enter a comment to add to this claim.');
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/comments`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, { text }));
      this.commentTexts.update(({ [c.id]: _, ...rest }) => rest);
      this.saved.emit(saved);
    } catch {
      this.setRowError(c.id, 'This comment could not be added to the claim.');
    }
  }

  /** Shows the claim's timeline: the audit lines about it, newest first, as the file holds them. */
  protected async showTimeline(c: Claim): Promise<void> {
    this.clearRowError(c.id);
    this.timelines.update(({ [c.id]: _, ...rest }) => rest);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const press = ++this.timelinePress;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/timeline`;
    try {
      const lines = await firstValueFrom(this.http.get<string[]>(url));
      if (press === this.timelinePress) this.timelines.update((t) => ({ ...t, [c.id]: lines }));
    } catch {
      if (press === this.timelinePress) this.setRowError(c.id, 'The timeline of this claim could not be read.');
    }
  }

  protected setLineField(id: number, field: keyof LineFields, text: string): void {
    this.lineFields.update((l) => ({ ...l, [id]: { ...(l[id] ?? { label: '', qty: '', unit: '' }), [field]: text } }));
    this.clearRowError(id);
  }

  /**
   * Adds the typed line to a DRAFT claim, whose amount becomes the sum of its lines; adding a line edits the claim.
   * Refused for a blank label, a quantity that is not a whole number of at least 1, a unit amount without up to two
   * decimals, a claim that is not a draft, one in a closed period, and one another user is editing.
   */
  protected async addLine(c: Claim): Promise<void> {
    this.clearRowError(c.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const typed = this.lineFields()[c.id] ?? { label: '', qty: '', unit: '' };
    const label = typed.label.trim();
    const qty = typed.qty.trim();
    const unit = typed.unit.trim();
    if (label === '') {
      this.setRowError(c.id, 'Enter a label for the line.');
      return;
    }
    if (!/^\d+$/.test(qty) || Number(qty) < 1) {
      this.setRowError(c.id, 'Enter a whole quantity of at least 1.');
      return;
    }
    if (!AMOUNT.test(unit)) {
      this.setRowError(c.id, 'Enter a unit amount with up to two decimals.');
      return;
    }
    if (c.status !== 'DRAFT') {
      this.setRowError(c.id, `A ${c.status} claim cannot take a line; only a draft can.`);
      return;
    }
    if (inClosedPeriod(this.periods.list(), c.dueBy)) {
      this.setRowError(c.id, CLOSED);
      return;
    }
    if (c.lockedBy !== null && c.lockedBy !== this.session.user()) {
      this.setRowError(c.id, LOCKED);
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/lines`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, { label, qty: Number(qty), unit }));
      this.lineFields.update(({ [c.id]: _, ...rest }) => rest);
      this.saved.emit(saved);
    } catch (e) {
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      this.setRowError(
        c.id,
        status === 403
          ? 'Your role may not edit a claim on this sheet.'
          : status === 423
            ? LOCKED
            : `This ${c.status} claim cannot take a line, or its period is closed.`,
      );
    }
  }

  protected setDeferDate(id: number, date: string): void {
    this.deferDates.update((d) => ({ ...d, [id]: date }));
    this.clearRowError(id);
  }

  /** Defers an OPEN claim until the typed date, which must be after the sheet's today. */
  protected async defer(c: Claim): Promise<void> {
    this.clearRowError(c.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const until = (this.deferDates()[c.id] ?? '').trim();
    const today = this.session.sheetToday();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(until) || (today !== null && until <= today)) {
      this.setRowError(c.id, `Enter a date after ${today ?? 'today'} to defer this claim until.`);
      return;
    }
    if (c.status !== 'OPEN') {
      this.setRowError(c.id, `A ${c.status} claim cannot be deferred; only an OPEN one can.`);
      return;
    }
    if (inClosedPeriod(this.periods.list(), c.dueBy)) {
      this.setRowError(c.id, CLOSED);
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/defer`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, { until }));
      this.deferDates.update(({ [c.id]: _, ...rest }) => rest);
      this.saved.emit(saved);
    } catch {
      this.setRowError(c.id, `This ${c.status} claim cannot be deferred until ${until}.`);
    }
  }

  protected setRedate(id: number, date: string): void {
    this.redates.update((d) => ({ ...d, [id]: date }));
    this.clearRowError(id);
  }

  /**
   * A steward re-dates an OPEN or PENDING claim to the typed date; its due-by, and so its period, stay as fixed.
   * Refused for a claim whose due-by falls in a closed period and for a date after the person's retirement.
   */
  protected async redate(c: Claim): Promise<void> {
    this.clearRowError(c.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const date = (this.redates()[c.id] ?? '').trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.setRowError(c.id, 'Enter the new date of this claim.');
      return;
    }
    if (c.status !== 'OPEN' && c.status !== 'PENDING') {
      this.setRowError(c.id, `A ${c.status} claim cannot be re-dated; only an OPEN or PENDING one can.`);
      return;
    }
    if (inClosedPeriod(this.periods.list(), c.dueBy)) {
      this.setRowError(c.id, CLOSED);
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/redate`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, { date }));
      this.redates.update(({ [c.id]: _, ...rest }) => rest);
      this.saved.emit(saved);
    } catch {
      this.setRowError(c.id, `This ${c.status} claim cannot be re-dated to ${date}.`);
    }
  }

  protected setSplitAmount(id: number, amount: string): void {
    this.splitAmounts.update((a) => ({ ...a, [id]: amount }));
    this.clearRowError(id);
  }

  /**
   * Splits the typed amount (more than 0.00 and less than the claim's) off an OPEN claim that is not deferred: the
   * claim keeps the rest, and a new OPEN claim with the amount is listed as its person's newest.
   */
  protected async split(c: Claim): Promise<void> {
    this.clearRowError(c.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const amount = (this.splitAmounts()[c.id] ?? '').trim();
    if (!AMOUNT.test(amount) || Number(amount) <= 0 || Number(amount) >= Number(c.amount)) {
      this.setRowError(c.id, `Enter an amount more than 0.00 and less than ${c.amount} to split off this claim.`);
      return;
    }
    if (c.status !== 'OPEN' || deferred(c, this.session.sheetToday())) {
      this.setRowError(c.id, `Only an OPEN claim that is not deferred can be split.`);
      return;
    }
    if (inClosedPeriod(this.periods.list(), c.dueBy)) {
      this.setRowError(c.id, CLOSED);
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/split`;
    try {
      const result = await firstValueFrom(this.http.post<{ original: Claim; split: Claim }>(url, { amount }));
      this.splitAmounts.update(({ [c.id]: _, ...rest }) => rest);
      this.saved.emit(result.original);
      this.saved.emit(result.split);
    } catch (e) {
      const forbidden = e instanceof HttpErrorResponse && e.status === 403;
      this.setRowError(
        c.id,
        forbidden ? 'Your role may not open a claim, so it may not split one.' : `This ${c.status} claim cannot be split.`,
      );
    }
  }

  protected setSettlePartAmount(id: number, amount: string): void {
    this.settlePartAmounts.update((a) => ({ ...a, [id]: amount }));
    this.clearRowError(id);
  }

  /**
   * Splits the typed amount off an OPEN claim as the split button does, and settles the new claim at once as the
   * settle button does (PENDING above the clerk limit); a refused split or settle refuses the whole.
   */
  protected async settlePart(c: Claim): Promise<void> {
    this.clearRowError(c.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const amount = (this.settlePartAmounts()[c.id] ?? '').trim();
    if (!AMOUNT.test(amount) || Number(amount) <= 0 || Number(amount) >= Number(c.amount)) {
      this.setRowError(c.id, `Enter an amount more than 0.00 and less than ${c.amount} to settle off this claim.`);
      return;
    }
    if (c.status !== 'OPEN' || deferred(c, this.session.sheetToday())) {
      this.setRowError(c.id, `Only an OPEN claim that is not deferred can be part-settled.`);
      return;
    }
    if (inClosedPeriod(this.periods.list(), c.dueBy)) {
      this.setRowError(c.id, CLOSED);
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/settle-part`;
    try {
      const result = await firstValueFrom(this.http.post<{ original: Claim; settled: Claim }>(url, { amount }));
      this.settlePartAmounts.update(({ [c.id]: _, ...rest }) => rest);
      this.saved.emit(result.original);
      this.saved.emit(result.settled);
    } catch (e) {
      const forbidden = e instanceof HttpErrorResponse && e.status === 403;
      this.setRowError(
        c.id,
        forbidden ? 'Your role may not split and settle this claim.' : `This ${c.status} claim cannot be part-settled.`,
      );
    }
  }

  protected setReassignTo(id: number, personId: string): void {
    this.reassignTo.update((r) => ({ ...r, [id]: personId }));
    this.clearRowError(id);
  }

  /** A user whose role may reassign (a steward by the standing rule) moves the claim to the chosen person; a settled or voided claim cannot move. */
  protected async reassign(c: Claim): Promise<void> {
    this.clearRowError(c.id);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const to = this.reassignTo()[c.id] ?? '';
    if (!to) {
      this.setRowError(c.id, 'Choose the person to move this claim to.');
      return;
    }
    if (c.status === 'SETTLED' || c.status === 'VOID' || c.status === 'DELETED') {
      this.setRowError(c.id, `A ${c.status} claim cannot move.`);
      return;
    }
    if (inClosedPeriod(this.periods.list(), c.dueBy)) {
      this.setRowError(c.id, CLOSED);
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${c.id}/reassign`;
    try {
      const moved = await firstValueFrom(this.http.post<Claim>(url, { personId: Number(to) }));
      this.reassignTo.update(({ [c.id]: _, ...rest }) => rest);
      this.saved.emit(moved);
    } catch (e) {
      const forbidden = e instanceof HttpErrorResponse && e.status === 403;
      this.setRowError(
        c.id,
        forbidden ? 'Your role may not move a claim on this sheet.' : `This ${c.status} claim cannot be moved to that person.`,
      );
    }
  }

  // The past retirement a claim dated on the date falls within: after its from and before its to; undefined if none.
  private pastRetirementCovering(date: string): { from: string; to: string } | undefined {
    return this.pastRetirements().find((r) => date > r.from && date < r.to);
  }

  private setRowError(id: number, err: string): void {
    this.rowErrors.update((e) => ({ ...e, [id]: err }));
  }

  private clearRowError(id: number): void {
    this.rowErrors.update(({ [id]: _, ...rest }) => rest);
  }

  /** Cancels the open editor, releasing the claim it held. */
  protected async cancel(): Promise<void> {
    const editing = this.editingId();
    this.reset();
    const sheet = this.session.sheetId();
    if (editing === null || !sheet) return;
    try {
      this.saved.emit(await firstValueFrom(this.http.post<Claim>(`/api/sheets/${encodeURIComponent(sheet)}/claims/${editing}/unlock`, null)));
    } catch {
      // the editor is closed either way; a hold the store keeps is released when the sheet's today changes
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

  /**
   * Adds a draft claim from the chosen template: the store takes its amount and note as they are now and dates it as
   * a claim with no date, by every rule of the claim form.
   */
  protected async addFromTemplate(): Promise<void> {
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const name = this.template();
    if (!this.templates.list().some((t) => t.name === name)) {
      this.error.set('Choose a template to add a claim from.');
      return;
    }
    // the store dates it and judges the period and the person's retirement, as for a claim with no date
    this.submitting = true;
    try {
      const url = `/api/sheets/${encodeURIComponent(sheet)}/people/${this.personId()}/claims/from-template`;
      this.saved.emit(await firstValueFrom(this.http.post<Claim>(url, { template: name })));
    } catch (e) {
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      this.error.set(
        status === 409 ? CLOSED : status === 403 ? 'Your role may not add a claim on this sheet.' : 'Could not add a claim from that template.',
      );
    } finally {
      this.submitting = false;
    }
  }

  protected async submit(e: Event): Promise<void> {
    e.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    // submitting the form is the next action on the person's claims: an error left on one of their rows goes
    this.rowErrors.set({});
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const amount = this.amount().trim();
    if (!SIGNED_AMOUNT.test(amount)) {
      this.error.set('Enter an amount with up to two decimals.');
      return;
    }
    const corrects = this.corrects().trim();
    const correction = amount.startsWith('-');
    if (correction && !/^([1-9]\d*|.+-[1-9]\d*)$/.test(corrects)) {
      this.error.set('A negative amount is a correction: enter the number of the settled claim it corrects.');
      return;
    }
    const date = this.date().trim();
    if (date && !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.error.set('Enter a date as yyyy-mm-dd.');
      return;
    }
    if (this.locking) {
      // the editor is saved only once the store holds its claim for this user
      this.submitting = true;
      const held = await this.locking.finally(() => (this.submitting = false));
      if (!held) return;
    }
    const editing = this.editingId();
    const was = this.claims().find((c) => c.id === editing);
    // a claim's period is the one its due-by falls in: an edit leaves it as fixed, and the store judges a new claim's
    if (was && inClosedPeriod(this.periods.list(), was.dueBy)) {
      this.error.set(CLOSED);
      return;
    }
    if (was && was.lines.length > 0 && !sameAmount(amount, was.amount)) {
      this.error.set(LINED);
      return;
    }
    const retiredFrom = this.retiredFrom();
    // a new claim with no date is dated on the sheet's entry date, when set, as the store dates it
    const on = date || ((was ? null : this.settings.entryDate()) ?? this.session.sheetToday() ?? '');
    if (retiredFrom !== null && on > retiredFrom) {
      this.error.set(`${this.personName()} is retired from ${retiredFrom}, so a claim cannot be dated after it.`);
      return;
    }
    const past = this.pastRetirementCovering(on);
    if (past) {
      this.error.set(`${this.personName()} was retired from ${past.from} to ${past.to}, so a claim cannot be dated within it.`);
      return;
    }
    const ref = this.ref().trim();
    const refKey = ref.toLowerCase();
    if (ref && this.sheetClaims().some((c) => c.id !== editing && c.ref?.toLowerCase() === refKey)) {
      this.error.set(`The reference ${ref} is already used on this sheet.`);
      return;
    }
    const form = { amount, date: date || null, note: this.note().trim(), corrects: correction ? corrects : null, ref, tags: this.tags() };
    const base = `/api/sheets/${encodeURIComponent(sheet)}`;
    this.submitting = true;
    try {
      const claim =
        editing === null
          ? await firstValueFrom(this.http.post<Claim>(`${base}/people/${this.personId()}/claims`, form))
          : await firstValueFrom(this.http.put<Claim>(`${base}/claims/${editing}`, form));
      this.saved.emit(claim);
      this.reset();
    } catch (e) {
      const conflict = e instanceof HttpErrorResponse && e.status === 409;
      this.error.set(
        correction
          ? `Claim no. ${corrects} is not a settled claim of ${this.personName()}, so it cannot be corrected.`
          : conflict && ref
            ? `The reference ${ref} is already used on this sheet, or the claim's period is closed.`
            : editing === null ? (conflict ? CLOSED : 'Could not add that claim.') : 'Could not save that claim.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
