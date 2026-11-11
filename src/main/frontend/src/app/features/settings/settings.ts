import { HttpClient } from '@angular/common/http';
import { Component, computed, effect, inject, model, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { DatedValue, START_FLOOR, START_LATE_PCT, valueOn } from '../rates/rates';
import { FigurePipe } from '../../core/figure';

// The sheet's settings: its clerk limit (two decimals; blank for no limit), and its floor (two
// decimals; 1.00 on a new sheet), its terms (whole days; 14 on a new sheet) and its late percent (two decimals; 0.00 on a new sheet). A
// clerk settles a claim only when its due is at or below the limit; a claim's live due is never below the floor; a claim is due by its date
// plus the terms of its person's grade (fixed when the claim is added); an overdue claim's due gains, for each full 30 days overdue, the late percent in force on that boundary date of its figure. Saving saves the clerk limit
// once its field has been entered (blank: no limit), and the floor, terms and late percent when typed (blank: left as they are).
// The policy date (blank: the sheet's today) decides how a claim's live figures round: half up when dated before it, half to even
// on or after it; it saves, like the clerk limit, once its field has been entered (blank: none).
// Period numbering from (blank: off): while the sheet's today is on or after it, a claim opened is
// numbered within its period (<period name>-<n>, or X-<n> in no period); it saves like the policy date.
// Each grade may have its own terms (whole days; blank: left as they are); a grade without its own
// uses the sheet's terms. A claim's due-by is fixed when it is added, so changing terms moves none.
// The cap (two decimals; blank for none) is the most a person's due-total can be; it saves, like
// the clerk limit, once its field has been entered (blank: none).
// The settings that carry a figure (the clerk limit, the floor, the terms, each grade's terms, the
// late percent and the cap) are dated: each saves its value from the date in its from field (blank:
// the sheet's today), keeping the values saved before (a value saved from the same date is
// replaced). A claim's floor and terms are the ones in force on its date, its late percents the ones in force on its 30-day boundaries; the clerk
// limit and the cap are the ones in force on the sheet's today. Each shows the value in force on
// the sheet's today: before its first from-date the floor is 1.00, the late percent 0.00 and the
// terms 14; a grade's terms, the clerk limit and the cap show nothing. The policy date and period
// numbering from are not dated.
// The proof threshold (two decimals; blank for none) is dated like the floor: a claim whose due is
// above the one in force on its date cannot be settled or approved while it has no proofs. It
// saves, like the clerk limit, once its field has been entered (blank: none).
// Each grade may have its own proof threshold (two decimals; blank: none), dated like the floor: a
// claim's threshold is its person's grade's on its date when it has one then, else the sheet's.
// Each saves, like the clerk limit, once its field has been entered.
// Each grade may have its own clerk limit (two decimals; blank: none), dated like the floor: the
// limit a claim settles under is its person's grade's on the sheet's today when one is set, else
// the sheet's. Each saves, like the clerk limit, once its field has been entered.
// The note threshold (two decimals; blank for none) is dated like the floor: a claim whose due is
// above the one in force on its date cannot be opened while its note is empty. It saves, like the
// clerk limit, once its field has been entered (blank: none).
// The review threshold (two decimals; blank for none) is dated like the floor: a claim whose due is
// above the one in force on its date goes to REVIEW when opened, instead of OPEN, until a steward
// reviews it. It saves, like the clerk limit, once its field has been entered (blank: none).
// The permission matrix: for each of open, settle, void, reassign, close and reopen, the roles that
// may do it, comma separated (blank: the standing rule, open, settle, void and close by clerk and
// steward, reassign and reopen by steward). Each saves, like the clerk limit, once its field has
// been entered; it is not dated.
// The escalation gap (a whole number of days; blank: off): reminding a claim whose previous reminder
// was at least that many days before the sheet's today escalates it, once ever. It saves, like the
// alert threshold, once its field has been entered; it is not dated.
// The score thresholds, mid and high (whole numbers, below zero too; blank: none): while both are
// set, each person shows a band by their score. Each saves, like the escalation gap, once its field
// has been entered; neither is dated.
// The review SLA (a whole number of days; blank: none): a REVIEW claim whose review day plus that
// many days is before the sheet's today is late for review. It saves, like the escalation gap, once
// its field has been entered; it is not dated.
// Grace days (whole days; blank: off): when the sheet's today changes, every open period whose
// to-date plus the grace days is before the new today is closed. It saves, like the clerk limit,
// once its field has been entered; it is not dated.
// Each grade may have grace days (whole days; blank: none), dated like the floor: a claim whose
// person's grade on its date has grace days in force on that date is overdue only once its due-by
// plus them is before the sheet's today, and its late charge's 30-day boundaries count from there;
// its due-by is unchanged. Each saves, like the clerk limit, once its field has been entered.
// The entry date (blank: off): while set, it is the default date of a new claim in place of the
// sheet's today. It saves like the policy date; it is not dated.
// The pot (an amount with up to two decimals; blank: none): shared out over each balanced pool's
// members as their cuts. It saves, like the clerk limit, once its field has been entered; it is not
// dated.
// The alert threshold (an amount with up to two decimals; blank: off): a change that lifts a
// person's due-total above it is audited as an alert. It saves, like the pot, once its field has
// been entered; it is not dated.
// The fiscal start month (1 to 12; 1 on a new sheet): the month each fiscal year starts in. It
// saves when typed (blank: left as it is); it is not dated.
// The parent (a sheet id; blank: none): a sheet already loaded, never this sheet nor one that has it
// as an ancestor. A grade with no rate in force on a date here takes the parent's rate for it then,
// then the parent's parent's, up to three levels up. It saves, like the clerk limit, once its field
// has been entered; it is not dated.
// The rounding mode (half-up or half-even; blank: none) is dated like the floor: a claim whose date
// has one in force rounds its live figures by it in place of the policy date. It saves, like the cap,
// once its field has been entered (blank: none).
/** A value saved for a dated setting, by key: blank from for the beginning of time, blank value for none. */
interface DatedSetting extends DatedValue {
  key: string;
}

interface SettingsResponse {
  /** The clerk limit, blank for none. */
  clerkLimit: string;
  floor: string;
  terms: number;
  latePct: string;
  /** The policy date, blank for none (the sheet's today). */
  policyDate: string;
  /** The period-numbering-from date, blank for off. */
  periodNumberingFrom: string;
  /** The terms set for each grade that has its own, by grade. */
  gradeTerms: Record<string, number>;
  /** The cap, blank for none. */
  cap: string;
  /** The proof threshold, blank for none. */
  proofThreshold: string;
  /** The note threshold, blank for none. */
  noteThreshold: string;
  /** The review threshold, blank for none. */
  reviewThreshold: string;
  /** Every value saved for the dated settings, by key and from-date. */
  dated: DatedSetting[];
  /** The permission matrix: for each action, its roles comma separated, blank for the standing rule. */
  permissions: Partial<Record<PermAction, string>>;
  /** The grace days, blank for off. */
  graceDays: string;
  /** The entry date, blank for off. */
  entryDate: string;
  /** The pot, blank for none. */
  pot: string;
  /** The parent sheet's id, blank for none. */
  parent: string;
  /** The month (1 to 12) the sheet's fiscal year starts in. */
  fiscalStartMonth: number;
  /** The alert threshold, blank for off. */
  alertThreshold: string;
  /** The rounding mode in force on the sheet's today, blank for none. */
  rounding: string;
  /** The escalation gap in days, blank for off. */
  escalateAfter: string;
  /** The review SLA in days, blank for none. */
  reviewSla: string;
  /** The most claims a person may have OPEN or PENDING at once, blank for none. */
  maxOpen: string;
  /** The pending SLA in days, blank for none. */
  pendingSla: string;
  /** The mid score threshold, blank for none. */
  scoreMid: string;
  /** The high score threshold, blank for none. */
  scoreHigh: string;
  /** The score log switch: on, or blank for off. */
  scoreLog: string;
  /** The proof kinds a claim above the proof threshold needs one of each of, comma separated, blank for none. */
  proofKinds: string;
  /** The grade a new person takes when none is given, blank for none (A). */
  defaultGrade: string;
}

/** A score threshold: a whole number, below zero too. */
const SCORE = /^-?\d{1,6}$/;

/** Proof kinds: receipt or photo, comma separated. */
const PROOF_KINDS = /^(receipt|photo)(\s*,\s*(receipt|photo))*$/i;

/** The actions the permission matrix covers. */
const PERM_ACTIONS = ['open', 'settle', 'void', 'reassign', 'close', 'reopen'] as const;
type PermAction = (typeof PERM_ACTIONS)[number];

/** A permission matrix entry: roles comma separated. */
const ROLES = /^\s*(clerk|steward)(\s*,\s*(clerk|steward))*\s*$/i;

/** The grades that may have their own terms, grace days, proof threshold and clerk limit. */
const GRADES = ['A', 'B', 'C'] as const;
type Grade = (typeof GRADES)[number];

/** A limit or floor: 0.00 or more, with up to two decimals. */
const LIMIT = /^\d{1,12}(\.\d{1,2})?$/;


/** Terms: a whole number of days. */
const TERMS = /^\d{1,4}$/;

const START_TERMS = '14';

/** A late percent: 0.00 to 999.99, with up to two decimals. */
const PCT = /^\d{1,3}(\.\d{1,2})?$/;


/** The keys of the dated settings, each with its own from field. */
const DATED_KEYS = ['clerk-limit', 'clerk-limit-A', 'clerk-limit-B', 'clerk-limit-C', 'floor', 'terms', 'terms-A', 'terms-B', 'terms-C', 'grace-A', 'grace-B', 'grace-C', 'late-pct', 'cap', 'proof-threshold', 'proof-threshold-A', 'proof-threshold-B', 'proof-threshold-C', 'note-threshold', 'review-threshold', 'rounding'] as const;
type DatedKey = (typeof DATED_KEYS)[number];

/** A rounding mode. */
const ROUNDING = /^(half-up|half-even)$/;

/** A policy date: yyyy-mm-dd. */
const DATE = /^\d{4}-\d{2}-\d{2}$/;

@Component({
  selector: 'app-settings',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="settings-heading">
      <h2 id="settings-heading">Settings</h2>
      <form aria-label="Settings" (submit)="save($event)">
        <label for="setting-clerk-limit">Clerk limit (blank: no limit)</label>
        <input
          id="setting-clerk-limit"
          data-testid="setting-clerk-limit"
          inputmode="decimal"
          [value]="limitInput() ?? ''"
          (focus)="enterLimit($any($event.target))"
          (input)="limitInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-clerk-limit-from">Clerk limit in force from (blank: the sheet's today)</label>
        <input
          id="setting-clerk-limit-from"
          data-testid="setting-clerk-limit-from"
          type="date"
          [value]="fromInput()['clerk-limit'] ?? ''"
          (input)="typeFrom('clerk-limit', $any($event.target).value)"
        />
        @for (g of grades; track g) {
          <label [for]="'setting-clerk-limit-' + g">Clerk limit for grade {{ g }} (blank: none, the sheet's)</label>
          <input
            [id]="'setting-clerk-limit-' + g"
            [attr.data-testid]="'setting-clerk-limit-' + g"
            inputmode="decimal"
            [value]="gradeLimitInput()[g] ?? ''"
            (focus)="enterGradeLimit(g, $any($event.target))"
            (input)="typeGradeLimit(g, $any($event.target).value)"
          />
          <label [for]="'setting-clerk-limit-' + g + '-from'">Clerk limit for grade {{ g }} in force from (blank: the sheet's today)</label>
          <input
            [id]="'setting-clerk-limit-' + g + '-from'"
            [attr.data-testid]="'setting-clerk-limit-' + g + '-from'"
            type="date"
            [value]="fromInput()[limitKey(g)] ?? ''"
            (input)="typeFrom(limitKey(g), $any($event.target).value)"
          />
        }
        <label for="setting-floor">Floor (blank: leave as it is)</label>
        <input
          id="setting-floor"
          data-testid="setting-floor"
          inputmode="decimal"
          [value]="floorInput() ?? ''"
          (input)="floorInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-floor-from">Floor in force from (blank: the sheet's today)</label>
        <input
          id="setting-floor-from"
          data-testid="setting-floor-from"
          type="date"
          [value]="fromInput()['floor'] ?? ''"
          (input)="typeFrom('floor', $any($event.target).value)"
        />
        <label for="setting-terms">Terms in days (blank: leave as they are)</label>
        <input
          id="setting-terms"
          data-testid="setting-terms"
          inputmode="numeric"
          [value]="termsInput() ?? ''"
          (input)="termsInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-terms-from">Terms in force from (blank: the sheet's today)</label>
        <input
          id="setting-terms-from"
          data-testid="setting-terms-from"
          type="date"
          [value]="fromInput()['terms'] ?? ''"
          (input)="typeFrom('terms', $any($event.target).value)"
        />
        @for (g of grades; track g) {
          <label [for]="'setting-terms-' + g">Terms in days for grade {{ g }} (blank: leave as they are)</label>
          <input
            [id]="'setting-terms-' + g"
            [attr.data-testid]="'setting-terms-' + g"
            inputmode="numeric"
            [value]="gradeTermsInput()[g] ?? ''"
            (input)="typeGradeTerms(g, $any($event.target).value)"
          />
          <label [for]="'setting-terms-' + g + '-from'">Terms for grade {{ g }} in force from (blank: the sheet's today)</label>
          <input
            [id]="'setting-terms-' + g + '-from'"
            [attr.data-testid]="'setting-terms-' + g + '-from'"
            type="date"
            [value]="fromInput()[gradeKey(g)] ?? ''"
            (input)="typeFrom(gradeKey(g), $any($event.target).value)"
          />
        }
        @for (g of grades; track g) {
          <label [for]="'setting-grace-' + g">Grace days for grade {{ g }} before a claim is overdue (blank: none)</label>
          <input
            [id]="'setting-grace-' + g"
            [attr.data-testid]="'setting-grace-' + g"
            inputmode="numeric"
            [value]="gradeGraceInput()[g] ?? ''"
            (focus)="enterGradeGrace(g, $any($event.target))"
            (input)="typeGradeGrace(g, $any($event.target).value)"
          />
          <label [for]="'setting-grace-' + g + '-from'">Grace days for grade {{ g }} in force from (blank: the sheet's today)</label>
          <input
            [id]="'setting-grace-' + g + '-from'"
            [attr.data-testid]="'setting-grace-' + g + '-from'"
            type="date"
            [value]="fromInput()[graceKey(g)] ?? ''"
            (input)="typeFrom(graceKey(g), $any($event.target).value)"
          />
        }
        <label for="setting-late-pct">Late percent per 30 days overdue (blank: leave as it is)</label>
        <input
          id="setting-late-pct"
          data-testid="setting-late-pct"
          inputmode="decimal"
          [value]="latePctInput() ?? ''"
          (input)="latePctInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-late-pct-from">Late percent in force from (blank: the sheet's today)</label>
        <input
          id="setting-late-pct-from"
          data-testid="setting-late-pct-from"
          type="date"
          [value]="fromInput()['late-pct'] ?? ''"
          (input)="typeFrom('late-pct', $any($event.target).value)"
        />
        <label for="setting-policy-date">Policy date (blank: the sheet's today)</label>
        <input
          id="setting-policy-date"
          data-testid="setting-policy-date"
          type="date"
          [value]="policyInput() ?? ''"
          (focus)="enterPolicy($any($event.target))"
          (input)="policyInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-period-numbering-from">Period numbering from (blank: off)</label>
        <input
          id="setting-period-numbering-from"
          data-testid="setting-period-numbering-from"
          type="date"
          [value]="numberingInput() ?? ''"
          (focus)="enterNumbering($any($event.target))"
          (input)="numberingInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-cap">Cap on a person's due-total (blank: no cap)</label>
        <input
          id="setting-cap"
          data-testid="setting-cap"
          inputmode="decimal"
          [value]="capInput() ?? ''"
          (focus)="enterCap($any($event.target))"
          (input)="capInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-cap-from">Cap in force from (blank: the sheet's today)</label>
        <input
          id="setting-cap-from"
          data-testid="setting-cap-from"
          type="date"
          [value]="fromInput()['cap'] ?? ''"
          (input)="typeFrom('cap', $any($event.target).value)"
        />
        <label for="setting-rounding">Rounding mode, half-up or half-even (blank: none, by the policy date)</label>
        <input
          id="setting-rounding"
          data-testid="setting-rounding"
          [value]="roundingInput() ?? ''"
          (focus)="enterRounding($any($event.target))"
          (input)="roundingInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-rounding-from">Rounding mode in force from (blank: the sheet's today)</label>
        <input
          id="setting-rounding-from"
          data-testid="setting-rounding-from"
          type="date"
          [value]="fromInput()['rounding'] ?? ''"
          (input)="typeFrom('rounding', $any($event.target).value)"
        />
        <label for="setting-proof-threshold">Proof threshold (blank: none)</label>
        <input
          id="setting-proof-threshold"
          data-testid="setting-proof-threshold"
          inputmode="decimal"
          [value]="thresholdInput() ?? ''"
          (focus)="enterThreshold($any($event.target))"
          (input)="thresholdInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-proof-threshold-from">Proof threshold in force from (blank: the sheet's today)</label>
        <input
          id="setting-proof-threshold-from"
          data-testid="setting-proof-threshold-from"
          type="date"
          [value]="fromInput()['proof-threshold'] ?? ''"
          (input)="typeFrom('proof-threshold', $any($event.target).value)"
        />
        @for (g of grades; track g) {
          <label [for]="'setting-proof-threshold-' + g">Proof threshold for grade {{ g }} (blank: none, the sheet's)</label>
          <input
            [id]="'setting-proof-threshold-' + g"
            [attr.data-testid]="'setting-proof-threshold-' + g"
            inputmode="decimal"
            [value]="gradeThresholdInput()[g] ?? ''"
            (focus)="enterGradeThreshold(g, $any($event.target))"
            (input)="typeGradeThreshold(g, $any($event.target).value)"
          />
          <label [for]="'setting-proof-threshold-' + g + '-from'">Proof threshold for grade {{ g }} in force from (blank: the sheet's today)</label>
          <input
            [id]="'setting-proof-threshold-' + g + '-from'"
            [attr.data-testid]="'setting-proof-threshold-' + g + '-from'"
            type="date"
            [value]="fromInput()[thresholdKey(g)] ?? ''"
            (input)="typeFrom(thresholdKey(g), $any($event.target).value)"
          />
        }
        <label for="setting-note-threshold">Note threshold (blank: none)</label>
        <input
          id="setting-note-threshold"
          data-testid="setting-note-threshold"
          inputmode="decimal"
          [value]="noteThresholdInput() ?? ''"
          (focus)="enterNoteThreshold($any($event.target))"
          (input)="noteThresholdInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-note-threshold-from">Note threshold in force from (blank: the sheet's today)</label>
        <input
          id="setting-note-threshold-from"
          data-testid="setting-note-threshold-from"
          type="date"
          [value]="fromInput()['note-threshold'] ?? ''"
          (input)="typeFrom('note-threshold', $any($event.target).value)"
        />
        <label for="setting-review-threshold">Review threshold (blank: none)</label>
        <input
          id="setting-review-threshold"
          data-testid="setting-review-threshold"
          inputmode="decimal"
          [value]="reviewThresholdInput() ?? ''"
          (focus)="enterReviewThreshold($any($event.target))"
          (input)="reviewThresholdInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-review-threshold-from">Review threshold in force from (blank: the sheet's today)</label>
        <input
          id="setting-review-threshold-from"
          data-testid="setting-review-threshold-from"
          type="date"
          [value]="fromInput()['review-threshold'] ?? ''"
          (input)="typeFrom('review-threshold', $any($event.target).value)"
        />
        <p>
        Parent sheet:
        <strong data-testid="setting-parent-current">{{ session.parent() }}</strong>
        @if (session.parent() === '') {
          <span>(none)</span>
        }
      </p>
      @for (a of permActions; track a) {
          <label [for]="'setting-perm-' + a">Roles that may {{ a }}, comma separated (blank: the standing rule)</label>
          <input
            [id]="'setting-perm-' + a"
            [attr.data-testid]="'setting-perm-' + a"
            [value]="permInput()[a] ?? ''"
            (focus)="enterPerm(a, $any($event.target))"
            (input)="typePerm(a, $any($event.target).value)"
          />
        }
        <label for="setting-grace-days">Grace days before a period closes itself (blank: off)</label>
        <input
          id="setting-grace-days"
          data-testid="setting-grace-days"
          inputmode="numeric"
          [value]="graceInput() ?? ''"
          (focus)="enterGrace($any($event.target))"
          (input)="graceInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-entry-date">Entry date for new claims (blank: off)</label>
        <input
          id="setting-entry-date"
          data-testid="setting-entry-date"
          type="date"
          [value]="entryInput() ?? ''"
          (focus)="enterEntry($any($event.target))"
          (input)="entryInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-pot">Pot shared out over the pools (blank: none)</label>
        <input
          id="setting-pot"
          data-testid="setting-pot"
          inputmode="decimal"
          [value]="potInput() ?? ''"
          (focus)="enterPot($any($event.target))"
          (input)="potInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-alert-threshold">Alert when a due-total rises above (blank: off)</label>
        <input
          id="setting-alert-threshold"
          data-testid="setting-alert-threshold"
          inputmode="decimal"
          [value]="alertInput() ?? ''"
          (focus)="enterAlert($any($event.target))"
          (input)="alertInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-escalate-after">Escalate a reminder this many days after the previous one (blank: off)</label>
        <input
          id="setting-escalate-after"
          data-testid="setting-escalate-after"
          inputmode="numeric"
          [value]="escalateInput() ?? ''"
          (focus)="enterEscalate($any($event.target))"
          (input)="escalateInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-score-mid">Score at or above which a person is MID (blank: none)</label>
        <input
          id="setting-score-mid"
          data-testid="setting-score-mid"
          inputmode="numeric"
          [value]="scoreMidInput() ?? ''"
          (focus)="enterScoreMid($any($event.target))"
          (input)="scoreMidInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-score-high">Score at or above which a person is HIGH (blank: none)</label>
        <input
          id="setting-score-high"
          data-testid="setting-score-high"
          inputmode="numeric"
          [value]="scoreHighInput() ?? ''"
          (focus)="enterScoreHigh($any($event.target))"
          (input)="scoreHighInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-score-log">Log each person's score when today changes (on, or blank: off)</label>
        <input
          id="setting-score-log"
          data-testid="setting-score-log"
          [value]="scoreLogInput() ?? ''"
          (focus)="enterScoreLog($any($event.target))"
          (input)="scoreLogInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-review-sla">Days a claim may wait in review before it is late (blank: none)</label>
        <input
          id="setting-review-sla"
          data-testid="setting-review-sla"
          inputmode="numeric"
          [value]="reviewSlaInput() ?? ''"
          (focus)="enterReviewSla($any($event.target))"
          (input)="reviewSlaInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-pending-sla">Days a claim may wait pending before it is late (blank: none)</label>
        <input
          id="setting-pending-sla"
          data-testid="setting-pending-sla"
          inputmode="numeric"
          [value]="pendingSlaInput() ?? ''"
          (focus)="enterPendingSla($any($event.target))"
          (input)="pendingSlaInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-max-open">Most open or pending claims a person may have (blank: none)</label>
        <input
          id="setting-max-open"
          data-testid="setting-max-open"
          inputmode="numeric"
          [value]="maxOpenInput() ?? ''"
          (focus)="enterMaxOpen($any($event.target))"
          (input)="maxOpenInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-proof-kinds">Proof kinds a claim above the proof threshold needs, comma separated: receipt, photo (blank: none)</label>
        <input
          id="setting-proof-kinds"
          data-testid="setting-proof-kinds"
          [value]="proofKindsInput() ?? ''"
          (focus)="enterProofKinds($any($event.target))"
          (input)="proofKindsInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-default-grade">Grade a new person takes when none is given: one of the sheet's grades (blank: A)</label>
        <input
          id="setting-default-grade"
          data-testid="setting-default-grade"
          [value]="defaultGradeInput() ?? ''"
          (focus)="enterDefaultGrade($any($event.target))"
          (input)="defaultGradeInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-parent">Parent sheet whose rates stand in for missing ones (blank: none)</label>
        <input
          id="setting-parent"
          data-testid="setting-parent"
          [value]="parentInput() ?? ''"
          (focus)="enterParent($any($event.target))"
          (input)="parentInput.set($any($event.target).value); error.set(null)"
        />
        <label for="setting-fiscal-start-month">Month the fiscal year starts in, 1 to 12 (blank: left as it is)</label>
        <input
          id="setting-fiscal-start-month"
          data-testid="setting-fiscal-start-month"
          inputmode="numeric"
          [value]="fiscalInput() ?? ''"
          (input)="fiscalInput.set($any($event.target).value); error.set(null)"
        />
        <button type="submit" data-testid="settings-save">Save settings</button>
        @if (error(); as err) {
          <p role="alert" data-testid="settings-error">{{ err }}</p>
        }
      </form>
      <p>
        Clerk limit in force:
        <strong data-testid="setting-clerk-limit-current">{{ limit() | figure }}</strong>
        @if (limit() === '') {
          <span>(no limit)</span>
        }
      </p>
      @for (g of grades; track g) {
        <p>
          Clerk limit in force for grade {{ g }}:
          <strong [attr.data-testid]="'setting-clerk-limit-' + g + '-current'">{{ gradeLimits()[g] | figure }}</strong>
          @if (gradeLimits()[g] === '') {
            <span>(the sheet's)</span>
          }
        </p>
      }
      <p>
        Floor in force:
        <strong data-testid="setting-floor-current">{{ floor() | figure }}</strong>
      </p>
      <p>
        Terms in force (days):
        <strong data-testid="setting-terms-current">{{ terms() }}</strong>
      </p>
      @for (g of grades; track g) {
        <p>
          Terms in force for grade {{ g }} (days):
          <strong [attr.data-testid]="'setting-terms-' + g + '-current'">{{ gradeTerms()[g] }}</strong>
          @if (gradeTerms()[g] === '') {
            <span>(the sheet's terms)</span>
          }
        </p>
      }
      @for (g of grades; track g) {
        <p>
          Grace days in force for grade {{ g }}:
          <strong [attr.data-testid]="'setting-grace-' + g + '-current'">{{ gradeGraces()[g] }}</strong>
          @if (gradeGraces()[g] === '') {
            <span>(none)</span>
          }
        </p>
      }
      <p>
        Late percent in force:
        <strong data-testid="setting-late-pct-current">{{ latePct() | figure }}</strong>
      </p>
      <p>
        Policy date in force:
        <strong data-testid="setting-policy-date-current">{{ policyDate() }}</strong>
        @if (policyDate() === '') {
          <span>(the sheet's today)</span>
        }
      </p>
      <p>
        Period numbering from:
        <strong data-testid="setting-period-numbering-from-current">{{ numberingFrom() }}</strong>
        @if (numberingFrom() === '') {
          <span>(off)</span>
        }
      </p>
      <p>
        Cap in force:
        <strong data-testid="setting-cap-current">{{ cap() | figure }}</strong>
        @if (cap() === '') {
          <span>(no cap)</span>
        }
      </p>
      <p>
        Rounding mode in force:
        <strong data-testid="setting-rounding-current">{{ rounding() }}</strong>
        @if (rounding() === '') {
          <span>(none: by the policy date)</span>
        }
      </p>
      <p>
        Proof threshold in force:
        <strong data-testid="setting-proof-threshold-current">{{ threshold() | figure }}</strong>
        @if (threshold() === '') {
          <span>(none)</span>
        }
      </p>
      @for (g of grades; track g) {
        <p>
          Proof threshold in force for grade {{ g }}:
          <strong [attr.data-testid]="'setting-proof-threshold-' + g + '-current'">{{ gradeThresholds()[g] | figure }}</strong>
          @if (gradeThresholds()[g] === '') {
            <span>(the sheet's)</span>
          }
        </p>
      }
      <p>
        Note threshold in force:
        <strong data-testid="setting-note-threshold-current">{{ noteThreshold() | figure }}</strong>
        @if (noteThreshold() === '') {
          <span>(none)</span>
        }
      </p>
      <p>
        Review threshold in force:
        <strong data-testid="setting-review-threshold-current">{{ reviewThreshold() | figure }}</strong>
        @if (reviewThreshold() === '') {
          <span>(none)</span>
        }
      </p>
      <p>
        Grace days:
        <strong data-testid="setting-grace-days-current">{{ graceDays() }}</strong>
        @if (graceDays() === '') {
          <span>(off)</span>
        }
      </p>
      <p>
        Entry date:
        <strong data-testid="setting-entry-date-current">{{ entryDate() }}</strong>
        @if (entryDate() === '') {
          <span>(off: the sheet's today)</span>
        }
      </p>
      <p>
        Pot:
        <strong data-testid="setting-pot-current">{{ pot() | figure }}</strong>
        @if (pot() === '') {
          <span>(none)</span>
        }
      </p>
      <p>
        Alert threshold:
        <strong data-testid="setting-alert-threshold-current">{{ alertThreshold() | figure }}</strong>
        @if (alertThreshold() === '') {
          <span>(off)</span>
        }
      </p>
      <p>
        Escalation gap:
        <strong data-testid="setting-escalate-after-current">{{ escalateAfter() }}</strong>
        @if (escalateAfter() === '') {
          <span>(off)</span>
        }
      </p>
      <p>
        Score MID from:
        <strong data-testid="setting-score-mid-current">{{ session.scoreMid() }}</strong>
        @if (session.scoreMid() === '') {
          <span>(none)</span>
        }
      </p>
      <p>
        Score HIGH from:
        <strong data-testid="setting-score-high-current">{{ session.scoreHigh() }}</strong>
        @if (session.scoreHigh() === '') {
          <span>(none)</span>
        }
      </p>
      <p>
        Score log:
        <strong data-testid="setting-score-log-current">{{ scoreLog() }}</strong>
        @if (scoreLog() === '') {
          <span>(off)</span>
        }
      </p>
      <p>
        Review SLA (days):
        <strong data-testid="setting-review-sla-current">{{ session.reviewSla() }}</strong>
        @if (session.reviewSla() === '') {
          <span>(none)</span>
        }
      </p>
      <p>
        Pending SLA (days):
        <strong data-testid="setting-pending-sla-current">{{ session.pendingSla() }}</strong>
        @if (session.pendingSla() === '') {
          <span>(none)</span>
        }
      </p>
      <p>
        Most open claims per person:
        <strong data-testid="setting-max-open-current">{{ maxOpen() }}</strong>
        @if (maxOpen() === '') {
          <span>(none)</span>
        }
      </p>
      <p>
        Proof kinds:
        <strong data-testid="setting-proof-kinds-current">{{ proofKinds() }}</strong>
        @if (proofKinds() === '') {
          <span>(none)</span>
        }
      </p>
      <p>
        Default grade:
        <strong data-testid="setting-default-grade-current">{{ defaultGrade() }}</strong>
        @if (defaultGrade() === '') {
          <span>(A)</span>
        }
      </p>
      <p>
        Fiscal year starts in month:
        <strong data-testid="setting-fiscal-start-month-current">{{ fiscalStartMonth() }}</strong>
      </p>
      @for (a of permActions; track a) {
        <p>
          Roles that may {{ a }}:
          <strong [attr.data-testid]="'setting-perm-' + a + '-current'">{{ permissions()[a] ?? '' }}</strong>
          @if (!permissions()[a]) {
            <span>(the standing rule)</span>
          }
        </p>
      }
    </section>
  `,
})
export class Settings {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(Session);

  /** Every value saved for the dated settings. */
  private readonly dated = signal<DatedSetting[]>([]);
  /** The sheet's floors, dated, shared with whoever works out what is due. */
  readonly floors = model<DatedValue[]>([]);
  /** The floor in force on the sheet's today. */
  protected readonly floor = computed(() => valueOn(this.floors(), this.session.figureDay(), START_FLOOR));
  /** The sheet's terms in days in force on its today: what a grade without its own terms uses. */
  protected readonly terms = computed(() => this.inForce('terms', START_TERMS));
  protected readonly grades = GRADES;
  /** The terms in force on the sheet's today for each grade; blank for one without its own then. */
  protected readonly gradeTerms = computed(
    () => Object.fromEntries(GRADES.map((g) => [g, this.inForce(this.gradeKey(g), '')])) as Record<Grade, string>,
  );
  /** The terms typed for each grade; blank (or missing) leaves them as they are. */
  protected readonly gradeTermsInput = signal<Partial<Record<Grade, string>>>({});
  /** Each grade's grace days, dated (blank: none), shared with whoever works out whether a claim is overdue. */
  readonly graces = model<Record<string, DatedValue[]>>({});
  /** The grace days in force on the sheet's today for each grade; blank for none. */
  protected readonly gradeGraces = computed(
    () => Object.fromEntries(GRADES.map((g) => [g, this.inForce(this.graceKey(g), '')])) as Record<Grade, string>,
  );
  /** The grace days typed for each grade; missing while its field has not been entered (left as they are). */
  protected readonly gradeGraceInput = signal<Partial<Record<Grade, string>>>({});
  /** The sheet's late percents, dated, shared with whoever works out an overdue claim's late charge. */
  readonly latePcts = model<DatedValue[]>([]);
  /** The late percent in force on the sheet's today. */
  protected readonly latePct = computed(() => valueOn(this.latePcts(), this.session.figureDay(), START_LATE_PCT));
  /** The sheet's policy date (blank: its today), shared with whoever works out how a claim rounds. */
  readonly policyDate = model('');
  /** The sheet's caps, dated (blank: none), shared with whoever works out a person's due-total. */
  readonly caps = model<DatedValue[]>([]);
  /** The cap in force on the sheet's today; blank for none. */
  protected readonly cap = computed(() => valueOn(this.caps(), this.session.figureDay(), ''));
  /** The cap typed; null while its field has not been entered (left as it is). */
  protected readonly capInput = signal<string | null>(null);
  /** The sheet's rounding modes, dated (blank: none), shared with whoever works out how a claim rounds. */
  readonly roundings = model<DatedValue[]>([]);
  /** The rounding mode in force on the sheet's today; blank for none. */
  protected readonly rounding = computed(() => valueOn(this.roundings(), this.session.figureDay(), ''));
  /** The rounding mode typed; null while its field has not been entered (left as it is). */
  protected readonly roundingInput = signal<string | null>(null);

  /** The proof threshold in force on the sheet's today; blank for none. */
  protected readonly threshold = computed(() => this.inForce('proof-threshold', ''));
  /** The proof threshold typed; null while its field has not been entered (left as it is). */
  protected readonly thresholdInput = signal<string | null>(null);
  /** The proof threshold in force on the sheet's today for each grade; blank for none (the sheet's). */
  protected readonly gradeThresholds = computed(
    () => Object.fromEntries(GRADES.map((g) => [g, this.inForce(this.thresholdKey(g), '')])) as Record<Grade, string>,
  );
  /** The proof threshold typed for each grade; missing while its field has not been entered (left as it is). */
  protected readonly gradeThresholdInput = signal<Partial<Record<Grade, string>>>({});
  /** The note threshold in force on the sheet's today; blank for none. */
  protected readonly noteThreshold = computed(() => this.inForce('note-threshold', ''));
  /** The note threshold typed; null while its field has not been entered (left as it is). */
  protected readonly noteThresholdInput = signal<string | null>(null);
  /** The review threshold in force on the sheet's today; blank for none. */
  protected readonly reviewThreshold = computed(() => this.inForce('review-threshold', ''));
  /** The review threshold typed; null while its field has not been entered (left as it is). */
  protected readonly reviewThresholdInput = signal<string | null>(null);

  /** The clerk limit in force on the sheet's today; blank for none. */
  protected readonly limit = computed(() => this.inForce('clerk-limit', ''));
  /** The from-date typed for each dated setting; blank (or missing) for the sheet's today. */
  protected readonly fromInput = signal<Partial<Record<DatedKey, string>>>({});
  /** The period-numbering-from date in force; blank for off. */
  protected readonly numberingFrom = signal('');
  /** The clerk limit typed; null while its field has not been entered (left as it is). */
  protected readonly limitInput = signal<string | null>(null);
  /** The clerk limit in force on the sheet's today for each grade; blank for none (the sheet's). */
  protected readonly gradeLimits = computed(
    () => Object.fromEntries(GRADES.map((g) => [g, this.inForce(this.limitKey(g), '')])) as Record<Grade, string>,
  );
  /** The clerk limit typed for each grade; missing while its field has not been entered (left as it is). */
  protected readonly gradeLimitInput = signal<Partial<Record<Grade, string>>>({});
  /** The floor typed; blank (or null) leaves it as it is. */
  protected readonly floorInput = signal<string | null>(null);
  /** The terms typed; blank (or null) leaves them as they are. */
  protected readonly termsInput = signal<string | null>(null);
  /** The late percent typed; blank (or null) leaves it as it is. */
  protected readonly latePctInput = signal<string | null>(null);
  /** The policy date typed; null while its field has not been entered (left as it is). */
  protected readonly policyInput = signal<string | null>(null);
  /** The period-numbering-from date typed; null while its field has not been entered (left as it is). */
  protected readonly numberingInput = signal<string | null>(null);
  /** The grace days in force; blank for off. */
  protected readonly graceDays = signal('');
  /** The grace days typed; null while its field has not been entered (left as they are). */
  protected readonly graceInput = signal<string | null>(null);
  /** The entry date in force; blank for off. */
  protected readonly entryDate = signal('');
  /** The entry date typed; null while its field has not been entered (left as it is). */
  protected readonly entryInput = signal<string | null>(null);
  /** The sheet's pot, blank for none, shared with the pools that cut it. */
  readonly pot = model('');
  /** The pot typed; null while its field has not been entered (left as it is). */
  protected readonly potInput = signal<string | null>(null);
  /** The alert threshold, blank for off. */
  protected readonly alertThreshold = signal('');
  /** The alert threshold typed; null while its field has not been entered (left as it is). */
  protected readonly alertInput = signal<string | null>(null);
  /** The escalation gap in days, blank for off. */
  protected readonly escalateAfter = signal('');
  /** The escalation gap typed; null while its field has not been entered (left as it is). */
  protected readonly escalateInput = signal<string | null>(null);
  /** The mid score threshold typed; null while its field has not been entered (left as it is). */
  protected readonly scoreMidInput = signal<string | null>(null);
  /** The high score threshold typed; null while its field has not been entered (left as it is). */
  protected readonly scoreHighInput = signal<string | null>(null);
  /** The score log switch in force: on, or blank for off. */
  protected readonly scoreLog = signal('');
  /** The score log switch typed; null while its field has not been entered (left as it is). */
  protected readonly scoreLogInput = signal<string | null>(null);
  /** The review SLA typed; null while its field has not been entered (left as it is). */
  protected readonly reviewSlaInput = signal<string | null>(null);
  /** The pending SLA typed; null while its field has not been entered (left as it is). */
  protected readonly pendingSlaInput = signal<string | null>(null);
  /** The most open claims per person in force, blank for none. */
  protected readonly maxOpen = signal('');
  /** The most open claims per person typed; null while its field has not been entered (left as it is). */
  protected readonly maxOpenInput = signal<string | null>(null);
  /** The proof kinds in force, comma separated, blank for none. */
  protected readonly proofKinds = signal('');
  /** The proof kinds typed; null while their field has not been entered (left as they are). */
  protected readonly proofKindsInput = signal<string | null>(null);
  /** The default grade in force, blank for none (A). */
  protected readonly defaultGrade = signal('');
  /** The default grade typed; null while its field has not been entered (left as it is). */
  protected readonly defaultGradeInput = signal<string | null>(null);
  /** The month the sheet's fiscal year starts in (1 to 12). */
  protected readonly fiscalStartMonth = signal('1');
  /** The fiscal start month typed; null while its field has not been typed in (left as it is). */
  protected readonly fiscalInput = signal<string | null>(null);
  /** The parent typed; null while its field has not been entered (left as it is). */
  protected readonly parentInput = signal<string | null>(null);
  protected readonly permActions = PERM_ACTIONS;
  /** The permission matrix in force: for each action, its roles, blank for the standing rule. */
  protected readonly permissions = signal<Partial<Record<PermAction, string>>>({});
  /** The roles typed for each action; missing while its field has not been entered (left as it is). */
  protected readonly permInput = signal<Partial<Record<PermAction, string>>>({});
  protected readonly error = signal<string | null>(null);
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;

  constructor() {
    // Re-read the sheet's settings from the store every time a sheet is loaded.
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      untracked(() => this.show({ dated: [], policyDate: '', periodNumberingFrom: '', permissions: {}, graceDays: '', entryDate: '', pot: '' }));
      this.permInput.set({});
      this.gradeTermsInput.set({});
      this.gradeGraceInput.set({});
      this.gradeThresholdInput.set({});
      this.gradeLimitInput.set({});
      this.fromInput.set({});
      this.capInput.set(null);
      this.roundingInput.set(null);
      this.thresholdInput.set(null);
      this.noteThresholdInput.set(null);
      this.reviewThresholdInput.set(null);
      this.limitInput.set(null);
      this.floorInput.set(null);
      this.termsInput.set(null);
      this.latePctInput.set(null);
      this.policyInput.set(null);
      this.numberingInput.set(null);
      this.graceInput.set(null);
      this.entryInput.set(null);
      this.potInput.set(null);
      this.alertInput.set(null);
      this.escalateInput.set(null);
      this.reviewSlaInput.set(null);
      this.pendingSlaInput.set(null);
      this.maxOpenInput.set(null);
      this.scoreMidInput.set(null);
      this.scoreHighInput.set(null);
      this.scoreLogInput.set(null);
      this.proofKindsInput.set(null);
      this.defaultGradeInput.set(null);
      this.fiscalInput.set(null);
      this.parentInput.set(null);
      this.error.set(null);
      if (sheet) {
        void this.fetch(sheet);
      }
    });
  }

  private async fetch(sheet: string): Promise<void> {
    try {
      const res = await firstValueFrom(
        this.http.post<SettingsResponse>('/api/settings/get', { sheet, user: this.session.user() }),
      );
      if (this.session.sheet() === sheet) this.show(res);
    } catch {
      this.error.set('Could not read the settings of this sheet.');
    }
  }

  /** Entering the clerk limit's field fills it with the limit in force, selected, so it saves as
   *  it is unless typed over. */
  protected enterLimit(input: HTMLInputElement): void {
    if (this.limitInput() !== null) return;
    input.value = this.limit();
    input.select();
    this.limitInput.set(this.limit());
  }

  /** Entering the cap's field fills it with the cap in force, selected, so it saves as it is
   *  unless typed over. */
  protected enterCap(input: HTMLInputElement): void {
    if (this.capInput() !== null) return;
    input.value = this.cap();
    input.select();
    this.capInput.set(this.cap());
  }

  /** Entering the rounding mode's field fills it with the mode in force, selected, so it saves as
   *  it is unless typed over. */
  protected enterRounding(input: HTMLInputElement): void {
    if (this.roundingInput() !== null) return;
    input.value = this.rounding();
    input.select();
    this.roundingInput.set(this.rounding());
  }

  /** Entering the proof threshold's field fills it with the threshold in force, selected, so it
   *  saves as it is unless typed over. */
  protected enterThreshold(input: HTMLInputElement): void {
    if (this.thresholdInput() !== null) return;
    input.value = this.threshold();
    input.select();
    this.thresholdInput.set(this.threshold());
  }

  /** Entering the note threshold's field fills it with the threshold in force, selected, so it
   *  saves as it is unless typed over. */
  protected enterNoteThreshold(input: HTMLInputElement): void {
    if (this.noteThresholdInput() !== null) return;
    input.value = this.noteThreshold();
    input.select();
    this.noteThresholdInput.set(this.noteThreshold());
  }

  /** Entering the review threshold's field fills it with the threshold in force, selected, so it
   *  saves as it is unless typed over. */
  protected enterReviewThreshold(input: HTMLInputElement): void {
    if (this.reviewThresholdInput() !== null) return;
    input.value = this.reviewThreshold();
    input.select();
    this.reviewThresholdInput.set(this.reviewThreshold());
  }

  /** Entering the policy date's field fills it with the date in force, so it saves as it is unless
   *  changed. */
  protected enterPolicy(input: HTMLInputElement): void {
    if (this.policyInput() !== null) return;
    input.value = this.policyDate();
    this.policyInput.set(this.policyDate());
  }

  /** Entering the period-numbering-from field fills it with the date in force, so it saves as it is
   *  unless changed. */
  protected enterNumbering(input: HTMLInputElement): void {
    if (this.numberingInput() !== null) return;
    input.value = this.numberingFrom();
    this.numberingInput.set(this.numberingFrom());
  }

  /** Entering the entry date's field fills it with the date in force, so it saves as it is unless
   *  changed. */
  protected enterEntry(input: HTMLInputElement): void {
    if (this.entryInput() !== null) return;
    input.value = this.entryDate();
    this.entryInput.set(this.entryDate());
  }

  /** Entering the pot's field fills it with the pot in force, selected, so it saves as it is
   *  unless typed over. */
  protected enterPot(input: HTMLInputElement): void {
    if (this.potInput() !== null) return;
    input.value = this.pot();
    input.select();
    this.potInput.set(this.pot());
  }

  /** Entering the alert threshold's field fills it with the threshold in force, selected, so it
   *  saves as it is unless typed over. */
  protected enterAlert(input: HTMLInputElement): void {
    if (this.alertInput() !== null) return;
    input.value = this.alertThreshold();
    input.select();
    this.alertInput.set(this.alertThreshold());
  }

  /** Entering the escalation gap's field fills it with the gap in force, selected, so it saves as
   *  it is unless typed over. */
  protected enterEscalate(input: HTMLInputElement): void {
    if (this.escalateInput() !== null) return;
    input.value = this.escalateAfter();
    input.select();
    this.escalateInput.set(this.escalateAfter());
  }

  /** Entering the mid score threshold's field fills it with the threshold in force, selected, so it
   *  saves as it is unless typed over. */
  protected enterScoreMid(input: HTMLInputElement): void {
    if (this.scoreMidInput() !== null) return;
    input.value = this.session.scoreMid();
    input.select();
    this.scoreMidInput.set(this.session.scoreMid());
  }

  /** Entering the high score threshold's field fills it with the threshold in force, selected, so it
   *  saves as it is unless typed over. */
  protected enterScoreHigh(input: HTMLInputElement): void {
    if (this.scoreHighInput() !== null) return;
    input.value = this.session.scoreHigh();
    input.select();
    this.scoreHighInput.set(this.session.scoreHigh());
  }

  /** Entering the score log's field fills it with the switch in force, selected, so it saves as it is
   *  unless typed over. */
  protected enterScoreLog(input: HTMLInputElement): void {
    if (this.scoreLogInput() !== null) return;
    input.value = this.scoreLog();
    input.select();
    this.scoreLogInput.set(this.scoreLog());
  }

  /** Entering the review SLA's field fills it with the SLA in force, selected, so it saves as it is
   *  unless typed over. */
  protected enterReviewSla(input: HTMLInputElement): void {
    if (this.reviewSlaInput() !== null) return;
    input.value = this.session.reviewSla();
    input.select();
    this.reviewSlaInput.set(this.session.reviewSla());
  }

  /** Entering the pending SLA's field fills it with the SLA in force, selected, so it saves as it is
   *  unless typed over. */
  protected enterPendingSla(input: HTMLInputElement): void {
    if (this.pendingSlaInput() !== null) return;
    input.value = this.session.pendingSla();
    input.select();
    this.pendingSlaInput.set(this.session.pendingSla());
  }

  /** Entering the most open claims' field fills it with the value in force, selected, so it saves as it is
   *  unless typed over. */
  protected enterMaxOpen(input: HTMLInputElement): void {
    if (this.maxOpenInput() !== null) return;
    input.value = this.maxOpen();
    input.select();
    this.maxOpenInput.set(this.maxOpen());
  }

  /** Entering the proof kinds' field fills it with the kinds in force, selected, so they save as they are
   *  unless typed over. */
  protected enterProofKinds(input: HTMLInputElement): void {
    if (this.proofKindsInput() !== null) return;
    input.value = this.proofKinds();
    input.select();
    this.proofKindsInput.set(this.proofKinds());
  }

  /** Entering the default grade's field fills it with the grade in force, selected, so it saves as it is
   *  unless typed over. */
  protected enterDefaultGrade(input: HTMLInputElement): void {
    if (this.defaultGradeInput() !== null) return;
    input.value = this.defaultGrade();
    input.select();
    this.defaultGradeInput.set(this.defaultGrade());
  }

  /** Entering the parent's field fills it with the parent in force, selected, so it saves as it is
   *  unless typed over. */
  protected enterParent(input: HTMLInputElement): void {
    if (this.parentInput() !== null) return;
    input.value = this.session.parent();
    input.select();
    this.parentInput.set(this.session.parent());
  }

  /** Entering the grace days' field fills it with the days in force, selected, so they save as
   *  they are unless typed over. */
  protected enterGrace(input: HTMLInputElement): void {
    if (this.graceInput() !== null) return;
    input.value = this.graceDays();
    input.select();
    this.graceInput.set(this.graceDays());
  }

  /** Entering an action's permission field fills it with its roles in force, selected, so it saves
   *  as it is unless typed over. */
  protected enterPerm(action: PermAction, input: HTMLInputElement): void {
    if (this.permInput()[action] !== undefined) return;
    const roles = this.permissions()[action] ?? '';
    input.value = roles;
    input.select();
    this.permInput.update((m) => ({ ...m, [action]: roles }));
  }

  protected typePerm(action: PermAction, value: string): void {
    this.permInput.update((m) => ({ ...m, [action]: value }));
    this.error.set(null);
  }

  protected gradeKey(grade: Grade): DatedKey {
    return `terms-${grade}`;
  }

  protected graceKey(grade: Grade): DatedKey {
    return `grace-${grade}`;
  }

  /** Entering a grade's grace days' field fills it with the days in force, selected, so they save
   *  as they are unless typed over. */
  protected enterGradeGrace(grade: Grade, input: HTMLInputElement): void {
    if (this.gradeGraceInput()[grade] !== undefined) return;
    const days = this.gradeGraces()[grade];
    input.value = days;
    input.select();
    this.gradeGraceInput.update((m) => ({ ...m, [grade]: days }));
  }

  protected typeGradeGrace(grade: Grade, value: string): void {
    this.gradeGraceInput.update((m) => ({ ...m, [grade]: value }));
    this.error.set(null);
  }

  protected thresholdKey(grade: Grade): DatedKey {
    return `proof-threshold-${grade}`;
  }

  /** Entering a grade's proof threshold's field fills it with the threshold in force, selected, so
   *  it saves as it is unless typed over. */
  protected enterGradeThreshold(grade: Grade, input: HTMLInputElement): void {
    if (this.gradeThresholdInput()[grade] !== undefined) return;
    const threshold = this.gradeThresholds()[grade];
    input.value = threshold;
    input.select();
    this.gradeThresholdInput.update((m) => ({ ...m, [grade]: threshold }));
  }

  protected typeGradeThreshold(grade: Grade, value: string): void {
    this.gradeThresholdInput.update((m) => ({ ...m, [grade]: value }));
    this.error.set(null);
  }

  protected limitKey(grade: Grade): DatedKey {
    return `clerk-limit-${grade}`;
  }

  /** Entering a grade's clerk limit's field fills it with the limit in force, selected, so it
   *  saves as it is unless typed over. */
  protected enterGradeLimit(grade: Grade, input: HTMLInputElement): void {
    if (this.gradeLimitInput()[grade] !== undefined) return;
    const limit = this.gradeLimits()[grade];
    input.value = limit;
    input.select();
    this.gradeLimitInput.update((m) => ({ ...m, [grade]: limit }));
  }

  protected typeGradeLimit(grade: Grade, value: string): void {
    this.gradeLimitInput.update((m) => ({ ...m, [grade]: value }));
    this.error.set(null);
  }

  protected typeFrom(key: DatedKey, value: string): void {
    this.fromInput.update((m) => ({ ...m, [key]: value }));
    this.error.set(null);
  }

  /** The values saved for one dated setting. */
  private datedOf(key: string): DatedValue[] {
    return this.dated().filter((d) => d.key === key);
  }

  /** A dated setting's value in force on the sheet's today; `start` before its first from-date. */
  private inForce(key: string, start: string): string {
    return valueOn(this.datedOf(key), this.session.figureDay(), start);
  }

  protected typeGradeTerms(grade: Grade, value: string): void {
    this.gradeTermsInput.update((m) => ({ ...m, [grade]: value }));
    this.error.set(null);
  }

  private show(
    res: Pick<SettingsResponse, 'dated' | 'policyDate' | 'periodNumberingFrom' | 'permissions' | 'graceDays' | 'entryDate' | 'pot'>
      & Partial<Pick<SettingsResponse, 'parent' | 'fiscalStartMonth' | 'alertThreshold' | 'escalateAfter' | 'reviewSla' | 'pendingSla' | 'maxOpen' | 'proofKinds' | 'defaultGrade' | 'scoreMid' | 'scoreHigh' | 'scoreLog'>>,
  ): void {
    this.dated.set(res.dated);
    this.permissions.set(res.permissions);
    this.floors.set(this.datedOf('floor'));
    this.latePcts.set(this.datedOf('late-pct'));
    this.graces.set(Object.fromEntries(GRADES.map((g) => [g, this.datedOf(this.graceKey(g))])));
    this.caps.set(this.datedOf('cap'));
    this.roundings.set(this.datedOf('rounding'));
    this.policyDate.set(res.policyDate);
    this.numberingFrom.set(res.periodNumberingFrom);
    this.graceDays.set(res.graceDays);
    this.entryDate.set(res.entryDate);
    this.pot.set(res.pot);
    this.fiscalStartMonth.set(String(res.fiscalStartMonth ?? 1));
    this.alertThreshold.set(res.alertThreshold ?? '');
    this.escalateAfter.set(res.escalateAfter ?? '');
    if (res.parent !== undefined) this.session.parent.set(res.parent);
    this.session.reviewSla.set(res.reviewSla ?? '');
    this.session.pendingSla.set(res.pendingSla ?? '');
    this.maxOpen.set(res.maxOpen ?? '');
    this.session.scoreMid.set(res.scoreMid ?? '');
    this.session.scoreHigh.set(res.scoreHigh ?? '');
    this.scoreLog.set(res.scoreLog ?? '');
    this.proofKinds.set(res.proofKinds ?? '');
    this.defaultGrade.set(res.defaultGrade ?? '');
  }

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    if (this.submitting) return;
    this.error.set(null);
    const sheet = this.session.sheet();
    const clerkLimit = this.limitInput()?.trim() ?? null;
    const floor = this.floorInput()?.trim() || null;
    const terms = this.termsInput()?.trim() || null;
    const latePct = this.latePctInput()?.trim() || null;
    const policyDate = this.policyInput()?.trim() ?? null;
    const periodNumberingFrom = this.numberingInput()?.trim() ?? null;
    const cap = this.capInput()?.trim() ?? null;
    const rounding = this.roundingInput()?.trim() ?? null;
    const proofThreshold = this.thresholdInput()?.trim() ?? null;
    const noteThreshold = this.noteThresholdInput()?.trim() ?? null;
    const reviewThreshold = this.reviewThresholdInput()?.trim() ?? null;
    const graceDays = this.graceInput()?.trim() ?? null;
    const entryDate = this.entryInput()?.trim() ?? null;
    const pot = this.potInput()?.trim() ?? null;
    const parent = this.parentInput()?.trim() ?? null;
    const fiscalStartMonth = this.fiscalInput()?.trim() || null;
    const alertThreshold = this.alertInput()?.trim() ?? null;
    const escalateAfter = this.escalateInput()?.trim() ?? null;
    const reviewSla = this.reviewSlaInput()?.trim() ?? null;
    const maxOpen = this.maxOpenInput()?.trim() ?? null;
    const pendingSla = this.pendingSlaInput()?.trim() ?? null;
    const scoreMid = this.scoreMidInput()?.trim() ?? null;
    const scoreHigh = this.scoreHighInput()?.trim() ?? null;
    const scoreLog = this.scoreLogInput()?.trim() ?? null;
    const proofKinds = this.proofKindsInput()?.trim() ?? null;
    const defaultGrade = this.defaultGradeInput()?.trim() ?? null;
    if (clerkLimit !== null && clerkLimit !== '' && !LIMIT.test(clerkLimit)) {
      this.error.set('Enter a clerk limit of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    const gradeLimits: Partial<Record<Grade, string>> = {};
    for (const g of GRADES) {
      const l = this.gradeLimitInput()[g]?.trim();
      if (l === undefined) continue;
      if (l !== '' && !LIMIT.test(l)) {
        this.error.set(`Enter a clerk limit for grade ${g} of 0.00 or more, with up to two decimals, or leave it blank.`);
        return;
      }
      gradeLimits[g] = l;
    }
    if (floor !== null && !LIMIT.test(floor)) {
      this.error.set('Enter a floor of 0.00 or more, with up to two decimals.');
      return;
    }
    if (terms !== null && !TERMS.test(terms)) {
      this.error.set('Enter terms of 0 or more whole days.');
      return;
    }
    const gradeTerms: Partial<Record<Grade, string>> = {};
    for (const g of GRADES) {
      const t = this.gradeTermsInput()[g]?.trim();
      if (!t) continue;
      if (!TERMS.test(t)) {
        this.error.set(`Enter terms for grade ${g} of 0 or more whole days.`);
        return;
      }
      gradeTerms[g] = t;
    }
    const gradeGraces: Partial<Record<Grade, string>> = {};
    for (const g of GRADES) {
      const t = this.gradeGraceInput()[g]?.trim();
      if (t === undefined) continue;
      if (t !== '' && !TERMS.test(t)) {
        this.error.set(`Enter grace days for grade ${g} of 0 or more whole days, or leave it blank for none.`);
        return;
      }
      gradeGraces[g] = t;
    }
    if (latePct !== null && !PCT.test(latePct)) {
      this.error.set('Enter a late percent of 0.00 to 999.99, with up to two decimals.');
      return;
    }
    if (policyDate !== null && policyDate !== '' && !DATE.test(policyDate)) {
      this.error.set('Enter a policy date as a date, or leave it blank for the sheet\'s today.');
      return;
    }
    if (periodNumberingFrom !== null && periodNumberingFrom !== '' && !DATE.test(periodNumberingFrom)) {
      this.error.set('Enter a period-numbering-from date as a date, or leave it blank for off.');
      return;
    }
    if (cap !== null && cap !== '' && !LIMIT.test(cap)) {
      this.error.set('Enter a cap of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    if (rounding !== null && rounding !== '' && !ROUNDING.test(rounding)) {
      this.error.set('Enter a rounding mode of half-up or half-even, or leave it blank for none.');
      return;
    }
    if (proofThreshold !== null && proofThreshold !== '' && !LIMIT.test(proofThreshold)) {
      this.error.set('Enter a proof threshold of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    const gradeThresholds: Partial<Record<Grade, string>> = {};
    for (const g of GRADES) {
      const t = this.gradeThresholdInput()[g]?.trim();
      if (t === undefined) continue;
      if (t !== '' && !LIMIT.test(t)) {
        this.error.set(`Enter a proof threshold for grade ${g} of 0.00 or more, with up to two decimals, or leave it blank.`);
        return;
      }
      gradeThresholds[g] = t;
    }
    if (noteThreshold !== null && noteThreshold !== '' && !LIMIT.test(noteThreshold)) {
      this.error.set('Enter a note threshold of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    if (reviewThreshold !== null && reviewThreshold !== '' && !LIMIT.test(reviewThreshold)) {
      this.error.set('Enter a review threshold of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    if (graceDays !== null && graceDays !== '' && !TERMS.test(graceDays)) {
      this.error.set('Enter grace days of 0 or more whole days, or leave it blank for off.');
      return;
    }
    if (entryDate !== null && entryDate !== '' && !DATE.test(entryDate)) {
      this.error.set('Enter an entry date as a date, or leave it blank for off.');
      return;
    }
    if (pot !== null && pot !== '' && !LIMIT.test(pot)) {
      this.error.set('Enter a pot of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    if (alertThreshold !== null && alertThreshold !== '' && !LIMIT.test(alertThreshold)) {
      this.error.set('Enter an alert threshold of 0.00 or more, with up to two decimals, or leave it blank for off.');
      return;
    }
    if (escalateAfter !== null && escalateAfter !== '' && !TERMS.test(escalateAfter)) {
      this.error.set('Enter an escalation gap of 0 or more whole days, or leave it blank for off.');
      return;
    }
    if (reviewSla !== null && reviewSla !== '' && !TERMS.test(reviewSla)) {
      this.error.set('Enter a review SLA of 0 or more whole days, or leave it blank for none.');
      return;
    }
    if (pendingSla !== null && pendingSla !== '' && !TERMS.test(pendingSla)) {
      this.error.set('Enter a pending SLA of 0 or more whole days, or leave it blank for none.');
      return;
    }
    if (maxOpen !== null && maxOpen !== '' && !TERMS.test(maxOpen)) {
      this.error.set('Enter the most open claims per person as a whole number of 0 or more, or leave it blank for none.');
      return;
    }
    if ((scoreMid !== null && scoreMid !== '' && !SCORE.test(scoreMid)) || (scoreHigh !== null && scoreHigh !== '' && !SCORE.test(scoreHigh))) {
      this.error.set('Enter each score threshold as a whole number, or leave it blank for none.');
      return;
    }
    if (scoreLog !== null && scoreLog !== '' && scoreLog !== 'on') {
      this.error.set('Enter on for the score log, or leave it blank for off.');
      return;
    }
    if (proofKinds !== null && proofKinds !== '' && !PROOF_KINDS.test(proofKinds)) {
      this.error.set('Enter the proof kinds as receipt or photo, comma separated, or leave it blank for none.');
      return;
    }
    if (defaultGrade !== null && defaultGrade !== '' && !this.session.grades().includes(defaultGrade)) {
      this.error.set('Enter one of the sheet\'s grades as the default grade, or leave it blank for A.');
      return;
    }
    if (fiscalStartMonth !== null && !/^(0?[1-9]|1[0-2])$/.test(fiscalStartMonth)) {
      this.error.set('Enter the fiscal start month as a month number, 1 to 12.');
      return;
    }
    if (parent !== null && parent.length > 100) {
      this.error.set('Enter a parent sheet id of up to 100 characters, or leave it blank for none.');
      return;
    }
    if (parent !== null && parent === sheet) {
      this.error.set('A sheet cannot be its own parent.');
      return;
    }
    const perms: Partial<Record<PermAction, string>> = {};
    for (const a of PERM_ACTIONS) {
      const roles = this.permInput()[a]?.trim();
      if (roles === undefined) continue;
      if (roles !== '' && !ROLES.test(roles)) {
        this.error.set(`Enter the roles that may ${a} as clerk and/or steward, comma separated, or leave it blank.`);
        return;
      }
      perms[a] = roles;
    }
    const from: Partial<Record<DatedKey, string>> = {};
    for (const k of DATED_KEYS) {
      const f = this.fromInput()[k]?.trim();
      if (!f) continue;
      if (!DATE.test(f)) {
        this.error.set('Enter a from-date as a date, or leave it blank for the sheet\'s today.');
        return;
      }
      from[k] = f;
    }
    this.submitting = true;
    try {
      const res = await firstValueFrom(
        this.http.post<SettingsResponse>('/api/settings/set', {
          sheet,
          user: this.session.user(),
          clerkLimit,
          floor,
          terms,
          latePct,
          policyDate,
          periodNumberingFrom,
          termsA: gradeTerms.A ?? null,
          termsB: gradeTerms.B ?? null,
          termsC: gradeTerms.C ?? null,
          cap,
          clerkLimitFrom: from['clerk-limit'] ?? null,
          floorFrom: from.floor ?? null,
          termsFrom: from.terms ?? null,
          latePctFrom: from['late-pct'] ?? null,
          termsAFrom: from['terms-A'] ?? null,
          termsBFrom: from['terms-B'] ?? null,
          termsCFrom: from['terms-C'] ?? null,
          capFrom: from.cap ?? null,
          proofThreshold,
          proofThresholdFrom: from['proof-threshold'] ?? null,
          noteThreshold,
          noteThresholdFrom: from['note-threshold'] ?? null,
          reviewThreshold,
          reviewThresholdFrom: from['review-threshold'] ?? null,
          permOpen: perms.open ?? null,
          permSettle: perms.settle ?? null,
          permVoid: perms.void ?? null,
          permReassign: perms.reassign ?? null,
          permClose: perms.close ?? null,
          permReopen: perms.reopen ?? null,
          graceDays,
          entryDate,
          graceA: gradeGraces.A ?? null,
          graceB: gradeGraces.B ?? null,
          graceC: gradeGraces.C ?? null,
          graceAFrom: from['grace-A'] ?? null,
          graceBFrom: from['grace-B'] ?? null,
          graceCFrom: from['grace-C'] ?? null,
          proofThresholdA: gradeThresholds.A ?? null,
          proofThresholdB: gradeThresholds.B ?? null,
          proofThresholdC: gradeThresholds.C ?? null,
          proofThresholdAFrom: from['proof-threshold-A'] ?? null,
          proofThresholdBFrom: from['proof-threshold-B'] ?? null,
          proofThresholdCFrom: from['proof-threshold-C'] ?? null,
          pot,
          parent,
          fiscalStartMonth,
          alertThreshold,
          rounding,
          roundingFrom: from.rounding ?? null,
          escalateAfter,
          reviewSla,
          maxOpen,
          pendingSla,
          proofKinds,
          defaultGrade,
          clerkLimitA: gradeLimits.A ?? null,
          clerkLimitB: gradeLimits.B ?? null,
          clerkLimitC: gradeLimits.C ?? null,
          clerkLimitAFrom: from['clerk-limit-A'] ?? null,
          clerkLimitBFrom: from['clerk-limit-B'] ?? null,
          clerkLimitCFrom: from['clerk-limit-C'] ?? null,
          scoreMid,
          scoreHigh,
          scoreLog,
        }),
      );
      if (this.session.sheet() !== sheet) return;
      this.show(res);
      this.limitInput.set(null);
      this.floorInput.set(null);
      this.termsInput.set(null);
      this.gradeTermsInput.set({});
      this.gradeGraceInput.set({});
      this.gradeThresholdInput.set({});
      this.gradeLimitInput.set({});
      this.latePctInput.set(null);
      this.policyInput.set(null);
      this.numberingInput.set(null);
      this.capInput.set(null);
      this.roundingInput.set(null);
      this.thresholdInput.set(null);
      this.noteThresholdInput.set(null);
      this.reviewThresholdInput.set(null);
      this.permInput.set({});
      this.graceInput.set(null);
      this.entryInput.set(null);
      this.potInput.set(null);
      this.alertInput.set(null);
      this.escalateInput.set(null);
      this.reviewSlaInput.set(null);
      this.pendingSlaInput.set(null);
      this.maxOpenInput.set(null);
      this.scoreMidInput.set(null);
      this.scoreHighInput.set(null);
      this.scoreLogInput.set(null);
      this.proofKindsInput.set(null);
      this.defaultGradeInput.set(null);
      this.fiscalInput.set(null);
      this.parentInput.set(null);
      this.fromInput.set({});
    } catch {
      this.error.set(
        parent !== null && parent !== ''
          ? 'Could not save the settings. The parent must be a sheet already loaded, not this sheet nor one that has it as a parent.'
          : 'Could not save the settings.',
      );
    } finally {
      this.submitting = false;
    }
  }
}
