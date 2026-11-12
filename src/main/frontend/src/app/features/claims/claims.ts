import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { FigurePipe } from '../../core/locale';
import { Component, OnInit, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Templates } from '../../core/templates';
import { VoidReasons } from '../../core/void-reasons';
import { Period, periodOn } from '../periods/periods';
import { Rate, Rounding, dueOf, floored, percentOf, rateOn, sumDues } from '../rates/rates';

export interface Claim {
  id: number;
  personId: number;
  /** Two decimals, e.g. "2.50". */
  amount: string;
  /** yyyy-mm-dd */
  date: string;
  note: string;
  status: ClaimStatus;
  /** Given when the claim is opened; null for a DRAFT and once voided. */
  number: string | null;
  /** The due fixed when the claim was settled, two decimals; null when it has none and its due is live. */
  settledDue: string | null;
  /** The user who opened the claim; null for a DRAFT. That user cannot settle it. */
  openedBy: string | null;
  /** Whether the person's last statement bound the claim: it then shows the due it bound it to. */
  stated: boolean;
  /** The due the last statement bound the claim to, two decimals; null when unbound, or bound with none (n/a). */
  statedDue: string | null;
  /** The number of the SETTLED claim of the same person this claim corrects; null unless it is a correction. */
  corrects: string | null;
  /** The date (yyyy-mm-dd) that decides how its live figures round: its own, or a correction's corrected claim's. */
  roundingDate: string;
  /** Its place in its person's list: a claim with a later place is listed first (newest added, or last reassigned). */
  listed: number;
  /** The day (yyyy-mm-dd) the claim is due by, fixed when it was added from its person's terms in force on the sheet's today then. */
  dueBy: string;
  /** Its status log, oldest first: one line per status it has had. */
  log: ClaimLogLine[];
  /** The date (yyyy-mm-dd) an OPEN claim is deferred until; null when it was never deferred. */
  deferredUntil: string | null;
  /** How many proofs the claim has: one above the proof threshold on its date cannot be settled or approved with none. */
  proofs: number;
  /** The number of the claim this one was split off; null unless it was split off another. */
  splitFrom: string | null;
  /** The user whose open editor locks the claim against other users' edits; null when not locked. */
  lockedBy: string | null;
  /** Whether the claim was added while the period its due-by falls in was soft-closed. */
  lateEntry: boolean;
  /** The last day (yyyy-mm-dd) a reminder was written for the claim; null when it was never reminded. */
  remindedOn: string | null;
  /** The claim's external reference, unique on the sheet regardless of case; null when it has none. */
  ref: string | null;
  /** The claim's tags: trimmed, lower-cased, without repeats, sorted and joined by commas; null when it has none. */
  tags: string | null;
  /** The reason the claim was voided with, from the sheet's list; null when none. */
  voidReason: string | null;
  /** Its comments, oldest first. */
  comments: ClaimComment[];
  /** The number of the bulk settle that settled it (or sent it to PENDING); null when settled one at a time, or not settled. */
  settleBatch: number | null;
  /** The stamp a forward-only rate is matched against: the claim sees one whose forwardAfter is below it. */
  rateStamp: number;
  /** The day (yyyy-mm-dd) the claim last went to REVIEW; null when it never did. */
  reviewOn: string | null;
  /** Its lines, oldest first: with any, its amount is their quantities times their unit amounts added up. */
  lines: ClaimLine[];
  /** Whether a steward waived its late charge: it then bears none, whatever its overdue state. */
  waived: boolean;
  /** How many proofs it has of each kind (receipt, photo), by kind. */
  proofsByKind: Record<string, number>;
  /** Whether the claim is held: an OPEN or PENDING one then leaves the live totals unless a statement binds it. */
  held: boolean;
  /** The people the claim is shared with, in the order they were added. */
  shares: ClaimShare[];
  /** The user a steward assigned its review to; null when no one. */
  reviewAssignee: string | null;
}

/** A person a claim is shared with, and their share of its due: a percent with two decimals, e.g. "33.33". */
export interface ClaimShare {
  personId: number;
  share: string;
}

/** The kinds a proof can be: one added with none chosen is a receipt. */
const PROOF_KINDS = ['receipt', 'photo'] as const;

/** A line of a claim: a label, a quantity (a whole number, at least 1) and a unit amount (two decimals). */
export interface ClaimLine {
  label: string;
  qty: number;
  unit: string;
}

/** What is typed for a new line of a claim. */
interface LineEntry {
  label: string;
  qty: string;
  unit: string;
}

/** A comment on a claim: who wrote it, the sheet's today then, and its text. */
export interface ClaimComment {
  by: string;
  /** yyyy-mm-dd */
  date: string;
  text: string;
}

/** A line of a claim's status log: the status, who changed it, the sheet's today then, and the due then (or n/a). */
export interface ClaimLogLine {
  status: ClaimStatus;
  by: string;
  /** yyyy-mm-dd */
  date: string;
  due: string;
}

/** Claims as listed: the latest list place first. */
export function byListed(a: Claim, b: Claim): number {
  return b.listed - a.listed || b.id - a.id;
}

/**
 * DRAFT when added, then OPEN, then SETTLED; a clerk settling above the clerk limit makes it PENDING until a steward
 * approves (SETTLED) or rejects it (OPEN). A DRAFT, OPEN or PENDING claim may be VOID, and a steward may void a
 * SETTLED one within 7 days of its settlement. A DRAFT claim may be DELETED, counting nowhere, and restored to DRAFT on
 * the sheet's today it was deleted. A DRAFT claim whose due is above the review threshold on its date goes to REVIEW when
 * opened, counting nowhere until a steward reviews it to OPEN; it may be VOID.
 */
export type ClaimStatus = 'DRAFT' | 'OPEN' | 'PENDING' | 'SETTLED' | 'VOID' | 'DELETED' | 'REVIEW';

/** The claim's tags, sorted; none when it has none. */
export function tagsOf(claim: Claim): string[] {
  return claim.tags === null ? [] : claim.tags.split(',');
}

/** Whether a claim is a correction (a negative amount): its due has no floor and no late charge. */
export function isCorrection(claim: Claim): boolean {
  return claim.amount.trim().startsWith('-') && /[1-9]/.test(claim.amount);
}

/**
 * Whether a claim counts toward claimed and the totals: only OPEN, PENDING and SETTLED ones do, but not one deferred on
 * the sheet's today.
 */
export function counts(claim: Claim, today: string | null): boolean {
  return (isOpen(claim) || claim.status === 'SETTLED') && !deferred(claim, today);
}

/**
 * Whether the claim is deferred on the sheet's today: an OPEN claim deferred until a later date. It then counts in no
 * total, is not overdue, cannot be settled and is left unbound by a statement.
 */
export function deferred(claim: Claim, today: string | null): boolean {
  return claim.status === 'OPEN' && claim.deferredUntil !== null && today !== null && today < claim.deferredUntil;
}

/**
 * The status the claim had at the end of the date, as its status log records it: its last line logged on or before
 * the date; null when it has lines but none by then (it was added later). With no log at all, its status now.
 */
export function statusOn(claim: Claim, date: string): ClaimStatus | null {
  if (claim.log.length === 0) return claim.status;
  let found: ClaimStatus | null = null;
  for (const line of claim.log) if (line.date <= date) found = line.status;
  return found;
}

/** Whether a claim is handled as open: OPEN or PENDING. It goes overdue, bears the late charge, and a hold takes it out. */
export function isOpen(claim: Claim): boolean {
  return claim.status === 'OPEN' || claim.status === 'PENDING';
}

/** Whether the claim's due follows its last statement now: any claim it bound, a PENDING one included. */
export function bound(claim: Claim): boolean {
  return claim.stated;
}

/** One of a person's grades, from its from-date (yyyy-mm-dd); null from the beginning of time. */
export interface PersonGrade {
  grade: string;
  from: string | null;
}

/** The person's grade in force on the date: the one with the latest from-date on or before it. */
export function gradeOn(grades: PersonGrade[], date: string): string | undefined {
  let found: PersonGrade | undefined;
  for (const g of grades) {
    if (g.from !== null && g.from > date) continue;
    if (found === undefined || (found.from ?? '') <= (g.from ?? '')) found = g;
  }
  return found?.grade;
}

/**
 * How the claim's live figures round: by the rounding mode in force on its date when there is one, else half up when
 * its rounding date is before the sheet's policy date (the sheet's today when blank), half to even on or after it.
 */
export function roundingOf(claim: Claim, session: Session): Rounding {
  const mode = session.settingOn('rounding', claim.date);
  if (mode === 'half-up' || mode === 'half-even') return mode;
  const policy = session.policyDate() || session.today();
  return policy !== null && claim.roundingDate < policy ? 'half-up' : 'half-even';
}

/**
 * The due fixed when the claim was settled, else the due its person's last statement bound it to (as shown then, late
 * charge included), else the greater of the floor in force on its date and its figure (its amount times the rate of its
 * person's grade on its date, both in force on its date, or n/a with no rate), with the late charge on that added on top. A
 * correction has neither floor nor late charge.
 */
export function claimDue(claim: Claim, rates: Rate[], grades: PersonGrade[], session: Session): string {
  if (claim.settledDue !== null) return claim.settledDue;
  if (bound(claim)) return claim.statedDue ?? 'n/a';
  const grade = gradeOn(grades, claim.date);
  const figure = dueOf(claim.amount, grade === undefined ? undefined : rateOn(rates, grade, claim.date, claim.rateStamp), roundingOf(claim, session));
  if (figure === 'n/a' || isCorrection(claim)) return figure;
  const base = floored(figure, session.settingOn('floor', claim.date));
  return sumDues([base, lateCharge(claim, base, grades, session)]);
}

/**
 * The day the claim goes overdue after, and its late charge counts from: its due-by plus the grace days of its person's
 * grade on its date in force on that date, else its due-by. The due-by shown stays as it is.
 */
export function overdueBy(claim: Claim, grades: PersonGrade[], session: Session): string {
  if (claim.dueBy === '') return claim.dueBy;
  const grade = gradeOn(grades, claim.date);
  const grace = grade === undefined ? null : session.settingOn('grace-' + grade, claim.date);
  return grace === null || grace === '' ? claim.dueBy : plusDays(claim.dueBy, Number(grace));
}

/**
 * For every full 30 days an OPEN claim is past its due-by (plus its grace) on the sheet's today, the late percent in
 * force on that boundary (the due-by plus its grace plus 30, 60, 90 ... days) of the figure plus the charges of the
 * boundaries before it (compounding), each rounded like the claim's due, added up; 0.00 when it is not overdue, and on a correction.
 */
export function lateCharge(claim: Claim, figure: string, grades: PersonGrade[], session: Session): string {
  const by = overdueBy(claim, grades, session);
  const today = session.today();
  if (!isOpen(claim) || isCorrection(claim) || by === '' || today === null) return '0.00';
  if (deferred(claim, today) || claim.waived) return '0.00';
  const days = Math.round((Date.parse(today) - Date.parse(by)) / 86_400_000);
  const charges: string[] = [];
  for (let boundary = 1; boundary <= Math.floor(days / 30); boundary++) {
    const pct = session.settingOn('late-pct', plusDays(by, 30 * boundary));
    if (pct !== null) charges.push(percentOf(sumDues([figure, ...charges]), pct, 1, roundingOf(claim, session)));
  }
  return sumDues(charges);
}

/** The yyyy-mm-dd date the days after the date. */
export function plusDays(date: string, days: number): string {
  return new Date(Date.parse(date) + days * 86_400_000).toISOString().slice(0, 10);
}

/** Whether the claim's due is fixed, by settling or by a statement, rather than worked out from the rates. */
export function dueFixed(claim: Claim): boolean {
  return claim.settledDue !== null || (bound(claim) && claim.statedDue !== null);
}

/** Whether the claim is OPEN or PENDING, not deferred, and its due-by plus its grace is before the sheet's today. */
export function overdue(claim: Claim, grades: PersonGrade[], session: Session): boolean {
  const today = session.today();
  return isOpen(claim) && today !== null && overdueBy(claim, grades, session) < today && !deferred(claim, today);
}

type ClaimAction = 'open' | 'settle' | 'void' | 'approve' | 'reject' | 'resettle' | 'delete' | 'restore' | 'review';

/** Sums amounts with two decimals exactly, in whole cents. */
export function sumAmounts(amounts: string[]): string {
  const cents = amounts.reduce((total, a) => total + Math.round(Number(a) * 100), 0);
  return (cents / 100).toFixed(2);
}

// One person's claims, newest first, each with its due, and the form that adds a claim or, once a claim's editor is
// opened, saves that claim. Rendered as a table cell inside the person's row.
@Component({
  selector: 'td[app-claims]',
  imports: [FigurePipe],
  template: `
    <form (submit)="submit($event)">
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
      <label [for]="fieldId('corrects')">Corrects claim no.</label>
      <input
        [id]="fieldId('corrects')"
        data-testid="claim-form-corrects"
        [value]="corrects()"
        (input)="corrects.set(value($event)); error.set(null)"
      />
      <label [for]="fieldId('ref')">Reference</label>
      <input
        [id]="fieldId('ref')"
        data-testid="claim-form-ref"
        [value]="ref()"
        (input)="ref.set(value($event)); error.set(null)"
      />
      <label [for]="fieldId('tags')">Tags (comma separated)</label>
      <input
        [id]="fieldId('tags')"
        data-testid="claim-form-tags"
        [value]="tags()"
        (input)="tags.set(value($event)); error.set(null)"
      />
      <button type="submit" data-testid="claim-form-submit">
        {{ editing() === null ? 'Add claim' : 'Save claim' }}
      </button>
      @if (error(); as message) {
        <p role="alert" data-testid="claim-form-error">{{ message | fig }}</p>
      }
    </form>
    <div>
      <label [for]="fieldId('template')">Template</label>
      <select
        [id]="fieldId('template')"
        data-testid="claim-template-select"
        (change)="template.set(value($event)); error.set(null)"
      >
        <option value="" [selected]="template() === ''">No template</option>
        @for (t of templates.list(); track t.id) {
          <option [value]="t.name" [selected]="template() === t.name">{{ t.name | fig }}</option>
        }
      </select>
      <button type="button" data-testid="claim-template-add" (click)="addFromTemplate()">Add claim from template</button>
    </div>
    @if (claims().length > 0) {
      <table>
        <caption class="visually-hidden">Claims of {{ personName() }}</caption>
        <thead>
          <tr>
            <th scope="col"><span class="visually-hidden">Select</span></th><th scope="col">No.</th><th scope="col">Amount</th><th scope="col">Date</th><th scope="col">Note</th><th scope="col">Due</th><th scope="col">Due by</th><th scope="col">Overdue</th><th scope="col">Waived</th><th scope="col">Held</th>
            <th scope="col">Status</th><th scope="col">Review assigned to</th><th scope="col">Batch</th><th scope="col">Opened by</th><th scope="col">Stated</th><th scope="col">Period</th><th scope="col">Corrects</th><th scope="col">Reference</th><th scope="col">Tags</th><th scope="col">Deferred</th><th scope="col">Deferred until</th><th scope="col">Proofs</th><th scope="col">Split from</th><th scope="col">Late entry</th><th scope="col">Reminded</th><th scope="col">Log</th><th scope="col">Timeline</th><th scope="col">Comments</th><th scope="col">Lines</th><th scope="col">Shares</th>
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
                  (change)="selectToggled.emit({ id: c.id, checked: checked($event) })"
                />
              </td>
              <td data-testid="claim-number">{{ c.number ?? '' | fig }}</td>
              <td data-testid="claim-amount">{{ c.amount | fig }}</td>
              <td data-testid="claim-date">{{ c.date | fig }}</td>
              <td data-testid="claim-note">{{ c.note | fig }}</td>
              <td data-testid="claim-due">{{ due(c) | fig }}</td>
              <td data-testid="claim-due-by">{{ c.dueBy | fig }}</td>
              <td data-testid="claim-overdue">{{ overdue(c) ? 'yes' : 'no' | fig }}</td>
              <td data-testid="claim-waived">{{ c.waived ? 'yes' : 'no' | fig }}</td>
              <td data-testid="claim-held">{{ c.held ? 'yes' : 'no' | fig }}</td>
              <td data-testid="claim-status">{{ c.status | fig }}</td>
              <td data-testid="claim-review-assigned">{{ c.reviewAssignee ?? '' | fig }}</td>
              <td data-testid="claim-batch">{{ c.settleBatch ?? '' | fig }}</td>
              <td data-testid="claim-opened-by">{{ c.openedBy ?? '' | fig }}</td>
              <td data-testid="claim-stated">{{ c.stated ? 'yes' : 'no' | fig }}</td>
              <td data-testid="claim-period">{{ periodOf(c) | fig }}</td>
              <td data-testid="claim-corrects">{{ c.corrects ?? '' | fig }}</td>
              <td data-testid="claim-ref">{{ c.ref ?? '' | fig }}</td>
              <td data-testid="claim-tags">{{ tagsShown(c) | fig }}</td>
              <td data-testid="claim-deferred">{{ deferralRunning(c) ? 'yes' : 'no' | fig }}</td>
              <td data-testid="claim-deferred-until">{{ c.deferredUntil ?? '' | fig }}</td>
              <td>
                <span data-testid="claim-proofs">{{ c.proofs | fig }}</span>
                @for (k of proofKinds; track k) {
                  <span> {{ k }}: <span [attr.data-testid]="'claim-proofs-' + k">{{ c.proofsByKind[k] ?? 0 | fig }}</span></span>
                }
              </td>
              <td data-testid="claim-split-from">{{ c.splitFrom ?? '' | fig }}</td>
              <td data-testid="claim-late-entry">{{ c.lateEntry ? 'yes' : 'no' | fig }}</td>
              <td data-testid="claim-reminded">{{ c.remindedOn ?? '' | fig }}</td>
              <td>
                <span data-testid="claim-shares">{{ sharesShown(c) | fig }}</span>
                <label class="visually-hidden" [for]="'claim-share-select-' + c.id">Share with</label>
                <select
                  [id]="'claim-share-select-' + c.id"
                  data-testid="claim-share-select"
                  (change)="setShareEntry(c.id, 'personId', value($event))"
                >
                  <option value="" [selected]="!shareEntries()[c.id]?.personId">Share with…</option>
                  @for (o of people(); track o.id) {
                    <option [value]="o.id" [selected]="shareEntries()[c.id]?.personId === '' + o.id">{{ o.name | fig }}</option>
                  }
                </select>
                <label class="visually-hidden" [for]="'claim-share-pct-' + c.id">Share percent</label>
                <input
                  [id]="'claim-share-pct-' + c.id"
                  data-testid="claim-share-pct"
                  inputmode="decimal"
                  placeholder="Share %"
                  [value]="shareEntries()[c.id]?.share ?? ''"
                  (input)="setShareEntry(c.id, 'share', value($event))"
                />
                <button
                  type="button"
                  data-testid="claim-share-add"
                  [attr.aria-label]="'Share claim of ' + c.amount + ' on ' + c.date"
                  (click)="share(c)"
                >
                  Share
                </button>
              </td>
              <td data-testid="claim-review-late">{{ reviewLate(c) ? 'yes' : 'no' | fig }}</td>
              <td>
                <ol class="claim-log">
                  @for (line of c.log; track $index) {
                    <li data-testid="claim-log-line">{{ line.status }} by {{ line.by }} on {{ line.date }} due={{ line.due }}</li>
                  }
                </ol>
              </td>
              <td>
                <button
                  type="button"
                  data-testid="claim-timeline"
                  [attr.aria-label]="'Show the timeline of claim of ' + c.amount + ' on ' + c.date"
                  (click)="showTimeline(c)"
                >
                  Timeline
                </button>
                <ol class="claim-timeline">
                  @for (line of timelineOf(c); track $index) {
                    <li data-testid="claim-timeline-line">{{ line | fig }}</li>
                  }
                </ol>
              </td>
              <td>
                <ol class="claim-comments">
                  @for (comment of c.comments; track $index) {
                    <li data-testid="claim-comments-line">{{ comment.by }} on {{ comment.date }}: {{ comment.text }}</li>
                  }
                </ol>
                <label class="visually-hidden" [for]="'claim-comment-text-' + c.id">Comment</label>
                <input
                  [id]="'claim-comment-text-' + c.id"
                  data-testid="claim-comment-text"
                  placeholder="Comment"
                  [value]="commentTexts()[c.id] ?? ''"
                  (input)="setCommentText(c.id, value($event))"
                />
                <button
                  type="button"
                  data-testid="claim-comment-add"
                  [attr.aria-label]="'Comment claim of ' + c.amount + ' on ' + c.date"
                  (click)="addComment(c)"
                >
                  Comment
                </button>
              </td>
              <td>
                <ol class="claim-lines">
                  @for (line of c.lines; track $index) {
                    <li data-testid="claim-lines-line">{{ line.label }} {{ line.qty }} x {{ line.unit }}</li>
                  }
                </ol>
                <label class="visually-hidden" [for]="'claim-line-label-' + c.id">Line label</label>
                <input
                  [id]="'claim-line-label-' + c.id"
                  data-testid="claim-line-label"
                  placeholder="Label"
                  [value]="lineEntries()[c.id]?.label ?? ''"
                  (input)="setLineEntry(c.id, 'label', value($event))"
                />
                <label class="visually-hidden" [for]="'claim-line-qty-' + c.id">Line quantity</label>
                <input
                  [id]="'claim-line-qty-' + c.id"
                  data-testid="claim-line-qty"
                  inputmode="numeric"
                  placeholder="Qty"
                  [value]="lineEntries()[c.id]?.qty ?? ''"
                  (input)="setLineEntry(c.id, 'qty', value($event))"
                />
                <label class="visually-hidden" [for]="'claim-line-unit-' + c.id">Line unit amount</label>
                <input
                  [id]="'claim-line-unit-' + c.id"
                  data-testid="claim-line-unit"
                  inputmode="decimal"
                  placeholder="Unit amount"
                  [value]="lineEntries()[c.id]?.unit ?? ''"
                  (input)="setLineEntry(c.id, 'unit', value($event))"
                />
                <button
                  type="button"
                  data-testid="claim-line-add"
                  [attr.aria-label]="'Add a line to claim of ' + c.amount + ' on ' + c.date"
                  (click)="addLine(c)"
                >
                  Add line
                </button>
              </td>
              <td>
                <button
                  type="button"
                  data-testid="claim-edit"
                  [attr.aria-label]="'Edit claim of ' + c.amount + ' on ' + c.date"
                  (click)="edit(c)"
                >
                  Edit
                </button>
                <label class="visually-hidden" [for]="'claim-inline-amount-' + c.id">New amount</label>
                <input
                  [id]="'claim-inline-amount-' + c.id"
                  data-testid="claim-inline-amount"
                  inputmode="decimal"
                  placeholder="New amount"
                  [value]="inlineAmounts()[c.id] ?? ''"
                  (input)="setInlineAmount(c.id, value($event))"
                />
                <button
                  type="button"
                  data-testid="claim-inline-save"
                  [attr.aria-label]="'Save the new amount of claim of ' + c.amount + ' on ' + c.date"
                  (click)="saveInline(c)"
                >
                  Save amount
                </button>
                @if (editing() === c.id) {
                  <button type="button" data-testid="claim-cancel" (click)="cancel()">Cancel</button>
                }
                @for (a of actions; track a.action) {
                  <button
                    type="button"
                    [attr.data-testid]="'claim-' + a.action"
                    [attr.aria-label]="a.label + ' claim of ' + c.amount + ' on ' + c.date"
                    (click)="act(c, a.action)"
                  >
                    {{ a.label }}
                  </button>
                  @if (a.action === 'settle') {
                    <label class="visually-hidden" [for]="'claim-settle-date-' + c.id">Settle as of</label>
                    <input
                      [id]="'claim-settle-date-' + c.id"
                      type="date"
                      data-testid="claim-settle-date"
                      [value]="settleDates()[c.id] ?? ''"
                      (input)="setSettleDate(c.id, value($event))"
                    />
                  }
                }
                <label class="visually-hidden" [for]="'claim-proof-kind-' + c.id">Proof kind</label>
                <select
                  [id]="'claim-proof-kind-' + c.id"
                  data-testid="claim-proof-kind"
                  (change)="setProofKind(c.id, value($event))"
                >
                  <option value="" [selected]="!proofKindsChosen()[c.id]">Kind (none: receipt)</option>
                  @for (k of proofKinds; track k) {
                    <option [value]="k" [selected]="proofKindsChosen()[c.id] === k">{{ k }}</option>
                  }
                </select>
                <button
                  type="button"
                  data-testid="claim-proof"
                  [attr.aria-label]="'Add a proof to claim of ' + c.amount + ' on ' + c.date"
                  (click)="addProof(c)"
                >
                  Add proof
                </button>
                <button
                  type="button"
                  data-testid="claim-remind"
                  [attr.aria-label]="'Remind claim of ' + c.amount + ' on ' + c.date"
                  (click)="remind(c)"
                >
                  Remind
                </button>
                <label class="visually-hidden" [for]="'claim-split-amount-' + c.id">Amount to split off</label>
                <input
                  [id]="'claim-split-amount-' + c.id"
                  data-testid="claim-split-amount"
                  inputmode="decimal"
                  placeholder="Amount to split off"
                  [value]="splitAmounts()[c.id] ?? ''"
                  (input)="setSplitAmount(c.id, value($event))"
                />
                <button
                  type="button"
                  data-testid="claim-split"
                  [attr.aria-label]="'Split claim of ' + c.amount + ' on ' + c.date"
                  (click)="split(c)"
                >
                  Split
                </button>
                <label class="visually-hidden" [for]="'claim-settle-part-amount-' + c.id">Amount to settle</label>
                <input
                  [id]="'claim-settle-part-amount-' + c.id"
                  data-testid="claim-settle-part-amount"
                  inputmode="decimal"
                  placeholder="Amount to settle"
                  [value]="settlePartAmounts()[c.id] ?? ''"
                  (input)="setSettlePartAmount(c.id, value($event))"
                />
                <button
                  type="button"
                  data-testid="claim-settle-part"
                  [attr.aria-label]="'Part-settle claim of ' + c.amount + ' on ' + c.date"
                  (click)="settlePart(c)"
                >
                  Settle part
                </button>
                <label class="visually-hidden" [for]="'claim-defer-date-' + c.id">Defer until</label>
                <input
                  [id]="'claim-defer-date-' + c.id"
                  type="date"
                  data-testid="claim-defer-date"
                  [value]="deferDates()[c.id] ?? ''"
                  (input)="setDeferDate(c.id, value($event))"
                />
                <button
                  type="button"
                  data-testid="claim-waive"
                  [attr.aria-label]="'Waive late charge of claim of ' + c.amount + ' on ' + c.date"
                  (click)="waive(c)"
                >
                  Waive late charge
                </button>
                <button
                  type="button"
                  data-testid="claim-hold"
                  [attr.aria-label]="(c.held ? 'Release claim of ' : 'Hold claim of ') + c.amount + ' on ' + c.date"
                  (click)="toggleHold(c)"
                >
                  {{ c.held ? 'Release' : 'Hold' }}
                </button>
                <button
                  type="button"
                  data-testid="claim-defer"
                  [attr.aria-label]="'Defer claim of ' + c.amount + ' on ' + c.date"
                  (click)="defer(c)"
                >
                  Defer
                </button>
                <label class="visually-hidden" [for]="'claim-date-new-' + c.id">New date</label>
                <input
                  [id]="'claim-date-new-' + c.id"
                  type="date"
                  data-testid="claim-date-new"
                  [value]="redates()[c.id] ?? ''"
                  (input)="setRedate(c.id, value($event))"
                />
                <button
                  type="button"
                  data-testid="claim-redate"
                  [attr.aria-label]="'Re-date claim of ' + c.amount + ' on ' + c.date"
                  (click)="redate(c)"
                >
                  Re-date
                </button>
                <label class="visually-hidden" [for]="'claim-review-user-' + c.id">Assign review to user</label>
                <input
                  [id]="'claim-review-user-' + c.id"
                  data-testid="claim-review-user"
                  placeholder="Review by user"
                  [value]="reviewUsers()[c.id] ?? ''"
                  (input)="setReviewUser(c.id, value($event))"
                />
                <button
                  type="button"
                  data-testid="claim-review-assign"
                  [attr.aria-label]="'Assign review of claim of ' + c.amount + ' on ' + c.date"
                  (click)="assignReview(c)"
                >
                  Assign review
                </button>
                <label class="visually-hidden" [for]="'claim-reassign-to-' + c.id">Reassign to</label>
                <select
                  [id]="'claim-reassign-to-' + c.id"
                  data-testid="claim-reassign-to"
                  (change)="setReassignTo(c.id, value($event))"
                >
                  <option value="" [selected]="!reassignTo()[c.id]">Reassign to…</option>
                  @for (o of others(); track o.id) {
                    <option [value]="o.id" [selected]="reassignTo()[c.id] === '' + o.id">{{ o.name | fig }}</option>
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
                <label class="visually-hidden" [for]="'claim-reject-reason-' + c.id">Reason to reject</label>
                <input
                  [id]="'claim-reject-reason-' + c.id"
                  data-testid="claim-reject-reason"
                  placeholder="Reason to reject"
                  [value]="rejectReasons()[c.id] ?? ''"
                  (input)="setRejectReason(c.id, value($event))"
                />
                @if (voidReasons.list().length > 0) {
                  <label class="visually-hidden" [for]="'claim-void-reason-' + c.id">Reason to void</label>
                  <select
                    [id]="'claim-void-reason-' + c.id"
                    data-testid="claim-void-reason"
                    [value]="chosenVoidReasons()[c.id] ?? ''"
                    (change)="setVoidReason(c.id, value($event))"
                  >
                    <option value="" [selected]="!chosenVoidReasons()[c.id]">No void reason</option>
                    @for (r of voidReasons.list(); track r.id) {
                      <option [value]="r.reason" [selected]="chosenVoidReasons()[c.id] === r.reason">{{ r.reason | fig }}</option>
                    }
                  </select>
                }
                @if (actionError(); as e) {
                  @if (e.id === c.id) {
                    <p role="alert" data-testid="claim-error">{{ e.message | fig }}</p>
                  }
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
})
export class Claims implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);
  protected readonly voidReasons = inject(VoidReasons);
  protected readonly templates = inject(Templates);

  readonly personId = input.required<number>();
  readonly personName = input.required<string>();
  /** The person's claims, newest first. */
  readonly claims = input.required<Claim[]>();
  /** The person's grades, each from its from-date. */
  readonly grades = input.required<PersonGrade[]>();
  /** The sheet's rates: a claim's due uses the rate in force on the claim's date of the grade in force then. */
  readonly rates = input.required<Rate[]>();
  /** The sheet's periods: a claim belongs to the one whose dates contain its due-by. */
  readonly periods = input.required<Period[]>();
  /** The sheet's people, in the order they were added: a claim may be reassigned to any of the others. */
  readonly people = input<{ id: number; name: string }[]>([]);
  /** The date the person is retired from (yyyy-mm-dd); null while not retired. */
  readonly retiredFrom = input<string | null>(null);
  /** The person's past retirements: no claim can be dated after the from-date and before the to-date of one. */
  readonly retirements = input<{ from: string; to: string }[]>([]);
  /** The ids of the sheet's claims selected to act on together. */
  readonly selected = input<ReadonlySet<number>>(new Set());
  /** A claim's checkbox was ticked or cleared. */
  readonly selectToggled = output<{ id: number; checked: boolean }>();
  /** A claim was added, saved or changed status. */
  readonly saved = output<Claim>();

  protected readonly amount = signal('');
  protected readonly date = signal('');
  protected readonly note = signal('');
  /** The number of the settled claim a correction (a negative amount) corrects. */
  protected readonly corrects = signal('');
  /** The claim's external reference: blank for none. */
  protected readonly ref = signal('');
  /** The claim's tags, comma separated: blank for none. */
  protected readonly tags = signal('');
  /** The name of the template picked to add a claim from: blank for none. */
  protected readonly template = signal('');
  /** The id of the claim whose editor is open, or null when the form adds a claim. */
  protected readonly editing = signal<number | null>(null);
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;
  /** The lock the open editor is waiting for, settling to whether the store gave it; null once it answered. */
  private locking: Promise<boolean> | null = null;
  /** The error of the last status change refused, and the claim it belongs to. */
  protected readonly actionError = signal<{ id: number; message: string } | null>(null);
  protected readonly actions: { action: ClaimAction; label: string }[] = [
    { action: 'open', label: 'Open' },
    { action: 'settle', label: 'Settle' },
    { action: 'void', label: 'Void' },
    { action: 'approve', label: 'Approve' },
    { action: 'reject', label: 'Reject' },
    { action: 'resettle', label: 'Re-settle' },
    { action: 'delete', label: 'Delete' },
    { action: 'restore', label: 'Restore' },
    { action: 'review', label: 'Review' },
  ];
  /** The people other than this one, whom a claim may be reassigned to. */
  protected readonly others = computed(() => this.people().filter((p) => p.id !== this.personId()));
  /** The id (as text) of the person picked to reassign each claim to, by claim id. */
  protected readonly reassignTo = signal<Record<number, string>>({});
  /** The reason typed to reject each PENDING claim, by claim id. */
  protected readonly rejectReasons = signal<Record<number, string>>({});
  /** The void reason chosen for each claim from the sheet's list, by claim id. */
  protected readonly chosenVoidReasons = signal<Record<number, string>>({});
  /** The date (yyyy-mm-dd) picked to defer each OPEN claim until, by claim id. */
  protected readonly deferDates = signal<Record<number, string>>({});
  /** The date (yyyy-mm-dd) picked to settle each claim as of, by claim id; none: as it stands today. */
  protected readonly settleDates = signal<Record<number, string>>({});
  /** The kinds of proof offered. */
  protected readonly proofKinds = PROOF_KINDS;
  /** The proof kind chosen for each claim's next proof, by claim id; none chosen: a receipt. */
  protected readonly proofKindsChosen = signal<Record<number, string>>({});
  /** The new date (yyyy-mm-dd) picked to re-date each OPEN or PENDING claim to, by claim id. */
  protected readonly redates = signal<Record<number, string>>({});
  /** The user typed to assign each claim's review to, by claim id. */
  protected readonly reviewUsers = signal<Record<number, string>>({});
  /** The amount typed to split off each OPEN claim, by claim id. */
  protected readonly splitAmounts = signal<Record<number, string>>({});
  /** The amount typed to part-settle off each OPEN claim, by claim id. */
  protected readonly settlePartAmounts = signal<Record<number, string>>({});
  /** The comment typed for each claim, by claim id. */
  protected readonly commentTexts = signal<Record<number, string>>({});
  /** The timeline last shown for each claim, by claim id, with the claim as it was shown for. */
  protected readonly timelines = signal<Record<number, { of: Claim; lines: string[] }>>({});
  /** The new line typed for each DRAFT claim, by claim id. */
  protected readonly lineEntries = signal<Record<number, LineEntry>>({});
  /** The person picked (their id as text) and the share typed to share each claim with, by claim id. */
  protected readonly shareEntries = signal<Record<number, { personId: string; share: string }>>({});
  /** The new amount typed for each DRAFT claim in its row, by claim id. */
  protected readonly inlineAmounts = signal<Record<number, string>>({});

  constructor() {
    // A new today releases every editor's lock, so the open editor closes.
    let seenToday: string | null | undefined;
    effect(() => {
      const today = this.session.sheetToday();
      if (seenToday !== undefined && today !== seenToday) untracked(() => this.reset());
      seenToday = today;
    });
  }

  /** The editor the user left open (its claim still locked by them) is shown open again when the sheet is loaded. */
  ngOnInit(): void {
    const user = this.session.user();
    const mine = this.claims().find((c) => c.status === 'DRAFT' && user !== null && c.lockedBy === user);
    if (mine) this.fill(mine);
  }

  protected fieldId(field: string): string {
    return `claim-form-${field}-${this.personId()}`;
  }

  /** The due fixed when the claim was settled, else its amount times the rate on its date, or n/a with no rate. */
  /** The claim's tags as shown: sorted, comma and space separated. */
  protected tagsShown(claim: Claim): string {
    return tagsOf(claim).join(', ');
  }

  protected due(claim: Claim): string {
    return claimDue(claim, this.rates(), this.grades(), this.session);
  }

  /** Whether the claim is in REVIEW past the review SLA. */
  protected reviewLate(claim: Claim): boolean {
    return this.session.reviewLate(claim);
  }

  /** Whether the claim is OPEN and was due by (plus its grace) a day before the sheet's today. */
  protected overdue(claim: Claim): boolean {
    return overdue(claim, this.grades(), this.session);
  }

  /** Whether the claim is deferred on the sheet's today. */
  protected deferred(claim: Claim): boolean {
    return deferred(claim, this.session.today());
  }

  /** Whether the claim's deferral has not yet ended on the sheet's today, whatever its status now. */
  protected deferralRunning(claim: Claim): boolean {
    const today = this.session.today();
    return claim.deferredUntil !== null && today !== null && today < claim.deferredUntil;
  }

  /** The name of the period the claim's due-by falls in, or blank when none. */
  protected periodOf(claim: Claim): string {
    return periodOn(this.periods(), claim.dueBy)?.name ?? '';
  }

  /**
   * The from-date of the retirement that refuses a claim dated on the date: the current one when the date is after it,
   * or a past one when the date is after its from-date and before its to-date; null when none does.
   */
  private retiredBefore(date: string): string | null {
    const retiredFrom = this.retiredFrom();
    if (retiredFrom !== null && date > retiredFrom) return retiredFrom;
    return this.retirements().find((r) => date > r.from && date < r.to)?.from ?? null;
  }

  /** The due-by of the claim being edited, or blank when none is. */
  private editedDueBy(): string {
    return this.claims().find((c) => c.id === this.editing())?.dueBy ?? '';
  }

  /** Whether the claim being edited is shown as a DRAFT. */
  private editedDraft(): boolean {
    return this.claims().find((c) => c.id === this.editing())?.status === 'DRAFT';
  }

  /** The closed period the date (a claim's due-by) falls in, or undefined when none does. */
  private closedOn(date: string): Period | undefined {
    const period = periodOn(this.periods(), date);
    return period?.closed ? period : undefined;
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected checked(event: Event): boolean {
    return (event.target as HTMLInputElement).checked;
  }

  /**
   * Open the claim's editor, locking the claim against other users' edits until it is saved or cancelled. Refused,
   * leaving the editor as it was, for a viewer, a claim not DRAFT, one in a closed period and one another user has
   * open; the store decides again, and a refusal there puts the editor back as it was.
   */
  protected edit(claim: Claim): void {
    this.actionError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const refused = this.editRefusal(claim, null);
    if (refused !== null) {
      this.actionError.set({ id: claim.id, message: refused });
      return;
    }
    const was = this.editing();
    const form = { amount: this.amount(), date: this.date(), note: this.note(), corrects: this.corrects(), ref: this.ref(), tags: this.tags() };
    this.fill(claim);
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${claim.id}/lock`;
    const locking = firstValueFrom(this.http.post<Claim>(url, null, this.session.headers())).then(
      (locked) => {
        if (was !== null && was !== claim.id) this.release(sheet, was);
        if (this.session.sheet() === sheet) this.saved.emit(locked);
        return true;
      },
      (e: unknown) => {
        if (this.editing() === claim.id) {
          this.editing.set(was);
          this.amount.set(form.amount);
          this.date.set(form.date);
          this.note.set(form.note);
          this.corrects.set(form.corrects);
          this.ref.set(form.ref);
          this.tags.set(form.tags);
        }
        this.actionError.set({ id: claim.id, message: this.editRefusal(claim, e) ?? 'This claim could not be opened for editing.' });
        return false;
      },
    );
    this.locking = locking;
    void locking.finally(() => {
      if (this.locking === locking) this.locking = null;
    });
  }

  /** Why the claim's editor cannot be opened (with the store's refusal, when it gave one), or null when it can. */
  private editRefusal(claim: Claim, e: unknown): string | null {
    const user = this.session.user();
    const closed = this.closedOn(claim.dueBy);
    if (this.session.viewer() || (e instanceof HttpErrorResponse && e.status === 403)) {
      return 'A viewer may not edit a claim.';
    }
    if (claim.status !== 'DRAFT') {
      return `This claim is ${claim.status}: only a DRAFT can be edited. To change an open claim, void it and add a new one.`;
    }
    if (closed) return `This claim is due by a date in the closed period ${closed.name}: it cannot be edited.`;
    if ((claim.lockedBy !== null && claim.lockedBy !== user) || (e instanceof HttpErrorResponse && e.status === 423)) {
      return `${claim.lockedBy ?? 'Another user'} has this claim open in their editor: it cannot be edited until they save or cancel it.`;
    }
    return e === null ? null : 'This claim could not be opened for editing.';
  }

  /** Show the claim's values in the form, which then saves that claim. */
  private fill(claim: Claim): void {
    this.editing.set(claim.id);
    this.amount.set(claim.amount);
    this.date.set(claim.date);
    this.note.set(claim.note);
    this.corrects.set(claim.corrects ?? '');
    this.ref.set(claim.ref ?? '');
    this.tags.set(tagsOf(claim).join(', '));
    this.error.set(null);
  }

  /** Close the open editor and release its lock. */
  protected cancel(): void {
    const editing = this.editing();
    const sheet = this.session.sheet();
    this.reset();
    if (this.actionError()?.id === editing) this.actionError.set(null);
    if (editing !== null && sheet) this.release(sheet, editing);
  }

  /** Release the user's lock of the claim; the claim shows unlocked once the store answers. */
  private release(sheet: string, id: number): void {
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${id}/unlock`;
    firstValueFrom(this.http.post<Claim>(url, null, this.session.headers())).then(
      (claim) => {
        if (this.session.sheet() === sheet) this.saved.emit(claim);
      },
      () => undefined,
    );
  }

  protected reset(): void {
    this.editing.set(null);
    this.amount.set('');
    this.date.set('');
    this.note.set('');
    this.corrects.set('');
    this.ref.set('');
    this.tags.set('');
    this.error.set(null);
  }

  protected setRejectReason(id: number, reason: string): void {
    this.rejectReasons.update((all) => ({ ...all, [id]: reason }));
    if (this.actionError()?.id === id) this.actionError.set(null);
  }

  protected setVoidReason(id: number, reason: string): void {
    this.chosenVoidReasons.update((all) => ({ ...all, [id]: reason }));
    if (this.actionError()?.id === id) this.actionError.set(null);
  }

  protected setProofKind(id: number, kind: string): void {
    this.proofKindsChosen.update((all) => ({ ...all, [id]: kind }));
    if (this.actionError()?.id === id) this.actionError.set(null);
  }

  protected setSettleDate(id: number, date: string): void {
    this.settleDates.update((all) => ({ ...all, [id]: date }));
    if (this.actionError()?.id === id) this.actionError.set(null);
  }

  protected setDeferDate(id: number, date: string): void {
    this.deferDates.update((all) => ({ ...all, [id]: date }));
    if (this.actionError()?.id === id) this.actionError.set(null);
  }

  /**
   * Defer an OPEN claim until the date picked for it; the store refuses a claim not OPEN, a date not after the sheet's
   * today, and a closed period.
   */
  protected async defer(claim: Claim): Promise<void> {
    this.actionError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const until = (this.deferDates()[claim.id] ?? '').trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(until)) {
      this.actionError.set({ id: claim.id, message: 'Pick the date to defer this claim until.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${claim.id}/defer`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, { until }, this.session.headers()));
      this.setDeferDate(claim.id, '');
      if (this.session.sheet() === sheet) this.saved.emit(saved);
    } catch {
      const closed = this.closedOn(claim.dueBy);
      const today = this.session.sheetToday();
      const message =
        claim.status !== 'OPEN'
          ? `This claim is ${claim.status}: only an OPEN claim can be deferred.`
          : closed
            ? `This claim is due by a date in the closed period ${closed.name}: it cannot be deferred.`
            : today !== null && until <= today
              ? `A claim can only be deferred to a date after today (${today}).`
              : 'This claim could not be deferred.';
      this.actionError.set({ id: claim.id, message });
    }
  }

  protected setRedate(id: number, date: string): void {
    this.redates.update((all) => ({ ...all, [id]: date }));
    if (this.actionError()?.id === id) this.actionError.set(null);
  }

  /**
   * Re-date an OPEN or PENDING claim to the date picked for it; the store refuses any other status, a date after the
   * person's retirement, a claim in a closed period or a new date in one, and a user who is not a steward.
   */
  protected async redate(claim: Claim): Promise<void> {
    this.actionError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const date = (this.redates()[claim.id] ?? '').trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.actionError.set({ id: claim.id, message: 'Pick the new date for this claim.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${claim.id}/redate`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, { date }, this.session.headers()));
      this.setRedate(claim.id, '');
      if (this.session.sheet() === sheet) this.saved.emit(saved);
    } catch (e) {
      const closed = this.closedOn(claim.dueBy);
      const retiredFrom = this.retiredBefore(date);
      const message =
        e instanceof HttpErrorResponse && e.status === 403
          ? 'Only a steward can re-date a claim.'
          : claim.status !== 'OPEN' && claim.status !== 'PENDING'
            ? `This claim is ${claim.status}: only an OPEN or PENDING claim can be re-dated.`
            : closed
              ? `This claim is due by a date in the closed period ${closed.name}: it cannot be re-dated.`
              : retiredFrom !== null
                ? `${this.personName()} is retired from ${retiredFrom}: the claim cannot be dated after that.`
                : 'This claim could not be re-dated.';
      this.actionError.set({ id: claim.id, message });
    }
  }

  protected setReviewUser(id: number, user: string): void {
    this.reviewUsers.update((all) => ({ ...all, [id]: user }));
    if (this.actionError()?.id === id) this.actionError.set(null);
  }

  /** Assign a REVIEW claim's review to the user typed for it; the store refuses any other status and a user who is not a steward. */
  protected async assignReview(claim: Claim): Promise<void> {
    this.actionError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const user = (this.reviewUsers()[claim.id] ?? '').trim();
    if (!user) {
      this.actionError.set({ id: claim.id, message: 'Type the user to assign this review to.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${claim.id}/assign-review`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, { user }, this.session.headers()));
      this.setReviewUser(claim.id, '');
      if (this.session.sheet() === sheet) this.saved.emit(saved);
    } catch (e) {
      const message =
        e instanceof HttpErrorResponse && e.status === 403
          ? 'Only a steward can assign a review.'
          : claim.status !== 'REVIEW'
            ? `This claim is ${claim.status}: only a REVIEW claim can have its review assigned.`
            : 'The review of this claim could not be assigned.';
      this.actionError.set({ id: claim.id, message });
    }
  }

  /**
   * Waive the late charge of an OPEN or PENDING claim; the store refuses any other status, a claim already waived, a
   * claim in a closed period, and a user who is not a steward.
   */
  protected async waive(claim: Claim): Promise<void> {
    this.actionError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${claim.id}/waive`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, {}, this.session.headers()));
      if (this.session.sheet() === sheet) this.saved.emit(saved);
    } catch (e) {
      const closed = this.closedOn(claim.dueBy);
      const message =
        e instanceof HttpErrorResponse && e.status === 403
          ? 'Only a steward can waive a late charge.'
          : claim.status !== 'OPEN' && claim.status !== 'PENDING'
            ? `This claim is ${claim.status}: only an OPEN or PENDING claim can have its late charge waived.`
            : claim.waived
              ? 'The late charge of this claim is already waived.'
              : closed
                ? `This claim is due by a date in the closed period ${closed.name}: its late charge cannot be waived.`
                : 'The late charge of this claim could not be waived.';
      this.actionError.set({ id: claim.id, message });
    }
  }

  /** Hold an OPEN or PENDING claim, or release it when held; the store refuses any other status. */
  protected async toggleHold(claim: Claim): Promise<void> {
    this.actionError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${claim.id}/hold`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, {}, this.session.headers()));
      if (this.session.sheet() === sheet) this.saved.emit(saved);
    } catch {
      const message =
        claim.status !== 'OPEN' && claim.status !== 'PENDING'
          ? `This claim is ${claim.status}: only an OPEN or PENDING claim can be held or released.`
          : claim.held
            ? 'This claim could not be released.'
            : 'This claim could not be held.';
      this.actionError.set({ id: claim.id, message });
    }
  }

  /** The claim's holders as <name> <share>, comma and space separated; blank when it is not shared. */
  protected sharesShown(claim: Claim): string {
    return (claim.shares ?? [])
      .map((h) => `${this.people().find((p) => p.id === h.personId)?.name ?? ''} ${h.share}`)
      .join(', ');
  }

  protected setShareEntry(id: number, field: 'personId' | 'share', text: string): void {
    this.shareEntries.update((all) => ({ ...all, [id]: { ...(all[id] ?? { personId: '', share: '' }), [field]: text } }));
    if (this.actionError()?.id === id) this.actionError.set(null);
  }

  /**
   * Share the claim with the person picked, at the share typed (a percent, more than 0.00, two decimals); the store
   * refuses the claim's own person, a person already holding a share, shares totalling more than 100.00, and a
   * SETTLED, VOID or DELETED claim.
   */
  protected async share(claim: Claim): Promise<void> {
    this.actionError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const entry = this.shareEntries()[claim.id] ?? { personId: '', share: '' };
    const share = entry.share.trim();
    if (entry.personId === '') {
      this.actionError.set({ id: claim.id, message: 'Pick the person to share this claim with.' });
      return;
    }
    if (!/^\d{1,3}(\.\d{1,2})?$/.test(share) || !/[1-9]/.test(share)) {
      this.actionError.set({ id: claim.id, message: 'A share is a percent more than 0.00, with up to two decimals.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${claim.id}/shares`;
    try {
      const saved = await firstValueFrom(
        this.http.post<Claim>(url, { personId: Number(entry.personId), share }, this.session.headers()),
      );
      this.shareEntries.update((all) => ({ ...all, [claim.id]: { ...entry, share: '' } }));
      if (this.session.sheet() === sheet) this.saved.emit(saved);
    } catch {
      const holder = Number(entry.personId);
      const total = (claim.shares ?? []).reduce((t, h) => t + Math.round(Number(h.share) * 100), 0) + Math.round(Number(share) * 100);
      const message =
        claim.status === 'SETTLED' || claim.status === 'VOID' || claim.status === 'DELETED'
          ? `This claim is ${claim.status}: it cannot be shared.`
          : holder === claim.personId
            ? 'A claim cannot be shared with its own person.'
            : (claim.shares ?? []).some((h) => h.personId === holder)
              ? 'That person already holds a share of this claim.'
              : total > 10000
                ? 'The shares of this claim cannot total more than 100.00.'
                : 'This claim could not be shared.';
      this.actionError.set({ id: claim.id, message });
    }
  }

  /** Add a proof of the chosen kind to the claim; the store refuses a SETTLED or VOID claim, and a closed period. */
  protected async addProof(claim: Claim): Promise<void> {
    this.actionError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${claim.id}/proof`;
    try {
      const kind = this.proofKindsChosen()[claim.id] || null;
      const saved = await firstValueFrom(this.http.post<Claim>(url, { kind }, this.session.headers()));
      this.proofKindsChosen.update(({ [claim.id]: _, ...rest }) => rest);
      if (this.session.sheet() === sheet) this.saved.emit(saved);
    } catch {
      const closed = this.closedOn(claim.dueBy);
      const message =
        claim.status === 'SETTLED' || claim.status === 'VOID'
          ? `This claim is ${claim.status}: a proof cannot be added to it.`
          : closed
            ? `This claim is due by a date in the closed period ${closed.name}: a proof cannot be added to it.`
            : 'A proof could not be added to this claim.';
      this.actionError.set({ id: claim.id, message });
    }
  }

  /** Remind the claim; the store refuses a claim not OPEN or PENDING, and one already reminded on the sheet's today. */
  protected async remind(claim: Claim): Promise<void> {
    this.actionError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${claim.id}/remind`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, null, this.session.headers()));
      if (this.session.sheet() === sheet) this.saved.emit(saved);
    } catch {
      const message =
        claim.status !== 'OPEN' && claim.status !== 'PENDING'
          ? `This claim is ${claim.status}: only an OPEN or PENDING claim can be reminded.`
          : 'This claim could not be reminded: it may already have been reminded today.';
      this.actionError.set({ id: claim.id, message });
    }
  }

  protected setCommentText(id: number, text: string): void {
    this.commentTexts.update((all) => ({ ...all, [id]: text }));
    if (this.actionError()?.id === id) this.actionError.set(null);
  }

  /** Comment the claim; refused for an empty comment, and by the store like any change. */
  protected async addComment(claim: Claim): Promise<void> {
    this.actionError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const text = (this.commentTexts()[claim.id] ?? '').trim();
    if (text === '') {
      this.actionError.set({ id: claim.id, message: 'Type a comment: an empty one cannot be added.' });
      return;
    }
    if (text.length > 1000) {
      this.actionError.set({ id: claim.id, message: 'Type a comment of at most 1000 characters.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${claim.id}/comments`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, { text }, this.session.headers()));
      this.setCommentText(claim.id, '');
      if (this.session.sheet() === sheet) this.saved.emit(saved);
    } catch {
      this.actionError.set({ id: claim.id, message: 'This comment could not be added to the claim.' });
    }
  }

  /** The claim's timeline as last shown: none once the claim has been re-read, since it may have changed. */
  protected timelineOf(claim: Claim): string[] {
    const shown = this.timelines()[claim.id];
    return shown && shown.of === claim ? shown.lines : [];
  }

  /** Show the claim's timeline: the audit lines about it, newest first, as the audit file holds them. */
  protected async showTimeline(claim: Claim): Promise<void> {
    this.actionError.set(null);
    this.timelines.update((all) => {
      const { [claim.id]: _, ...rest } = all;
      return rest;
    });
    const sheet = this.session.sheet();
    if (!sheet) return;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${claim.id}/timeline`;
    try {
      const lines = await firstValueFrom(this.http.get<string[]>(url, this.session.headers()));
      if (this.session.sheet() === sheet) this.timelines.update((all) => ({ ...all, [claim.id]: { of: claim, lines } }));
    } catch {
      this.actionError.set({ id: claim.id, message: 'The timeline of this claim could not be read.' });
    }
  }

  protected setLineEntry(id: number, field: keyof LineEntry, text: string): void {
    this.lineEntries.update((all) => {
      const entry = all[id] ?? { label: '', qty: '', unit: '' };
      return { ...all, [id]: { ...entry, [field]: text } };
    });
    if (this.actionError()?.id === id) this.actionError.set(null);
  }

  /**
   * Add a line to a DRAFT claim, which counts as editing it: refused alike for a viewer, a claim not DRAFT, one in a
   * closed period and one another user has open, and for an empty label, a quantity that is not a whole number of at
   * least 1 and a unit amount with more than two decimals.
   */
  protected async addLine(claim: Claim): Promise<void> {
    this.actionError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const entry = this.lineEntries()[claim.id] ?? { label: '', qty: '', unit: '' };
    const label = entry.label.trim();
    const qty = entry.qty.trim();
    const unit = entry.unit.trim();
    if (label === '' || label.length > 255) {
      this.actionError.set({ id: claim.id, message: 'Type a label of at most 255 characters for the line.' });
      return;
    }
    if (!/^0*[1-9]\d{0,5}$/.test(qty)) {
      this.actionError.set({ id: claim.id, message: 'Type a whole number of at least 1 as the quantity.' });
      return;
    }
    if (!/^\d{1,10}(\.\d{1,2})?$/.test(unit)) {
      this.actionError.set({ id: claim.id, message: 'Type a unit amount with at most two decimals.' });
      return;
    }
    const refused = this.editRefusal(claim, null);
    if (refused !== null) {
      this.actionError.set({ id: claim.id, message: refused });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${claim.id}/lines`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, { label, qty, unit }, this.session.headers()));
      this.lineEntries.update((all) => ({ ...all, [claim.id]: { label: '', qty: '', unit: '' } }));
      if (this.session.sheet() === sheet) this.saved.emit(saved);
    } catch (e) {
      const message =
        e instanceof HttpErrorResponse && e.status === 422
          ? 'The lines of this claim would add up to more than a claim can hold.'
          : (this.editRefusal(claim, e) ?? 'This line could not be added to the claim.');
      this.actionError.set({ id: claim.id, message });
    }
  }

  protected setInlineAmount(id: number, amount: string): void {
    this.inlineAmounts.update((all) => ({ ...all, [id]: amount }));
    if (this.actionError()?.id === id) this.actionError.set(null);
  }

  /**
   * Save the amount typed in a DRAFT claim's row, keeping its date, note and correction: saved as the claim's editor
   * saves it, so refused alike for a viewer, a claim not DRAFT, one in a closed period and one another user has open.
   */
  protected async saveInline(claim: Claim): Promise<void> {
    this.actionError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const amount = (this.inlineAmounts()[claim.id] ?? '').trim();
    if (!/^-?\d+(\.\d{1,2})?$/.test(amount)) {
      this.actionError.set({
        id: claim.id,
        message: 'Type an amount with at most two decimals; a negative one is a correction.',
      });
      return;
    }
    const refused = this.editRefusal(claim, null);
    if (refused !== null) {
      this.actionError.set({ id: claim.id, message: refused });
      return;
    }
    if (claim.lines.length > 0 && Number(amount) !== Number(claim.amount)) {
      this.actionError.set({
        id: claim.id,
        message: `This claim has lines: its amount is ${claim.amount}, their quantities times their unit amounts added up.`,
      });
      return;
    }
    const body = { amount, date: claim.date, note: claim.note, corrects: claim.corrects, ref: claim.ref, tags: claim.tags };
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${claim.id}`;
    try {
      const saved = await firstValueFrom(this.http.put<Claim>(url, body, this.session.headers()));
      this.setInlineAmount(claim.id, '');
      // Saving releases the user's own lock, so an editor they had open on the claim closes.
      if (this.editing() === claim.id) this.reset();
      if (this.session.sheet() === sheet) this.saved.emit(saved);
    } catch (e) {
      const message =
        e instanceof HttpErrorResponse && e.status === 422
          ? 'A negative amount is a correction: it must name, by number, a settled claim of this person.'
          : (this.editRefusal(claim, e) ?? 'The claim could not be saved.');
      this.actionError.set({ id: claim.id, message });
    }
  }

  protected setSplitAmount(id: number, amount: string): void {
    this.splitAmounts.update((all) => ({ ...all, [id]: amount }));
    if (this.actionError()?.id === id) this.actionError.set(null);
  }

  /**
   * Split the amount typed for it off an OPEN claim into a new OPEN claim; the store refuses a claim not OPEN or
   * deferred, an amount not above 0.00 and below the claim's, a closed period, and a role that may not open a claim.
   */
  protected async split(claim: Claim): Promise<void> {
    this.actionError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const amount = (this.splitAmounts()[claim.id] ?? '').trim();
    if (!/^\d+(\.\d{1,2})?$/.test(amount)) {
      this.actionError.set({ id: claim.id, message: 'Type the amount to split off, with at most two decimals.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${claim.id}/split`;
    try {
      const added = await firstValueFrom(this.http.post<Claim>(url, { amount }, this.session.headers()));
      this.setSplitAmount(claim.id, '');
      if (this.session.sheet() === sheet) this.saved.emit(added);
    } catch (e) {
      const closed = this.closedOn(claim.dueBy);
      const message =
        e instanceof HttpErrorResponse && e.status === 403
          ? 'Your role may not open a claim, so it may not split one.'
          : claim.status !== 'OPEN'
            ? `This claim is ${claim.status}: only an OPEN claim can be split.`
            : this.deferred(claim)
              ? `This claim is deferred until ${claim.deferredUntil}: it cannot be split.`
              : closed
                ? `This claim is due by a date in the closed period ${closed.name}: it cannot be split.`
                : `The amount split off must be more than 0.00 and less than ${claim.amount}.`;
      this.actionError.set({ id: claim.id, message });
    }
  }

  protected setSettlePartAmount(id: number, amount: string): void {
    this.settlePartAmounts.update((all) => ({ ...all, [id]: amount }));
    if (this.actionError()?.id === id) this.actionError.set(null);
  }

  /**
   * Part-settle an OPEN claim: split the amount typed for it off into a new claim, settled at once by the user (PENDING
   * when the clerk limit says so). The store refuses the whole action when the split or the settle would be refused;
   * four eyes and the proof rule are judged on this claim.
   */
  protected async settlePart(claim: Claim): Promise<void> {
    this.actionError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const amount = (this.settlePartAmounts()[claim.id] ?? '').trim();
    if (!/^\d+(\.\d{1,2})?$/.test(amount)) {
      this.actionError.set({ id: claim.id, message: 'Type the amount to settle, with at most two decimals.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${claim.id}/settle-part`;
    try {
      const added = await firstValueFrom(this.http.post<Claim>(url, { amount }, this.session.headers()));
      this.setSettlePartAmount(claim.id, '');
      if (this.session.sheet() === sheet) this.saved.emit(added);
    } catch (e) {
      const closed = this.closedOn(claim.dueBy);
      const message =
        e instanceof HttpErrorResponse && e.status === 403
          ? 'Your role may not open and settle a claim, so it may not part-settle one.'
          : claim.status !== 'OPEN'
            ? `This claim is ${claim.status}: only an OPEN claim can be part-settled.`
            : claim.openedBy !== null && claim.openedBy === this.session.user()
              ? 'You opened this claim: someone else must settle any part of it.'
              : this.deferred(claim)
                ? `This claim is deferred until ${claim.deferredUntil}: it cannot be part-settled.`
                : closed
                  ? `This claim is due by a date in the closed period ${closed.name}: it cannot be part-settled.`
                  : `This claim cannot be part-settled: the amount must be more than 0.00 and less than ${claim.amount}, `
                    + 'and the claim must have the proofs it needs and not be last edited by you.';
      this.actionError.set({ id: claim.id, message });
    }
  }

  protected setReassignTo(id: number, personId: string): void {
    this.reassignTo.update((all) => ({ ...all, [id]: personId }));
    if (this.actionError()?.id === id) this.actionError.set(null);
  }

  /**
   * Move a DRAFT, OPEN or PENDING claim to the person picked for it; the store refuses a SETTLED or VOID claim, a
   * closed period, a person retired before its date, and a user who is not a steward.
   */
  protected async reassign(claim: Claim): Promise<void> {
    this.actionError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const to = this.others().find((p) => String(p.id) === this.reassignTo()[claim.id]);
    if (to === undefined) {
      this.actionError.set({ id: claim.id, message: 'Pick the person to reassign this claim to.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${claim.id}/reassign`;
    try {
      const saved = await firstValueFrom(this.http.post<Claim>(url, { personId: to.id }, this.session.headers()));
      this.setReassignTo(claim.id, '');
      if (this.session.sheet() === sheet) this.saved.emit(saved);
    } catch (e) {
      const closed = this.closedOn(claim.dueBy);
      const message =
        e instanceof HttpErrorResponse && e.status === 403
          ? 'Only a steward can reassign a claim.'
          : closed
            ? `This claim is due by a date in the closed period ${closed.name}: it cannot be reassigned.`
            : claim.status === 'SETTLED' || claim.status === 'VOID'
              ? `This claim is ${claim.status}: only a DRAFT, OPEN or PENDING claim can be reassigned.`
              : `This claim could not be reassigned to ${to.name}.`;
      this.actionError.set({ id: claim.id, message });
    }
  }

  /**
   * Open, settle, void, approve, reject, re-settle, delete, restore or review a claim; the store refuses a change its status, or the user's role, does not
   * allow. Rejecting takes the reason typed for the claim.
   */
  protected async act(claim: Claim, action: ClaimAction): Promise<void> {
    this.actionError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const reason = (this.rejectReasons()[claim.id] ?? '').trim();
    if (action === 'reject' && reason === '') {
      this.actionError.set({ id: claim.id, message: 'Type a reason to reject this claim.' });
      return;
    }
    const listed = this.voidReasons.list().map((r) => r.reason);
    const voidReason = this.chosenVoidReasons()[claim.id] ?? '';
    if (action === 'void' && listed.length > 0 && !listed.includes(voidReason)) {
      this.actionError.set({ id: claim.id, message: 'Choose a reason from the void reasons to void this claim.' });
      return;
    }
    const on = action === 'settle' ? (this.settleDates()[claim.id] ?? '').trim() : '';
    if (on !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(on)) {
      this.actionError.set({ id: claim.id, message: 'Pick a date to settle this claim as of, or none for today.' });
      return;
    }
    const url = `/api/sheets/${encodeURIComponent(sheet)}/claims/${claim.id}/${action}`;
    try {
      const body =
        action === 'reject'
          ? { reason }
          : action === 'void'
            ? { reason: listed.length > 0 ? voidReason : null }
            : action === 'settle' && on !== ''
              ? { on }
              : null;
      const saved = await firstValueFrom(this.http.post<Claim>(url, body, this.session.headers()));
      if (action === 'reject') this.setRejectReason(claim.id, '');
      if (action === 'settle') this.setSettleDate(claim.id, '');
      if (this.session.sheet() === sheet) this.saved.emit(saved);
    } catch (e) {
      const closed = this.closedOn(claim.dueBy);
      const capped = periodOn(this.periods(), claim.dueBy);
      const message =
        e instanceof HttpErrorResponse && e.status === 403
          ? action === 'settle'
            ? 'Whoever opened this claim cannot also settle it: another user must settle it.'
            : action === 'resettle'
            ? 'Only a steward can re-settle a claim.'
            : action === 'review'
            ? 'Only a steward can review a claim.'
            : action === 'void'
            ? claim.status === 'SETTLED'
              ? 'Only a steward can void a settled claim.'
              : 'Your role is not allowed to void a claim.'
            : 'Only a steward can approve or reject a pending claim, and not the one who opened it.'
          : closed
              ? `This claim is due by a date in the closed period ${closed.name}: it cannot be ${pastOf[action]}.`
              : e instanceof HttpErrorResponse && e.status === 422 && action === 'void'
                ? 'Choose a reason from the void reasons to void this claim.'
              : e instanceof HttpErrorResponse && e.status === 422 && (action === 'settle' || action === 'approve')
                ? `This claim's due is above the proof threshold: add a proof, and one of each of the sheet's proof kinds, before it can be ${pastOf[action]}.`
              : e instanceof HttpErrorResponse && e.status === 409 && action === 'open' && claim.status === 'DRAFT' && capped?.cap != null
                ? `This claim's person already has ${capped.cap} open claims in the period ${capped.name}, its cap: it cannot be opened.`
              : e instanceof HttpErrorResponse && e.status === 409 && action === 'settle' && on !== ''
                  && claim.status === 'OPEN' && !this.deferred(claim)
                ? `This claim cannot be settled as of ${on}: the date is after the sheet's today, or the claim did not exist then.`
              : action === 'settle' && this.deferred(claim)
                ? `This claim is deferred until ${claim.deferredUntil}: it cannot be settled before then.`
              : action === 'resettle'
                ? `This claim is ${claim.status}: only a SETTLED claim can be re-settled.`
              : action === 'void' && claim.status === 'SETTLED'
                ? 'A settled claim can only be voided within 7 days of its settlement.'
              : action === 'delete'
                ? `This claim is ${claim.status}: only a DRAFT claim can be deleted.`
              : action === 'restore' && claim.status === 'DELETED'
                ? 'A deleted claim can only be restored on the day it was deleted.'
              : action === 'restore'
                ? `This claim is ${claim.status}: only a DELETED claim can be restored.`
              : action === 'review'
                ? `This claim is ${claim.status}: only a claim in REVIEW can be reviewed.`
                : `This claim's status does not let it be ${pastOf[action]}.`;
      this.actionError.set({ id: claim.id, message });
    }
  }

  /**
   * Add a DRAFT claim with the picked template's amount and note now, dated as a claim with no date; the store refuses
   * it by every rule of the claim form, and the form shows why.
   */
  protected async addFromTemplate(): Promise<void> {
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const name = this.template();
    if (name === '') {
      this.error.set('Pick a template to add a claim from.');
      return;
    }
    this.submitting = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<Claim>(
          `/api/sheets/${encodeURIComponent(sheet)}/people/${this.personId()}/claims/from-template`,
          { name },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() === sheet) this.saved.emit(saved);
    } catch (e) {
      const dated = this.session.newClaimDate() ?? '';
      const retiredFrom = this.retiredBefore(dated);
      const why =
        e instanceof HttpErrorResponse && e.status === 404
          ? ` There is no template ${name}.`
          : retiredFrom !== null
            ? ` ${this.personName()} is retired from ${retiredFrom}.`
            : '';
      this.error.set('The claim could not be added from the template.' + why);
    } finally {
      this.submitting = false;
    }
  }

  protected async submit(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    // A save waits for the editor's lock; a refused lock saves nothing (the claim shows why).
    if (this.locking !== null) {
      this.submitting = true;
      const locked = await this.locking;
      this.submitting = false;
      if (!locked) return;
    }
    const sheet = this.session.sheet();
    if (!sheet) return;
    const amount = this.amount().trim();
    if (!/^-?\d+(\.\d{1,2})?$/.test(amount)) {
      this.error.set('Type an amount with at most two decimals; a negative one is a correction.');
      return;
    }
    const lined = this.claims().find((c) => c.id === this.editing() && c.lines.length > 0);
    if (lined && Number(amount) !== Number(lined.amount)) {
      this.error.set(`This claim has lines: its amount is ${lined.amount}, their quantities times their unit amounts added up.`);
      return;
    }
    const corrects = this.corrects().trim();
    if (corrects.length > 255) {
      this.error.set('Type the number of the settled claim the correction corrects.');
      return;
    }
    const ref = this.ref().trim();
    if (ref.length > 255) {
      this.error.set('Type a reference of at most 255 characters.');
      return;
    }
    const tags = this.tags().trim();
    if (tags.length > 1000) {
      this.error.set('Type tags of at most 1000 characters in all.');
      return;
    }
    const date = this.date().trim();
    if (date && !/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.error.set('Pick a date for the claim, or leave it blank for today.');
      return;
    }
    const body = { amount, date: date || null, note: this.note(), corrects: corrects || null, ref: ref || null, tags: tags || null };
    const editing = this.editing();
    const base = `/api/sheets/${encodeURIComponent(sheet)}`;
    this.submitting = true;
    try {
      const saved = await firstValueFrom(
        editing === null
          ? this.http.post<Claim>(`${base}/people/${this.personId()}/claims`, body, this.session.headers())
          : this.http.put<Claim>(`${base}/claims/${editing}`, body, this.session.headers()),
      );
      if (this.session.sheet() === sheet) {
        this.reset();
        this.saved.emit(saved);
      }
    } catch (e) {
      const dated = date || ((editing === null ? this.session.newClaimDate() : this.session.sheetToday()) ?? '');
      const closed = editing === null ? undefined : this.closedOn(this.editedDueBy());
      const retiredFrom = this.retiredBefore(dated);
      const why = closed
        ? ` It is due by a date in the closed period ${closed.name}.`
        : retiredFrom !== null
          ? ` ${this.personName()} is retired from ${retiredFrom}.`
          : ref && e instanceof HttpErrorResponse && e.status === 409 && (editing === null || this.editedDraft())
          ? ` The reference ${ref} is already used on the sheet.`
          : editing !== null && e instanceof HttpErrorResponse && e.status === 409
          ? ' Only a DRAFT claim can be edited.'
          : editing !== null && e instanceof HttpErrorResponse && e.status === 423
          ? ' Another user has this claim open in their editor.'
          : e instanceof HttpErrorResponse && e.status === 422
          ? ' A negative amount is a correction: it must name, by number, a settled claim of this person.'
          : '';
      this.error.set((editing === null ? 'The claim could not be added.' : 'The claim could not be saved.') + why);
    } finally {
      this.submitting = false;
    }
  }
}

const pastOf: Record<ClaimAction, string> = {
  open: 'opened',
  settle: 'settled',
  void: 'voided',
  approve: 'approved',
  reject: 'rejected',
  resettle: 're-settled',
  delete: 'deleted',
  restore: 'restored',
  review: 'reviewed',
};
