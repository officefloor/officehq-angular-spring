import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, linkedSignal, output, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { ClaimTemplates } from '../../core/claim-templates';
import { VoidReasons } from '../../core/void-reasons';
import { Period, inClosedPeriod, periodOn } from '../periods/periods';
import { DatedGrade, DatedValue, Lateness, Rate, chargedDueCents, dueText, graceOn, gradeOn } from '../rates/rates';
import { FigurePipe } from '../../core/figure';

// A person's claims, newest first, and the one form under their row that both adds a claim and
// edits an existing one (opening a claim's editor fills the form with its values). Each claim
// shows its status and the buttons that move it on; a move its status does not allow is refused
// with an error on the claim, as is the user who opened or last edited a claim settling it (four eyes). A claim
// belongs to the period its due-by falls in (as fixed when added; for one being added, its date
// plus the terms it would get). A claim whose due-by is inside a closed period cannot be added,
// edited or moved on: each is refused with an error; one dated in a closed period but due outside
// it can.
// Only a DRAFT can be edited; editing any other claim is refused with an error on the claim (to
// change an open claim, void it and add a new one).
// Opening a claim's editor locks the claim for every other user until the editor is saved or
// cancelled (the cancel button on the claim) or the sheet's today changes: another user's edit
// button on it is refused with an error on the claim. A refused edit button takes no lock. The lock
// is kept in the store, so the editor is open again when its user re-loads the sheet.
// A steward may void a SETTLED claim within 7 days of its settlement by the sheet's today: it keeps
// its number and shows its frozen due, which leaves every total. Anyone else, or a later day, is
// refused with an error on the claim.
// Each claim shows its due-by, fixed when it was added (its date plus the terms of its person's grade
// in force on its date, or the sheet's terms when that grade has none), and whether it is overdue:
// OPEN with its due-by before the sheet's today. A grade may have grace days: a claim whose person's
// grade on its date has some on its date is overdue only once its due-by plus them is before the
// sheet's today (its due-by shown is unchanged). An overdue claim's due gains the sheet's late
// percent of its figure for every full 30 days it is overdue, counted from its due-by plus its grace.
// A claim with a negative amount is a correction: it must name, by number, a SETTLED claim of the
// same person that it corrects, else it is refused. Its due has no floor and no late charge, and
// it shows the number it corrects.
// A claim may carry an external reference (trimmed; blank is none), shown on the claim: one already
// used on the sheet, compared without regard to case, is refused with an error on the form.
// A claim may carry tags, typed comma separated: each trimmed and lower-cased, repeats dropped,
// shown sorted. A claim split off another carries the original's tags.
// A clerk settling a claim whose due is above the clerk limit makes it PENDING. A PENDING claim
// counts like an OPEN one, and a statement binds it like any other claim that counts. A steward
// approves it (SETTLED, its due fixed then) or rejects it with a reason (back to OPEN, still bound
// by the last statement); the user who opened or last edited it cannot approve it.
// Who may open, settle, void or move a claim is the sheet's permission matrix (see the settings).
// By default a steward can move a DRAFT, OPEN or PENDING claim to another person: it keeps its date, takes
// the next number (a DRAFT, having none, gets none), loses any figure a statement bound it to, and is listed first under its new person.
// An OPEN claim can be deferred to a date after the sheet's today (refused for a claim that is not
// OPEN, a date not after today, or a claim in a closed period). Until the sheet's today reaches that
// date it counts in no total, is not overdue, cannot be settled and is left unbound by a statement.
// Each claim shows its proofs count, raised by one with its proof button (refused for a SETTLED or
// VOID claim, or one in a closed period). A claim whose due is above the proof threshold in force on
// its date cannot be settled or approved while it has no proofs: refused with an error on the claim.
// A proof has a kind, receipt or photo, chosen beside the proof button (a receipt when none is
// chosen); the claim shows a count per kind beside the total. While the sheet has proof kinds, a claim
// above the proof threshold also needs a proof of each of them before it can be settled or approved.
// An OPEN or PENDING claim can be reminded with its remind button, once per sheet-today: it shows
// the last day a reminder was written (refused for a claim in any other status or already reminded
// on the sheet's today).
// A steward can waive an OPEN or PENDING claim's late charge with its waive button: from then on it
// bears none, whatever its overdue state, and shows waived yes (refused for anyone else, a claim in
// any other status, or one already waived).
// An OPEN or PENDING claim can be held and released with its hold toggle: a held claim still counts
// in every total, and shows held yes
// (refused for a claim in any other status).
// Any claim can be commented with its comment text and button (an empty comment is refused with an
// error on the claim): its comments show oldest first as <user> on <date>: <text>, each kept with
// the user who wrote it and the sheet's today then.
// A claim whose due is above the note threshold in force on its date cannot be opened while its
// note is empty: refused with an error on the claim (by the bulk button, counted as refused).
// An OPEN claim can be split: an amount more than 0.00 and less than its own is split off into a new
// OPEN claim (same date and note, the next number, opened by the user), listed first and showing the
// original's number as split-from; the original keeps the rest and loses any figure a statement bound
// it to. Refused for a claim that is not OPEN, one deferred or in a closed period, and a role that may
// not open a claim.
// An OPEN claim can be part-settled with its part-settle amount and button: the amount is split off as
// the split button does and the new claim is settled at once by the user as the settle button does
// (PENDING above a clerk's limit). The four-eyes and proof rules are judged on the original claim.
// Whatever the split or the settle would refuse refuses the whole, with an error on the claim.
// A steward can re-date an OPEN or PENDING claim: its due-by, and so its period and number, and any
// statement-bound figure stay. Refused for anyone else, a claim in a closed period, and a date after
// its person's retirement.
// A DRAFT claim can have lines added with its line label, quantity (a whole number, at least 1) and
// unit amount (two decimals): its amount becomes the quantities times the unit amounts added up, and
// an edit typing another amount is refused. Adding a line counts as editing the claim; it is refused
// for a claim that is not DRAFT, in a closed period, or locked by another user's editor. The lines
// show in the order added as <label> <qty> x <unit>.
// A DRAFT claim can be deleted with its delete button (refused for any other status or a claim in a
// closed period): it is DELETED, counts nowhere and cannot be edited, opened or voided. Its restore
// button makes it a DRAFT again on the sheet-today it was deleted; on a later day that is refused.
// A claim whose due is above the review threshold in force on its date goes to REVIEW when opened
// (by the bulk button too, counted as opened), instead of OPEN: it shows who opened it but no
// number, counts in no total, and cannot be edited, settled or split; it can be voided. A steward's
// review button makes it OPEN, numbered then; anyone else's is refused with an error on the claim.
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'PENDING' | 'SETTLED' | 'VOID' | 'DELETED' | 'REVIEW';

export interface Claim {
  id: number;
  /** When the claim was added, in the order claims and forward-only rates are saved (see Rate.forwardAfter). */
  addedSeq?: number | null;
  personId: number;
  amount: string;
  date: string;
  note: string;
  status: ClaimStatus;
  /** The claim's number on the sheet, given when opened; null while DRAFT. Sheet-wide (1, 2, ...), or
   *  under numbering per period <period name>-<n>, or X-<n> in no period. */
  number: string | null;
  /** The due fixed when the claim was settled; null while not settled or if its grade then had no rate. */
  settledDue: string | null;
  /** The user who opened the claim; null while DRAFT, or if voided without being opened. */
  openedBy: string | null;
  /** The user who last edited the claim through its form or its row's amount; null while never edited. */
  editedBy: string | null;
  /** Whether the person's last statement bound the claim to the figure it had then. */
  stated: boolean;
  /** The figure bound by the last statement; null while unbound or if it had no rate then. */
  statedDue: string | null;
  /** The due as shown when the last statement bound the claim, late charge included; null while unbound or if it had no rate then. */
  statedCharged: string | null;
  /** The number of the claim this correction corrects; null for a claim that is not a correction. */
  corrects: string | null;
  /** The claim's external reference, unique on the sheet without regard to case; null when none. */
  ref: string | null;
  /** The claim's tags: trimmed, lower-cased, without repeats and sorted; empty when it has none. */
  tags: string[];
  /** The date the claim is due by, fixed when it was added. */
  dueBy: string;
  /** The claim's status log, oldest first: who moved it to each status, on the sheet's today then,
   *  with its due as computed at that moment. */
  log: ClaimLogLine[];
  /** The date the claim is deferred until; null when it was never deferred. */
  deferredUntil: string | null;
  /** How many proofs the claim has. */
  proofs: number;
  /** How many of its proofs are receipts. */
  proofsReceipt: number;
  /** How many of its proofs are photos. */
  proofsPhoto: number;
  /** The number of the claim this claim was split off from; null for a claim not made by a split. */
  splitFrom: string | null;
  /** The user whose open editor locks the claim for everyone else; null when unlocked. */
  lockedBy: string | null;
  /** Whether the claim was added while the period its due-by falls in was soft-closed. */
  lateEntry: boolean;
  /** The last sheet-today a reminder was written for the claim; null when never reminded. */
  remindedOn: string | null;
  /** The reason the claim was voided with, from the sheet's void reasons; null when none. */
  voidReason: string | null;
  /** The claim's comments, oldest first. */
  comments: ClaimCommentLine[];
  /** The number of the bulk settle batch that settled the claim (or made it PENDING); null when not settled by one. */
  batch: number | null;
  /** The claim's lines, in the order added; while it has any, its amount is their total. */
  lines: ClaimLineItem[];
  /** The sheet's today when the claim last went to REVIEW; null when it never has. */
  reviewOn: string | null;
  /** Whether a steward waived the claim's late charge: from then on it bears none. */
  waived: boolean;
  /** Whether the claim is held: a held claim still counts in every total. */
  held: boolean;
}

/** One line of a claim: a label, a quantity and a unit amount. */
export interface ClaimLineItem {
  label: string;
  qty: number;
  unit: string;
}

/** The line typed on a claim, before it is added. */
interface LineDraft {
  label: string;
  qty: string;
  unit: string;
}

/** One comment on a claim: who wrote it, on the sheet's today then. */
export interface ClaimCommentLine {
  by: string;
  date: string;
  text: string;
}

/** One line of a claim's status log. */
export interface ClaimLogLine {
  status: ClaimStatus;
  by: string;
  date: string;
  due: string;
}

/** The date a claim's live figures round by: its own, or for a correction the date of the claim it
 *  corrects, found by number among `claims` (its own when that claim is not among them). */
export function roundingDate(c: Claim, claims: readonly Claim[]): string {
  if (!isCorrection(c) || c.corrects === null) return c.date;
  return claims.find((o) => o.number === c.corrects)?.date ?? c.date;
}

/** Whether a claim is a correction: its amount is below zero. */
export function isCorrection(c: Claim): boolean {
  return cents(c.amount) < 0;
}

/** Another person a claim can be moved to. */
export interface ClaimTarget {
  id: number;
  name: string;
}

/** A move from one status to the next, by the endpoint that makes it. */
type ClaimAction = 'open' | 'review' | 'settle' | 'resettle' | 'approve' | 'reject' | 'void' | 'delete' | 'restore';

/** Whether a claim counts toward claimed, due-total and every total: only OPEN, PENDING and SETTLED do (a correction never toward claimed). */
export function counts(c: Claim): boolean {
  return isOpen(c) || c.status === 'SETTLED';
}

/** The status a claim had at the end of a day (yyyy-mm-dd), as its status log records it (the last
 *  line logged on or before it); null when it had none yet. */
export function statusAt(c: Claim, day: string): ClaimStatus | null {
  let at: ClaimStatus | null = null;
  for (const l of c.log) if (l.date <= day) at = l.status;
  return at;
}

/** Whether a claim is open: OPEN or PENDING. Only an open claim goes overdue. */
export function isOpen(c: Claim): boolean {
  return c.status === 'OPEN' || c.status === 'PENDING';
}

/** Whether a claim is deferred on the sheet's today: open, and its deferral date not yet reached. */
/** Whether a REVIEW claim is late for review on the sheet's today: its review day plus the review SLA
 *  (whole days; blank: none) is before today. */
export function reviewLate(c: Claim, today: string | null, sla: string): boolean {
  return c.status === 'REVIEW' && c.reviewOn !== null && today !== null && sla !== ''
    && plusDays(c.reviewOn, Number(sla)) < today;
}

export function deferred(c: Claim, today: string | null): boolean {
  return isOpen(c) && c.deferredUntil !== null && today !== null && today < c.deferredUntil;
}

/** The refusal of a change to a claim dated inside a closed period. */
const CLOSED = 'That date is inside a closed period.';

/** The refusal of editing a claim another user's open editor locks (or no longer a DRAFT). */
const LOCKED = 'Another user is editing this claim, or it is no longer a DRAFT.';

/** The refusal of a correction that names no SETTLED claim of its person. */
const UNCORRECTABLE = 'A negative amount must correct a SETTLED claim of this person, by its number.';

const PAST: Record<ClaimAction, string> = {
  open: 'opened',
  review: 'reviewed',
  settle: 'settled',
  resettle: 're-settled',
  approve: 'approved',
  reject: 'rejected',
  void: 'voided',
  delete: 'deleted',
  restore: 'restored',
};

/** A reason for rejecting a claim: one word. */
const REASON = /^\S{1,200}$/;

/** An amount: 0.00 or more, with up to two decimals. */
export const AMOUNT = /^\d{1,12}(\.\d{1,2})?$/;

/** A claim's amount: as {@link AMOUNT}, or below zero for a correction. */
const SIGNED_AMOUNT = /^-?\d{1,12}(\.\d{1,2})?$/;

/** A claim number: a whole number from 1, or a period's (or X) with its number in it, as <name>-<n>. */
const NUMBER = /^(.{1,100}-)?[1-9]\d{0,8}$/;

/** An amount in whole cents, so sums are exact; below zero for a negative amount. */
export function cents(amount: string): number {
  const negative = amount.startsWith('-');
  const [whole, frac = ''] = (negative ? amount.slice(1) : amount).split('.');
  const c = Number(whole) * 100 + Number(frac.padEnd(2, '0'));
  return negative ? -c : c;
}

/** Days since 1970-01-01 of a yyyy-mm-dd date (proleptic Gregorian). */
export function dayNumber(date: string): number {
  const [y0, m, d] = date.split('-').map(Number);
  const y = m <= 2 ? y0 - 1 : y0;
  const era = Math.floor(y / 400);
  const yoe = y - era * 400;
  const doy = Math.floor((153 * (m + (m > 2 ? -3 : 9)) + 2) / 5) + d - 1;
  const doe = yoe * 365 + Math.floor(yoe / 4) - Math.floor(yoe / 100) + doy;
  return era * 146097 + doe - 719468;
}

/** The yyyy-mm-dd date a number of days after a yyyy-mm-dd date (proleptic Gregorian). */
export function plusDays(date: string, days: number): string {
  const z = dayNumber(date) + days + 719468;
  const era = Math.floor(z / 146097);
  const doe = z - era * 146097;
  const yoe = Math.floor((doe - Math.floor(doe / 1460) + Math.floor(doe / 36524) - Math.floor(doe / 146096)) / 365);
  const doy = doe - (365 * yoe + Math.floor(yoe / 4) - Math.floor(yoe / 100));
  const mp = Math.floor((5 * doy + 2) / 153);
  const d = doy - Math.floor((153 * mp + 2) / 5) + 1;
  const m = mp < 10 ? mp + 3 : mp - 9;
  const y = yoe + era * 400 + (m <= 2 ? 1 : 0);
  return `${String(y).padStart(4, '0')}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
}

/** The date a claim is due by: fixed when it was added. */
export function dueBy(c: Claim): string {
  return c.dueBy;
}

/** Whether a claim is overdue: open (OPEN or PENDING), not deferred, and due by a date that, with
 *  the grace days of its person's grade added, is before the sheet's today. */
export function overdue(c: Claim, today: string | null, grace = 0): boolean {
  return isOpen(c) && !deferred(c, today) && today !== null && plusDays(dueBy(c), grace) < today;
}

/** The days a claim is overdue on the sheet's today: past its due-by plus its grace days; 0 when it is not overdue. */
export function daysOverdue(c: Claim, today: string | null, grace = 0): number {
  return overdue(c, today, grace) && today !== null ? dayNumber(today) - dayNumber(dueBy(c)) - grace : 0;
}

/** Whole cents shown with two decimals, with a minus sign below zero. */
export function money(c: number): string {
  const a = Math.abs(c);
  return `${c < 0 ? '-' : ''}${Math.floor(a / 100)}.${String(a % 100).padStart(2, '0')}`;
}

@Component({
  selector: 'app-person-claims',
  imports: [FigurePipe],
  template: `
    <form [attr.aria-label]="'Claim for ' + personName()" (submit)="save($event)">
      <label [for]="'claim-amount-' + personId()">Amount</label>
      <input
        [id]="'claim-amount-' + personId()"
        data-testid="claim-form-amount"
        inputmode="decimal"
        [value]="amountInput()"
        (input)="amountInput.set($any($event.target).value); error.set(null)"
      />
      <label [for]="'claim-date-' + personId()">Date</label>
      <input
        [id]="'claim-date-' + personId()"
        type="date"
        data-testid="claim-form-date"
        [value]="dateInput()"
        (input)="dateInput.set($any($event.target).value); error.set(null)"
      />
      <label [for]="'claim-note-' + personId()">Note</label>
      <input
        [id]="'claim-note-' + personId()"
        data-testid="claim-form-note"
        [value]="noteInput()"
        (input)="noteInput.set($any($event.target).value); error.set(null)"
      />
      <label [for]="'claim-corrects-' + personId()">Corrects claim no.</label>
      <input
        [id]="'claim-corrects-' + personId()"
        data-testid="claim-form-corrects"
        [value]="correctsInput()"
        (input)="correctsInput.set($any($event.target).value); error.set(null)"
      />
      <label [for]="'claim-ref-' + personId()">Reference</label>
      <input
        [id]="'claim-ref-' + personId()"
        data-testid="claim-form-ref"
        [value]="refInput()"
        (input)="refInput.set($any($event.target).value); error.set(null)"
      />
      <label [for]="'claim-tags-' + personId()">Tags (comma separated)</label>
      <input
        [id]="'claim-tags-' + personId()"
        data-testid="claim-form-tags"
        [value]="tagsInput()"
        (input)="tagsInput.set($any($event.target).value); error.set(null)"
      />
      <button type="submit" data-testid="claim-form-submit">
        {{ editingId() === null ? 'Add claim' : 'Save claim' }}
      </button>
      @if (error(); as err) {
        <p role="alert" data-testid="claim-form-error">{{ err }}</p>
      }
    </form>
    <div role="group" [attr.aria-label]="'Claim from a template for ' + personName()">
      <label [for]="'claim-template-' + personId()">Template</label>
      <select
        [id]="'claim-template-' + personId()"
        data-testid="claim-template-select"
        (change)="templateChosen.set($any($event.target).value); error.set(null)"
      >
        <option value="" [selected]="templateChosen() === ''">Choose a template</option>
        @for (t of templates.list(); track t.id) {
          <option [value]="t.name" [selected]="templateChosen() === t.name">{{ t.name }}</option>
        }
      </select>
      <button type="button" data-testid="claim-template-add" (click)="addFromTemplate()">Add from template</button>
    </div>
    <ul [attr.aria-label]="'Claims of ' + personName()">
      @for (c of claims(); track c.id) {
        <li [attr.data-testid]="'claim-row-' + c.id">
          <input
            type="checkbox"
            data-testid="claim-select"
            [attr.aria-label]="'Select claim of ' + c.amount + ' on ' + c.date"
            [checked]="selected().has(c.id)"
            (change)="selectedChange.emit({ id: c.id, on: $any($event.target).checked })"
          />
          <span>No. <span data-testid="claim-number">{{ c.number ?? '' }}</span></span>
          <span data-testid="claim-amount">{{ c.amount | figure }}</span>
          <span data-testid="claim-date">{{ c.date }}</span>
          <span data-testid="claim-note">{{ c.note }}</span>
          <span>Ref <span data-testid="claim-ref">{{ c.ref ?? '' }}</span></span>
          <span>Tags <span data-testid="claim-tags">{{ c.tags.join(', ') }}</span></span>
          <span>Corrects <span data-testid="claim-corrects">{{ c.corrects ?? '' }}</span></span>
          <span>Period <span data-testid="claim-period">{{ periodOf(c) }}</span></span>
          <span>Due <span data-testid="claim-due">{{ dueOf(c) | figure }}</span></span>
          <span>Due by <span data-testid="claim-due-by">{{ dueByOf(c) }}</span></span>
          <span>Overdue <span data-testid="claim-overdue">{{ overdueOf(c) ? 'yes' : 'no' }}</span></span>
          <span data-testid="claim-status">{{ c.status }}</span>
          <span>Opened by <span data-testid="claim-opened-by">{{ c.openedBy ?? '' }}</span></span>
          <span>Stated <span data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' }}</span></span>
          <span>Deferred <span data-testid="claim-deferred">{{ deferredOf(c) ? 'yes' : 'no' }}</span></span>
          <span>Until <span data-testid="claim-deferred-until">{{ c.deferredUntil ?? '' }}</span></span>
          <span>Proofs <span data-testid="claim-proofs">{{ c.proofs }}</span></span>
          <span>Receipts <span data-testid="claim-proofs-receipt">{{ c.proofsReceipt }}</span></span>
          <span>Photos <span data-testid="claim-proofs-photo">{{ c.proofsPhoto }}</span></span>
          <span>Split from <span data-testid="claim-split-from">{{ c.splitFrom ?? '' }}</span></span>
          <span>Batch <span data-testid="claim-batch">{{ c.batch ?? '' }}</span></span>
          <span>Late entry <span data-testid="claim-late-entry">{{ c.lateEntry ? 'yes' : 'no' }}</span></span>
          <span>Review late <span data-testid="claim-review-late">{{ reviewLateOf(c) ? 'yes' : 'no' }}</span></span>
          <span>Waived <span data-testid="claim-waived">{{ c.waived ? 'yes' : 'no' }}</span></span>
          <span>Held <span data-testid="claim-held">{{ c.held ? 'yes' : 'no' }}</span></span>
          <span>Reminded <span data-testid="claim-reminded">{{ c.remindedOn ?? '' }}</span></span>
          <ol [attr.aria-label]="'Status log of claim of ' + c.amount + ' on ' + c.date">
            @for (l of c.log; track $index) {
              <li data-testid="claim-log-line">{{ l.status }} by {{ l.by }} on {{ l.date }} due={{ l.due }}</li>
            }
          </ol>
          <ol [attr.aria-label]="'Comments on claim of ' + c.amount + ' on ' + c.date">
            @for (m of c.comments; track $index) {
              <li data-testid="claim-comments-line">{{ m.by }} on {{ m.date }}: {{ m.text }}</li>
            }
          </ol>
          <ol [attr.aria-label]="'Lines of claim of ' + c.amount + ' on ' + c.date">
            @for (l of c.lines; track $index) {
              <li data-testid="claim-lines-line">{{ l.label }} {{ l.qty }} x {{ l.unit }}</li>
            }
          </ol>
          <button type="button" data-testid="claim-timeline" (click)="showTimeline(c)">Timeline</button>
          <ol [attr.aria-label]="'Timeline of claim of ' + c.amount + ' on ' + c.date">
            @for (line of timelines().get(c.id) ?? []; track $index) {
              <li data-testid="claim-timeline-line">{{ line }}</li>
            }
          </ol>
          <label [for]="'claim-line-label-' + c.id">Line label</label>
          <input
            [id]="'claim-line-label-' + c.id"
            data-testid="claim-line-label"
            [value]="lineDrafts().get(c.id)?.label ?? ''"
            (input)="setLine(c.id, 'label', $any($event.target).value)"
          />
          <label [for]="'claim-line-qty-' + c.id">Quantity</label>
          <input
            [id]="'claim-line-qty-' + c.id"
            data-testid="claim-line-qty"
            inputmode="numeric"
            [value]="lineDrafts().get(c.id)?.qty ?? ''"
            (input)="setLine(c.id, 'qty', $any($event.target).value)"
          />
          <label [for]="'claim-line-unit-' + c.id">Unit amount</label>
          <input
            [id]="'claim-line-unit-' + c.id"
            data-testid="claim-line-unit"
            inputmode="decimal"
            [value]="lineDrafts().get(c.id)?.unit ?? ''"
            (input)="setLine(c.id, 'unit', $any($event.target).value)"
          />
          <button type="button" data-testid="claim-line-add" (click)="addLine(c)">Add line</button>
          <label [for]="'claim-comment-text-' + c.id">Comment</label>
          <input
            [id]="'claim-comment-text-' + c.id"
            data-testid="claim-comment-text"
            [value]="commentTexts().get(c.id) ?? ''"
            (input)="setCommentText(c.id, $any($event.target).value)"
          />
          <button type="button" data-testid="claim-comment-add" (click)="comment(c)">Add comment</button>
          <button type="button" data-testid="claim-open" (click)="act(c, 'open')">Open</button>
          <button type="button" data-testid="claim-review" (click)="act(c, 'review')">Review</button>
          <label [for]="'claim-settle-date-' + c.id">Settle as of</label>
          <input
            [id]="'claim-settle-date-' + c.id"
            type="date"
            data-testid="claim-settle-date"
            [value]="settleDates().get(c.id) ?? ''"
            (input)="setSettleDate(c.id, $any($event.target).value)"
          />
          <button type="button" data-testid="claim-settle" (click)="act(c, 'settle')">Settle</button>
          <button type="button" data-testid="claim-resettle" (click)="act(c, 'resettle')">Re-settle</button>
          <button type="button" data-testid="claim-approve" (click)="act(c, 'approve')">Approve</button>
          <label [for]="'claim-reject-reason-' + c.id">Reject reason</label>
          <input
            [id]="'claim-reject-reason-' + c.id"
            data-testid="claim-reject-reason"
            [value]="reasons().get(c.id) ?? ''"
            (input)="setReason(c.id, $any($event.target).value)"
          />
          <button type="button" data-testid="claim-reject" (click)="act(c, 'reject')">Reject</button>
          <label [for]="'claim-proof-kind-' + c.id">Proof kind</label>
          <select
            [id]="'claim-proof-kind-' + c.id"
            data-testid="claim-proof-kind"
            (change)="setProofKind(c.id, $any($event.target).value)"
          >
            <option value="" [selected]="!proofKindsChosen().get(c.id)">(receipt)</option>
            @for (k of proofKinds; track k) {
              <option [value]="k" [selected]="proofKindsChosen().get(c.id) === k">{{ k }}</option>
            }
          </select>
          <button type="button" data-testid="claim-proof" (click)="addProof(c)">Add proof</button>
          <button type="button" data-testid="claim-remind" (click)="remind(c)">Remind</button>
          <button type="button" data-testid="claim-waive" (click)="waive(c)">Waive late charge</button>
          <button type="button" data-testid="claim-hold" [attr.aria-pressed]="c.held" (click)="toggleHold(c)">
            {{ c.held ? 'Release' : 'Hold' }}
          </button>
          @if (voidReasons.list().length > 0) {
            <label [for]="'claim-void-reason-' + c.id">Void reason</label>
            <select
              [id]="'claim-void-reason-' + c.id"
              data-testid="claim-void-reason"
              (change)="setVoidReason(c.id, $any($event.target).value)"
            >
              <option value="" [selected]="!voidReasonsChosen().get(c.id)">(choose)</option>
              @for (r of voidReasons.list(); track r.id) {
                <option [value]="r.reason" [selected]="voidReasonsChosen().get(c.id) === r.reason">{{ r.reason }}</option>
              }
            </select>
          }
          <button type="button" data-testid="claim-void" (click)="act(c, 'void')">Void</button>
          <button type="button" data-testid="claim-delete" (click)="act(c, 'delete')">Delete</button>
          <button type="button" data-testid="claim-restore" (click)="act(c, 'restore')">Restore</button>
          <label [for]="'claim-defer-date-' + c.id">Defer until</label>
          <input
            [id]="'claim-defer-date-' + c.id"
            type="date"
            data-testid="claim-defer-date"
            [value]="deferDates().get(c.id) ?? ''"
            (input)="setDeferDate(c.id, $any($event.target).value)"
          />
          <button type="button" data-testid="claim-defer" (click)="defer(c)">Defer</button>
          <label [for]="'claim-date-new-' + c.id">Re-date to</label>
          <input
            [id]="'claim-date-new-' + c.id"
            type="date"
            data-testid="claim-date-new"
            [value]="redates().get(c.id) ?? ''"
            (input)="setRedate(c.id, $any($event.target).value)"
          />
          <button type="button" data-testid="claim-redate" (click)="redate(c)">Re-date</button>
          <label [for]="'claim-split-amount-' + c.id">Split off</label>
          <input
            [id]="'claim-split-amount-' + c.id"
            data-testid="claim-split-amount"
            inputmode="decimal"
            [value]="splitAmounts().get(c.id) ?? ''"
            (input)="setSplitAmount(c.id, $any($event.target).value)"
          />
          <button type="button" data-testid="claim-split" (click)="split(c)">Split</button>
          <label [for]="'claim-settle-part-amount-' + c.id">Settle part</label>
          <input
            [id]="'claim-settle-part-amount-' + c.id"
            data-testid="claim-settle-part-amount"
            inputmode="decimal"
            [value]="settlePartAmounts().get(c.id) ?? ''"
            (input)="setSettlePartAmount(c.id, $any($event.target).value)"
          />
          <button type="button" data-testid="claim-settle-part" (click)="settlePart(c)">Settle part</button>
          <label [for]="'claim-reassign-to-' + c.id">Move to</label>
          <select
            [id]="'claim-reassign-to-' + c.id"
            data-testid="claim-reassign-to"
            (change)="setTarget(c.id, $any($event.target).value)"
          >
            <option value="" [selected]="!targets().get(c.id)">Choose a person</option>
            @for (o of others(); track o.id) {
              <option [value]="o.id" [selected]="targets().get(c.id) === '' + o.id">{{ o.name }}</option>
            }
          </select>
          <button type="button" data-testid="claim-reassign" (click)="reassign(c)">Move</button>
          <button
            type="button"
            data-testid="claim-edit"
            [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date"
            (click)="edit(c)"
          >
            Edit
          </button>
          <label [for]="'claim-inline-amount-' + c.id">New amount</label>
          <input
            [id]="'claim-inline-amount-' + c.id"
            data-testid="claim-inline-amount"
            inputmode="decimal"
            [value]="inlineAmounts().get(c.id) ?? ''"
            (input)="setInlineAmount(c.id, $any($event.target).value)"
          />
          <button
            type="button"
            data-testid="claim-inline-save"
            [attr.aria-label]="'Save amount of claim of ' + c.amount + ' on ' + c.date"
            (click)="saveInline(c)"
          >
            Save amount
          </button>
          @if (editingId() === c.id) {
            <button type="button" data-testid="claim-cancel" (click)="cancel(c)">Cancel</button>
          }
          @if (actionErrors().get(c.id); as err) {
            <p role="alert" data-testid="claim-error">{{ err }}</p>
          }
        </li>
      }
    </ul>
  `,
})
export class PersonClaims {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  readonly claims = input.required<Claim[]>();
  /** The person's grades: the one in force on a claim's date, at its rate then, gives what the claim has due. */
  readonly grades = input.required<DatedGrade[]>();
  /** The sheet's rates. */
  readonly rates = input.required<Rate[]>();
  /** The sheet's floors, dated: the one in force on a claim's date is the least its live due can be. */
  readonly floors = input.required<DatedValue[]>();
  /** The sheet's late percents, dated: for each full 30 days a claim is overdue, the one in force on
   *  that boundary date (its due-by plus 30, 60, 90 ... days) is what it gains of its figure. */
  readonly latePcts = input.required<DatedValue[]>();
  /** Each grade's grace days, dated: those of the person's grade on a claim's date, in force on its
   *  date, put off its going overdue and its 30-day boundaries. */
  readonly graces = input.required<Record<string, DatedValue[]>>();
  /** The sheet's policy date (blank: its today): a claim dated before it rounds half up, else half to even. */
  readonly policyDate = input.required<string>();
  /** The sheet's rounding modes, dated (blank: none): the one in force on a claim's date, when there is one, is how it rounds. */
  readonly roundings = input.required<DatedValue[]>();
  /** The sheet's periods. */
  readonly periods = input.required<Period[]>();
  /** The ids of the claims selected for a bulk action, across the sheet. */
  readonly selected = input.required<ReadonlySet<number>>();
  /** The sheet's people, by id and name: a claim can be moved to any of them but its own. */
  readonly people = input.required<ClaimTarget[]>();
  readonly saved = output<Claim>();
  /** A claim moved to another person: it is listed first under them. */
  readonly reassigned = output<Claim>();
  /** A claim's checkbox ticked (on) or cleared. */
  readonly selectedChange = output<{ id: number; on: boolean }>();

  /** The DRAFT claim of this person whose editor this user holds the lock of: its editor is open,
   *  with the claim's values, when the sheet is loaded. */
  private readonly lockedId = computed(
    () => this.claims().find((c) => c.lockedBy === this.session.user() && c.status === 'DRAFT')?.id ?? null,
  );
  /** The claim whose editor this component opened (edit), until it is closed: when its lock shows
   *  up on the claim, what was typed into the editor meanwhile is kept. */
  private opened: number | null = null;
  protected readonly amountInput = this.editorField((c) => c.amount);
  protected readonly dateInput = this.editorField((c) => c.date);
  protected readonly noteInput = this.editorField((c) => c.note);
  protected readonly correctsInput = this.editorField((c) => c.corrects ?? '');
  protected readonly refInput = this.editorField((c) => c.ref ?? '');
  protected readonly tagsInput = this.editorField((c) => c.tags.join(', '));
  protected readonly editingId = linkedSignal(() => this.lockedId());
  protected readonly error = signal<string | null>(null);
  /** The sheet's claim templates, one of which adds a DRAFT claim from this row. */
  protected readonly templates = inject(ClaimTemplates);
  /** The name of the template chosen to add a claim from; '' when none is. */
  protected readonly templateChosen = signal('');
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;
  /** The lock an opened editor is taking, while it is being taken. */
  private locking: Promise<boolean> | null = null;
  /** The error of each claim's last refused status change, by claim id. */
  protected readonly actionErrors = signal<ReadonlyMap<number, string>>(new Map());
  /** The reason typed for rejecting each claim, by claim id. */
  protected readonly reasons = signal<ReadonlyMap<number, string>>(new Map());
  /** The line typed on each claim, by claim id. */
  protected readonly lineDrafts = signal<ReadonlyMap<number, LineDraft>>(new Map());
  /** The comment typed on each claim, by claim id. */
  protected readonly commentTexts = signal<ReadonlyMap<number, string>>(new Map());
  /** The sheet's void reasons, one of which a void needs while there are any. */
  protected readonly voidReasons = inject(VoidReasons);
  /** The void reason chosen for each claim, by claim id. */
  protected readonly voidReasonsChosen = signal<ReadonlyMap<number, string>>(new Map());
  /** The kinds a proof can be. */
  protected readonly proofKinds = ['receipt', 'photo'] as const;
  /** The proof kind chosen for each claim's next proof, by claim id; none chosen: a receipt. */
  protected readonly proofKindsChosen = signal<ReadonlyMap<number, string>>(new Map());
  /** The date typed to settle each claim as of, by claim id; blank for the sheet's today. */
  protected readonly settleDates = signal<ReadonlyMap<number, string>>(new Map());
  /** The date typed to defer each claim until, by claim id. */
  protected readonly deferDates = signal<ReadonlyMap<number, string>>(new Map());
  /** The new date typed for each claim, by claim id. */
  protected readonly redates = signal<ReadonlyMap<number, string>>(new Map());
  /** The amount typed to split off each claim, by claim id. */
  protected readonly splitAmounts = signal<ReadonlyMap<number, string>>(new Map());
  /** The amount typed to part-settle each claim, by claim id. */
  protected readonly settlePartAmounts = signal<ReadonlyMap<number, string>>(new Map());
  /** The amount typed to change each DRAFT claim's amount to in its row, by claim id. */
  protected readonly inlineAmounts = signal<ReadonlyMap<number, string>>(new Map());
  /** The audit lines shown as each claim's timeline, newest first, by claim id. */
  protected readonly timelines = signal<ReadonlyMap<number, readonly string[]>>(new Map());
  /** Counts presses of each claim's timeline button, so only the answer to the latest one is shown. */
  private readonly timelinePresses = new Map<number, number>();
  /** The person id chosen to move each claim to, by claim id. */
  protected readonly targets = signal<ReadonlyMap<number, string>>(new Map());
  /** The people a claim of this person can be moved to. */
  protected readonly others = computed(() => this.people().filter((p) => p.id !== this.personId()));

  /** A stated claim shows the figure bound by the last statement, a settled claim the due fixed
   *  when it was settled; any other claim's due is live, at the rate for the person's grade in
   *  force on the claim's date (the grade in force then), never below the floor in force on its date. An overdue claim's late charge is
   *  added on top. */
  protected dueOf(c: Claim): string {
    const late: Lateness = {
      latePcts: this.latePcts(),
      graces: this.graces(),
      today: this.session.today(),
      policyDate: this.policyDate(),
      roundings: this.roundings(),
    };
    const roundOn = roundingDate(c, this.claims());
    return dueText(chargedDueCents(c, this.rates(), gradeOn(this.grades(), c.date), this.floors(), late, roundOn));
  }

  protected dueByOf(c: Claim): string {
    return dueBy(c);
  }

  protected overdueOf(c: Claim): boolean {
    return overdue(c, this.session.today(), graceOn(this.graces(), gradeOn(this.grades(), c.date), c.date));
  }

  /** Whether the claim shows as late for review (see reviewLate). */
  protected reviewLateOf(c: Claim): boolean {
    return reviewLate(c, this.session.today(), this.session.reviewSla());
  }

  /** Whether the claim shows as deferred: its deferral date not yet reached, a voided claim keeping its deferral. */
  protected deferredOf(c: Claim): boolean {
    const today = this.session.today();
    return c.status === 'VOID' ? c.deferredUntil !== null && today !== null && today < c.deferredUntil : deferred(c, today);
  }

  /** The name of the period whose dates contain the claim's due-by; blank when none does. */
  protected periodOf(c: Claim): string {
    return periodOn(this.periods(), c.dueBy)?.name ?? '';
  }

  /** An editor field: the value of the claim whose lock this user holds (blank when none), except
   *  that the editor this component opened keeps what was typed when that claim's lock arrives. */
  private editorField(of: (c: Claim) => string) {
    return linkedSignal<number | null, string>({
      source: () => this.lockedId(),
      computation: (id, prev) => {
        if (prev !== undefined && id !== null && id === this.opened) return prev.value;
        const c = id === null ? undefined : untracked(this.claims).find((x) => x.id === id);
        return c === undefined ? '' : of(c);
      },
    });
  }

  /** Open a claim's editor, taking its lock; refused when it cannot be edited or another user holds it. */
  protected async edit(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    if (inClosedPeriod(this.periods(), c.dueBy)) {
      this.actionErrors.update((m) => new Map(m).set(c.id, CLOSED));
      return;
    }
    if (c.status !== 'DRAFT') {
      this.actionErrors.update((m) => new Map(m).set(c.id, `A ${c.status} claim cannot be edited.`));
      return;
    }
    if (c.lockedBy !== null && c.lockedBy !== this.session.user()) {
      this.actionErrors.update((m) => new Map(m).set(c.id, LOCKED));
      return;
    }
    if (this.submitting || this.locking !== null) return;
    const sheet = this.session.sheet();
    const was = this.editingId();
    const typed = [this.amountInput(), this.dateInput(), this.noteInput(), this.correctsInput(), this.refInput(), this.tagsInput()] as const;
    // the editor opens with the claim's values at once, so what is typed next is not overwritten
    // when the lock comes back; a submit meanwhile waits for the lock
    this.editingId.set(c.id);
    this.opened = c.id;
    this.amountInput.set(c.amount);
    this.dateInput.set(c.date);
    this.noteInput.set(c.note);
    this.correctsInput.set(c.corrects ?? '');
    this.refInput.set(c.ref ?? '');
    this.tagsInput.set(c.tags.join(', '));
    this.error.set(null);
    const locking = this.takeLock(c, sheet, was, typed);
    this.locking = locking;
    try {
      await locking;
    } finally {
      if (this.locking === locking) this.locking = null;
    }
  }

  /** Take a claim's lock for its opened editor: true when taken; when refused, the editor is put
   *  back as it was and the claim shows the error. */
  private async takeLock(c: Claim, sheet: string | null, was: number | null,
    typed: readonly [string, string, string, string, string, string]): Promise<boolean> {
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>('/api/claims/lock', { sheet, user: this.session.user(), id: c.id }),
      );
      if (this.session.sheet() !== sheet) return false;
      // taking this lock released the one this user held on another claim of the person
      const released = this.claims().find((x) => x.id === was && x.id !== claim.id);
      // the editor's fields follow the claim this user locks: keep what was typed while the lock was taken
      const now = [this.amountInput(), this.dateInput(), this.noteInput(), this.correctsInput(), this.refInput(), this.tagsInput()] as const;
      if (released !== undefined) this.saved.emit({ ...released, lockedBy: null });
      this.saved.emit(claim);
      this.editingId.set(claim.id);
      this.amountInput.set(now[0]);
      this.dateInput.set(now[1]);
      this.noteInput.set(now[2]);
      this.correctsInput.set(now[3]);
      this.refInput.set(now[4]);
      this.tagsInput.set(now[5]);
      return true;
    } catch (e) {
      if (this.session.sheet() !== sheet) return false;
      this.editingId.set(was);
      this.opened = was;
      this.amountInput.set(typed[0]);
      this.dateInput.set(typed[1]);
      this.noteInput.set(typed[2]);
      this.correctsInput.set(typed[3]);
      this.refInput.set(typed[4]);
      this.tagsInput.set(typed[5]);
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      const err =
        status === 423
          ? CLOSED
          : status === 403
            ? 'Your role may not edit a claim on this sheet.'
            : status === 409
              ? LOCKED
              : 'Could not open this claim\'s editor.';
      this.actionErrors.update((m) => new Map(m).set(c.id, err));
      return false;
    }
  }

  /** Close the claim's editor unsaved, releasing its lock. */
  protected async cancel(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    const sheet = this.session.sheet();
    this.reset();
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>('/api/claims/unlock', { sheet, user: this.session.user(), id: c.id }),
      );
      if (this.session.sheet() !== sheet) return;
      this.saved.emit(claim);
    } catch {
      // the editor is closed; a lock left behind is released when the sheet's today changes
    }
  }

  protected reset(): void {
    this.opened = null;
    this.editingId.set(null);
    this.amountInput.set('');
    this.dateInput.set('');
    this.noteInput.set('');
    this.correctsInput.set('');
    this.refInput.set('');
    this.tagsInput.set('');
    this.error.set(null);
  }

  private clearActionError(id: number): void {
    this.actionErrors.update((m) => {
      if (!m.has(id)) return m;
      const next = new Map(m);
      next.delete(id);
      return next;
    });
  }

  protected setReason(id: number, reason: string): void {
    this.reasons.update((m) => new Map(m).set(id, reason));
    this.clearActionError(id);
  }

  protected setLine(id: number, field: keyof LineDraft, value: string): void {
    this.lineDrafts.update((m) => {
      const draft = m.get(id) ?? { label: '', qty: '', unit: '' };
      return new Map(m).set(id, { ...draft, [field]: value });
    });
    this.clearActionError(id);
  }

  protected setCommentText(id: number, text: string): void {
    this.commentTexts.update((m) => new Map(m).set(id, text));
    this.clearActionError(id);
  }

  protected setVoidReason(id: number, reason: string): void {
    this.voidReasonsChosen.update((m) => new Map(m).set(id, reason));
    this.clearActionError(id);
  }

  protected setProofKind(id: number, kind: string): void {
    this.proofKindsChosen.update((m) => new Map(m).set(id, kind));
    this.clearActionError(id);
  }

  protected setTarget(id: number, personId: string): void {
    this.targets.update((m) => new Map(m).set(id, personId));
    this.clearActionError(id);
  }

  protected setSettleDate(id: number, date: string): void {
    this.settleDates.update((m) => new Map(m).set(id, date));
    this.clearActionError(id);
  }

  protected setDeferDate(id: number, date: string): void {
    this.deferDates.update((m) => new Map(m).set(id, date));
    this.clearActionError(id);
  }

  /** Defer an OPEN claim until the date typed; the server refuses any other claim, a date not after
   *  the sheet's today, and a claim dated inside a closed period. */
  protected async defer(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    const sheet = this.session.sheet();
    const until = (this.deferDates().get(c.id) ?? '').trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(until)) {
      this.actionErrors.update((m) => new Map(m).set(c.id, 'Enter the date to defer this claim until.'));
      return;
    }
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>('/api/claims/defer', { sheet, user: this.session.user(), id: c.id, until }),
      );
      if (this.session.sheet() !== sheet) return;
      this.saved.emit(claim);
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      const err =
        status === 423
          ? CLOSED
          : status === 400
            ? 'A claim can only be deferred to a date after the sheet\'s today.'
            : status === 409
              ? `A ${c.status} claim cannot be deferred.`
              : 'Could not defer this claim.';
      this.actionErrors.update((m) => new Map(m).set(c.id, err));
    }
  }

  protected setRedate(id: number, date: string): void {
    this.redates.update((m) => new Map(m).set(id, date));
    this.clearActionError(id);
  }

  /** Re-date an OPEN or PENDING claim to the date typed; the server refuses anyone but a steward,
   *  any other claim, a claim in a closed period or a new date inside one, and a date after its
   *  person's retirement. */
  protected async redate(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    const sheet = this.session.sheet();
    const date = (this.redates().get(c.id) ?? '').trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.actionErrors.update((m) => new Map(m).set(c.id, 'Enter the date to re-date this claim to.'));
      return;
    }
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>('/api/claims/redate', { sheet, user: this.session.user(), id: c.id, date }),
      );
      if (this.session.sheet() !== sheet) return;
      this.saved.emit(claim);
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      const err =
        status === 423
          ? CLOSED
          : status === 403
            ? 'Only a steward can re-date a claim.'
            : status === 422
              ? 'That date is after the person\'s retirement date.'
              : status === 409
                ? `A ${c.status} claim cannot be re-dated.`
                : 'Could not re-date this claim.';
      this.actionErrors.update((m) => new Map(m).set(c.id, err));
    }
  }

  protected setInlineAmount(id: number, amount: string): void {
    this.inlineAmounts.update((m) => new Map(m).set(id, amount));
    this.clearActionError(id);
  }

  /** Change a DRAFT claim's amount in its row, keeping its date, note and correction: refused as
   *  the claim form's save is (the amount's form, a claim that cannot be edited, a locked claim, a
   *  closed period, the viewer), with the refusal shown on the claim. */
  protected async saveInline(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    const sheet = this.session.sheet();
    const amount = (this.inlineAmounts().get(c.id) ?? '').trim();
    if (!SIGNED_AMOUNT.test(amount)) {
      this.actionErrors.update((m) =>
        new Map(m).set(c.id, 'Enter an amount with up to two decimals (below zero only for a correction).'));
      return;
    }
    const correction = cents(amount) < 0;
    if (correction && !this.correctable(c.corrects ?? '')) {
      this.actionErrors.update((m) => new Map(m).set(c.id, UNCORRECTABLE));
      return;
    }
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>('/api/claims/amount', { sheet, user: this.session.user(), id: c.id, amount }),
      );
      if (this.session.sheet() !== sheet) return;
      this.inlineAmounts.update((m) => {
        const next = new Map(m);
        next.delete(c.id);
        return next;
      });
      this.saved.emit(claim);
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      const err =
        status === 403
          ? 'Your role may not edit a claim on this sheet.'
          : status === 423
            ? CLOSED
            : status === 422
              ? 'A claim cannot be dated after its person\'s retirement date.'
              : status === 400 && correction
                ? UNCORRECTABLE
                : status === 409
                  ? 'Only a DRAFT claim can be edited, and not while another user is editing it.'
                  : 'Could not save this claim\'s amount.';
      this.actionErrors.update((m) => new Map(m).set(c.id, err));
    }
  }

  protected setSplitAmount(id: number, amount: string): void {
    this.splitAmounts.update((m) => new Map(m).set(id, amount));
    this.clearActionError(id);
  }

  /** Split the amount typed off an OPEN claim into a new OPEN claim; the server refuses any other
   *  claim, a deferred one, one dated inside a closed period, an amount not above 0.00 and below the
   *  claim's, and a role that may not open a claim. */
  protected async split(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    const sheet = this.session.sheet();
    const amount = (this.splitAmounts().get(c.id) ?? '').trim();
    if (!AMOUNT.test(amount) || cents(amount) <= 0 || cents(amount) >= cents(c.amount)) {
      this.actionErrors.update((m) =>
        new Map(m).set(c.id, 'Enter an amount more than 0.00 and less than this claim\'s amount.'));
      return;
    }
    try {
      const done = await firstValueFrom(
        this.http.post<{ original: Claim; split: Claim }>('/api/claims/split',
          { sheet, user: this.session.user(), id: c.id, amount }),
      );
      if (this.session.sheet() !== sheet) return;
      this.splitAmounts.update((m) => {
        const next = new Map(m);
        next.delete(c.id);
        return next;
      });
      this.saved.emit(done.original);
      this.saved.emit(done.split);
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      const err =
        status === 403
          ? 'Your role may not open a claim, so it may not split one on this sheet.'
          : status === 423
            ? CLOSED
            : status === 400
              ? 'Enter an amount more than 0.00 and less than this claim\'s amount.'
              : status === 409
                ? deferred(c, this.session.today())
                  ? `This claim is deferred until ${c.deferredUntil} and cannot be split before then.`
                  : `A ${c.status} claim cannot be split.`
                : 'Could not split this claim.';
      this.actionErrors.update((m) => new Map(m).set(c.id, err));
    }
  }

  protected setSettlePartAmount(id: number, amount: string): void {
    this.settlePartAmounts.update((m) => new Map(m).set(id, amount));
    this.clearActionError(id);
  }

  /** Split the amount typed off an OPEN claim and settle the new claim at once; the server refuses
   *  the whole when the split or the settle would be refused. */
  protected async settlePart(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    const sheet = this.session.sheet();
    const amount = (this.settlePartAmounts().get(c.id) ?? '').trim();
    if (!AMOUNT.test(amount) || cents(amount) <= 0 || cents(amount) >= cents(c.amount)) {
      this.actionErrors.update((m) =>
        new Map(m).set(c.id, 'Enter an amount more than 0.00 and less than this claim\'s amount.'));
      return;
    }
    try {
      const done = await firstValueFrom(
        this.http.post<{ original: Claim; split: Claim }>('/api/claims/settle-part',
          { sheet, user: this.session.user(), id: c.id, amount }),
      );
      if (this.session.sheet() !== sheet) return;
      this.settlePartAmounts.update((m) => {
        const next = new Map(m);
        next.delete(c.id);
        return next;
      });
      this.saved.emit(done.original);
      this.saved.emit(done.split);
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      const err =
        status === 403
          ? c.openedBy === this.session.user()
            ? 'You opened this claim, so you cannot settle any part of it.'
            : 'You may not split and settle this claim.'
          : status === 423
            ? CLOSED
            : status === 422
              ? 'This claim needs its proofs before any part of it can be settled.'
              : status === 400
                ? 'Enter an amount more than 0.00 and less than this claim\'s amount.'
                : status === 409
                  ? deferred(c, this.session.today())
                    ? `This claim is deferred until ${c.deferredUntil} and cannot be settled before then.`
                    : `A ${c.status} claim cannot be part-settled.`
                  : 'Could not settle part of this claim.';
      this.actionErrors.update((m) => new Map(m).set(c.id, err));
    }
  }

  /** Move a claim to the chosen person; the server refuses a clerk, and a SETTLED or VOID claim. */
  protected async reassign(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    const sheet = this.session.sheet();
    const personId = Number(this.targets().get(c.id) ?? '');
    if (!personId) {
      this.actionErrors.update((m) => new Map(m).set(c.id, 'Choose the person to move this claim to.'));
      return;
    }
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>('/api/claims/reassign', { sheet, user: this.session.user(), id: c.id, personId }),
      );
      if (this.session.sheet() !== sheet) return;
      this.targets.update((m) => {
        const next = new Map(m);
        next.delete(c.id);
        return next;
      });
      this.reassigned.emit(claim);
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      const err =
        status === 403
          ? 'Your role may not move a claim on this sheet.'
          : status === 423
            ? CLOSED
            : status === 422
              ? 'A claim cannot be moved to a person retired before its date.'
              : status === 400
                ? 'A correction cannot be moved.'
                : status === 409 && (c.status === 'SETTLED' || c.status === 'VOID')
                  ? `A ${c.status} claim cannot be moved.`
                  : 'Could not move this claim.';
      this.actionErrors.update((m) => new Map(m).set(c.id, err));
    }
  }

  /** Raise a claim's proofs count by one, with a proof of the kind chosen (none chosen: a receipt);
   *  the server refuses a SETTLED or VOID claim, and one dated inside a closed period. */
  protected async addProof(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    const sheet = this.session.sheet();
    const kind = this.proofKindsChosen().get(c.id) || null;
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>('/api/claims/proof', { sheet, user: this.session.user(), id: c.id, kind }),
      );
      if (this.session.sheet() !== sheet) return;
      this.proofKindsChosen.update((m) => {
        const next = new Map(m);
        next.delete(c.id);
        return next;
      });
      this.saved.emit(claim);
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      const err =
        status === 423
          ? CLOSED
          : status === 409
            ? `A ${c.status} claim cannot have a proof added.`
            : 'Could not add a proof to this claim.';
      this.actionErrors.update((m) => new Map(m).set(c.id, err));
    }
  }

  /** Add a line to a DRAFT claim; a blank label, a quantity below 1 or a unit amount with more than
   *  two decimals is refused. */
  protected async addLine(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    const sheet = this.session.sheet();
    const draft = this.lineDrafts().get(c.id) ?? { label: '', qty: '', unit: '' };
    const label = draft.label.trim();
    const qty = draft.qty.trim();
    const unit = draft.unit.trim();
    if (!label || !/^\d{1,7}$/.test(qty) || Number(qty) < 1 || !/^\d{1,12}(\.\d{1,2})?$/.test(unit)) {
      this.actionErrors.update((m) =>
        new Map(m).set(c.id, 'Enter a label, a whole quantity of at least 1 and a unit amount with two decimals.'),
      );
      return;
    }
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>('/api/claims/line', {
          sheet,
          user: this.session.user(),
          id: c.id,
          label,
          qty: Number(qty),
          unit,
        }),
      );
      if (this.session.sheet() !== sheet) return;
      this.lineDrafts.update((m) => {
        const next = new Map(m);
        next.delete(c.id);
        return next;
      });
      this.saved.emit(claim);
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      const err =
        status === 403
          ? 'You may not change this claim.'
          : status === 409
            ? `A line cannot be added to this claim: it is not a DRAFT or is being edited by another user.`
            : status === 423
              ? 'This claim is in a closed period.'
              : 'Could not add this line.';
      this.actionErrors.update((m) => new Map(m).set(c.id, err));
    }
  }

  /** Comment on a claim; an empty comment is refused. */
  protected async comment(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    const sheet = this.session.sheet();
    const text = (this.commentTexts().get(c.id) ?? '').trim();
    if (!text) {
      this.actionErrors.update((m) => new Map(m).set(c.id, 'Enter a comment.'));
      return;
    }
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>('/api/claims/comment', { sheet, user: this.session.user(), id: c.id, text }),
      );
      if (this.session.sheet() !== sheet) return;
      this.setCommentText(c.id, '');
      this.saved.emit(claim);
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      const err =
        status === 403
          ? 'You may not comment on this claim.'
          : status === 423
            ? 'This sheet may not be changed.'
            : 'Could not comment on this claim.';
      this.actionErrors.update((m) => new Map(m).set(c.id, err));
    }
  }

  /** Show a claim's timeline: the audit lines about it, newest first, exactly as the file holds them. */
  protected async showTimeline(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    const press = (this.timelinePresses.get(c.id) ?? 0) + 1;
    this.timelinePresses.set(c.id, press);
    this.timelines.update((m) => {
      const next = new Map(m);
      next.delete(c.id);
      return next;
    });
    const sheet = this.session.sheet();
    if (!sheet) return;
    try {
      const res = await firstValueFrom(this.http.post<{ lines: string[] }>('/api/audit/claim', { sheet, id: c.id }));
      if (this.session.sheet() !== sheet || this.timelinePresses.get(c.id) !== press) return;
      this.timelines.update((m) => new Map(m).set(c.id, res.lines));
    } catch {
      if (this.session.sheet() !== sheet || this.timelinePresses.get(c.id) !== press) return;
      this.actionErrors.update((m) => new Map(m).set(c.id, 'Could not read the timeline of this claim.'));
    }
  }

  /** Remind an OPEN or PENDING claim; the server refuses any other status, and a second reminder
   *  on the same sheet-today. */
  protected async remind(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    const sheet = this.session.sheet();
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>('/api/claims/remind', { sheet, user: this.session.user(), id: c.id }),
      );
      if (this.session.sheet() !== sheet) return;
      this.saved.emit(claim);
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      const err =
        status === 409
          ? c.status === 'OPEN' || c.status === 'PENDING'
            ? 'This claim was already reminded today.'
            : `A ${c.status} claim cannot be reminded.`
          : status === 403
            ? 'You may not remind this claim.'
            : 'Could not remind this claim.';
      this.actionErrors.update((m) => new Map(m).set(c.id, err));
    }
  }

  /** Waive an OPEN or PENDING claim's late charge; the server refuses anyone but a steward, any
   *  other status, and a claim already waived. */
  protected async waive(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    const sheet = this.session.sheet();
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>('/api/claims/waive', { sheet, user: this.session.user(), id: c.id }),
      );
      if (this.session.sheet() !== sheet) return;
      this.saved.emit(claim);
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      const err =
        status === 409
          ? c.waived
            ? "This claim's late charge is already waived."
            : `A ${c.status} claim's late charge cannot be waived.`
          : status === 403
            ? 'Only a steward can waive a late charge.'
            : "Could not waive this claim's late charge.";
      this.actionErrors.update((m) => new Map(m).set(c.id, err));
    }
  }

  /** Hold an OPEN or PENDING claim, or release it when held; the server refuses any other status. */
  protected async toggleHold(c: Claim): Promise<void> {
    this.clearActionError(c.id);
    const sheet = this.session.sheet();
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>('/api/claims/hold', { sheet, user: this.session.user(), id: c.id }),
      );
      if (this.session.sheet() !== sheet) return;
      this.saved.emit(claim);
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      const status = e instanceof HttpErrorResponse ? e.status : 0;
      const err =
        status === 409
          ? `A ${c.status} claim cannot be held or released.`
          : c.held
            ? 'Could not release this claim.'
            : 'Could not hold this claim.';
      this.actionErrors.update((m) => new Map(m).set(c.id, err));
    }
  }

  /** Move a claim to its next status; the server refuses a move its status does not allow. */
  protected async act(c: Claim, action: ClaimAction): Promise<void> {
    this.clearActionError(c.id);
    const sheet = this.session.sheet();
    const reason = (this.reasons().get(c.id) ?? '').trim();
    if (action === 'reject' && !REASON.test(reason)) {
      this.actionErrors.update((m) => new Map(m).set(c.id, 'Enter a one-word reason to reject this claim.'));
      return;
    }
    const voidReason = this.voidReasonsChosen().get(c.id) ?? '';
    if (action === 'void' && this.voidReasons.list().length > 0 && !voidReason) {
      this.actionErrors.update((m) => new Map(m).set(c.id, 'Choose a reason to void this claim.'));
      return;
    }
    const on = action === 'settle' ? (this.settleDates().get(c.id) ?? '').trim() : '';
    if (on !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(on)) {
      this.actionErrors.update((m) => new Map(m).set(c.id, 'Enter a date to settle this claim as of.'));
      return;
    }
    const body = {
      sheet,
      user: this.session.user(),
      id: c.id,
      ...(action === 'reject' ? { reason } : action === 'void' && voidReason ? { reason: voidReason } : {}),
      ...(on !== '' ? { on } : {}),
    };
    try {
      const claim = await firstValueFrom(this.http.post<Claim>(`/api/claims/${action}`, body));
      if (this.session.sheet() !== sheet) return;
      if (action === 'reject') this.setReason(c.id, '');
      if (action === 'void') this.setVoidReason(c.id, '');
      if (action === 'settle') this.setSettleDate(c.id, '');
      this.saved.emit(claim);
    } catch (e) {
      if (this.session.sheet() !== sheet) return;
      const err =
        e instanceof HttpErrorResponse && e.status === 403
          ? action === 'approve' || action === 'reject'
            ? c.openedBy === this.session.user() && action === 'approve'
              ? 'The user who opened a claim cannot approve it.'
              : c.editedBy === this.session.user() && action === 'approve'
                ? 'The user who last edited a claim cannot approve it.'
              : 'Only a steward can approve or reject a PENDING claim.'
            : action === 'resettle'
              ? 'Only a steward can re-settle a claim.'
            : action === 'review'
              ? 'Only a steward can review a claim.'
              : action === 'void' && c.status === 'SETTLED'
                ? 'Only a steward can void a SETTLED claim.'
              : action === 'settle' && c.openedBy === this.session.user()
                ? 'The user who opened a claim cannot settle it.'
              : action === 'settle' && c.editedBy === this.session.user()
                ? 'The user who last edited a claim cannot settle it.'
                : `Your role may not ${action} a claim on this sheet.`
          : on !== '' && on > (this.session.today() ?? on)
            ? 'A claim cannot be settled as of a date after the sheet\'s today.'
          : e instanceof HttpErrorResponse && e.status === 423
            ? CLOSED
            : e instanceof HttpErrorResponse && e.status === 422 && action === 'void'
              ? 'Choose a reason from the void reasons to void this claim.'
            : e instanceof HttpErrorResponse && e.status === 422 && action === 'open'
              ? 'This claim is due above the note threshold and cannot be opened with an empty note.'
            : e instanceof HttpErrorResponse && e.status === 422
              ? `This claim is due above the proof threshold and cannot be ${PAST[action]} without a proof (of each kind the sheet needs).`
            : action === 'settle' && deferred(c, this.session.today())
              ? `This claim is deferred until ${c.deferredUntil} and cannot be settled before then.`
            : on !== '' && e instanceof HttpErrorResponse && e.status === 409 && c.status === 'OPEN'
              ? `This claim was not on the sheet on ${on}.`
              : action === 'void' && c.status === 'SETTLED'
                ? 'A SETTLED claim can be voided only within 7 days of its settlement.'
              : action === 'restore' && c.status === 'DELETED'
                ? 'A DELETED claim can be restored only on the day it was deleted.'
              : action === 'delete' && c.status === 'DRAFT'
                ? LOCKED
                : `A ${c.status} claim cannot be ${PAST[action]}.`;
      this.actionErrors.update((m) => new Map(m).set(c.id, err));
    }
  }

  /** Whether a claim number names a SETTLED claim of this person, as a correction must. */
  private correctable(corrects: string): boolean {
    return NUMBER.test(corrects) && this.claims().some((c) => c.number === corrects && c.status === 'SETTLED');
  }

  /** Add a DRAFT claim from the chosen template: its amount and note as the store holds them now,
   *  dated the sheet's today, and refused as the claim form refuses a claim with no date. */
  protected async addFromTemplate(): Promise<void> {
    if (this.submitting) return;
    this.error.set(null);
    const name = this.templateChosen();
    if (name === '') {
      this.error.set('Choose a template to add a claim from.');
      return;
    }
    const sheet = this.session.sheet();
    this.submitting = true;
    try {
      const claim = await firstValueFrom(
        this.http.post<Claim>('/api/templates/claim', { sheet, user: this.session.user(), personId: this.personId(), name }),
      );
      if (this.session.sheet() !== sheet) return;
      this.saved.emit(claim);
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 423
          ? CLOSED
          : e instanceof HttpErrorResponse && e.status === 422
            ? 'A claim cannot be dated after its person\'s retirement date.'
          : e instanceof HttpErrorResponse && e.status === 404
            ? 'That template is no longer on this sheet.'
          : e instanceof HttpErrorResponse && e.status === 403
            ? 'Your role may not add claims.'
            : 'Could not add a claim from this template.',
      );
    } finally {
      this.submitting = false;
    }
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    if (this.locking !== null && !(await this.locking)) return;
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    const amount = this.amountInput().trim();
    const date = this.dateInput().trim();
    const corrects = this.correctsInput().trim();
    if (!SIGNED_AMOUNT.test(amount)) {
      this.error.set('Enter an amount with up to two decimals (below zero only for a correction).');
      return;
    }
    const correction = cents(amount) < 0;
    if (correction && !this.correctable(corrects)) {
      this.error.set(UNCORRECTABLE);
      return;
    }
    if (date !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.error.set('Enter a date, or leave it blank for the sheet\'s today.');
      return;
    }
    const id = this.editingId();
    const body = { sheet, user: this.session.user(), amount, date: date || null, note: this.noteInput().trim(),
      corrects: correction ? corrects : null, ref: this.refInput().trim() || null,
      tags: this.tagsInput() };
    this.submitting = true;
    try {
      const claim =
        id === null
          ? await firstValueFrom(this.http.post<Claim>('/api/claims/add', { ...body, personId: this.personId() }))
          : await firstValueFrom(this.http.post<Claim>('/api/claims/update', { ...body, id }));
      if (this.session.sheet() !== sheet) return;
      this.saved.emit(claim);
      this.reset();
    } catch (e) {
      this.error.set(
        e instanceof HttpErrorResponse && e.status === 423
          ? CLOSED
          : e instanceof HttpErrorResponse && e.status === 422
            ? 'A claim cannot be dated after its person\'s retirement date.'
          : e instanceof HttpErrorResponse && e.status === 400 && correction
            ? UNCORRECTABLE
          : e instanceof HttpErrorResponse && e.status === 409 && id === null
            ? 'That reference is already used on this sheet.'
          : e instanceof HttpErrorResponse && e.status === 409 && this.refInput().trim() !== ''
            ? 'That reference may already be used on this sheet; only a DRAFT claim can be edited, and not while another user is editing it.'
          : e instanceof HttpErrorResponse && e.status === 409
            ? 'Only a DRAFT claim can be edited, and not while another user is editing it.'
            : id === null
              ? 'Could not add this claim.'
              : 'Could not save this claim.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
