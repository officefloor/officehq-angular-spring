import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { FigurePipe } from '../../core/locale';
import { Component, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { DatedValue, PolicyVersion, Session } from '../../core/session';

interface SettingsView {
  /** The highest due a clerk may settle in force on the sheet's today, two decimals; null when there is no limit. */
  clerkLimit: string | null;
  /** The least a claim's live due can be, two decimals. */
  floor: string;
  /** The days after its date a claim is due by. */
  terms: number;
  /** Each grade's own terms in days, by grade; a grade left out uses the sheet's terms. */
  gradeTerms: Record<string, number>;
  /** The percent of its figure an overdue claim's due gains every full 30 days, two decimals. */
  latePct: string;
  /** The date (yyyy-mm-dd) before which a claim rounds half up, and on or after which half to even; null: the today. */
  policyDate: string | null;
  /** The date (yyyy-mm-dd) from which, on the sheet's today, a claim opened is numbered within its period; null: off. */
  periodNumberingFrom: string | null;
  /** The date (yyyy-mm-dd) a new claim is dated by default in place of the sheet's today; null: off. */
  entryDate: string | null;
  /** The most a person's due-total can be, two decimals; null when there is no cap. */
  cap: string | null;
  /** The days past its to-date after which a change of today closes an open period; null: off. */
  graceDays: number | null;
  /** The month (1 to 12) the sheet's fiscal year starts in. */
  fiscalStartMonth: number;
  /** Days at least which a claim's previous reminder lies before today for a reminder to escalate it; null: off. */
  escalateAfter: number | null;
  maxOpen: number | null;
  /** Days after its review day a REVIEW claim may wait before it is late; null: none. */
  reviewSla: number | null;
  /** Days after its pending day a PENDING claim may wait before it is late; null: none. */
  pendingSla: number | null;
  /** The score at or above which a person is MID while both score thresholds are set; null: blank. */
  scoreMid: number | null;
  /** The score at or above which a person is HIGH while both score thresholds are set; null: blank. */
  scoreHigh: number | null;
  /** Whether changing the sheet's today records each person's score on the day being left. */
  scoreLog: boolean;
  /** The due above which a claim cannot be settled or approved without a proof, two decimals; null when there is none. */
  proofThreshold: string | null;
  /** The due above which a claim cannot be opened with an empty note, two decimals; null when there is none. */
  noteThreshold: string | null;
  /** The due above which a claim goes to REVIEW when opened instead of OPEN, two decimals; null when there is none. */
  reviewThreshold: string | null;
  /** The pot shared out over each balanced pool's members by their shares, two decimals; null when there is none. */
  pot: string | null;
  /** The due-total above which a person's due-total going is reported in an ALERT line, two decimals; null when off. */
  alertThreshold: string | null;
  /** The kinds of proof (comma separated) a claim above the proof threshold needs one of each of; null when none. */
  proofKinds: string | null;
  /** The grade a person added without one takes (quick add, an import line with a blank grade); null: A. */
  defaultGrade: string | null;
  /** The parent sheet's id, whose rates a grade with none in force falls back on; null when there is none. */
  parent: string | null;
  /** The permission matrix, by action: the roles that may take it, comma separated; null: the standing rule. */
  permissions: Record<string, string | null>;
  /** Every value recorded of each dated setting, by key, from-date first to last. */
  dated: Record<string, DatedValue[]>;
  /** The policy versions, first made to last: the last one's number is the current version. */
  policyVersions: PolicyVersion[];
}

/** A delegation of the steward role to a user by a steward, from and to dates (yyyy-mm-dd) inclusive. */
interface DelegationView {
  id: number;
  user: string;
  by: string;
  from: string;
  to: string;
}

interface RoleView {
  user: string;
  /** clerk, steward, viewer or restricted; a user name not listed is a clerk. */
  role: string;
}

const ROLES = ['clerk', 'steward', 'viewer', 'restricted'];
/** The actions the permission matrix decides. */
const ACTIONS = ['open', 'settle', 'void', 'reassign', 'close', 'reopen', 'today'];
/** Roles typed for an action: clerk and/or steward, comma separated; blank keeps the standing rule. */
const PERM = /^((clerk|steward)(\s*,\s*(clerk|steward))*)?$/;
/** A date typed as yyyy-mm-dd. */
const DATE = /^\d{4}-\d{2}-\d{2}$/;

// The sheet's settings (dated, each saved from a from-date: its clerk limit, its floor, its terms and each grade's own, each grade's grace days, its late percent, its cap, its proof threshold and each grade's, its note threshold and its review threshold; not dated: its policy date, its period numbering from, its entry date, its grace days, its escalation gap, its pot, its alert threshold, its proof kinds, its parent sheet and its permission matrix) and the roles of its user names, each with the form that sets it.
@Component({
  selector: 'app-settings',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="settings-heading">
      <h2 id="settings-heading">Settings</h2>
      <p>Policy version <strong data-testid="policy-version">{{ session.policyVersion() ?? '' }}</strong></p>
      <form (submit)="saveSettings($event)">
        <label for="setting-clerk-limit">Clerk limit (blank: no limit)</label>
        <input
          id="setting-clerk-limit"
          data-testid="setting-clerk-limit"
          inputmode="decimal"
          [value]="clerkLimit()"
          (focus)="lastFocused = 'clerkLimit'"
          (input)="clerkLimit.set(value($event)); clerkLimitEdited = true; settingsError.set(null)"
        />
        <label for="setting-clerk-limit-from">From (blank: the sheet's today)</label>
        <input
          id="setting-clerk-limit-from"
          data-testid="setting-clerk-limit-from"
          type="date"
          [value]="froms()['clerk-limit'] ?? ''"
          (input)="setFrom('clerk-limit', value($event))"
        />
        @for (g of grades(); track g) {
          <label [for]="'setting-clerk-limit-' + g">Clerk limit for grade {{ g }} (blank: the sheet's clerk limit)</label>
          <input
            [id]="'setting-clerk-limit-' + g"
            [attr.data-testid]="'setting-clerk-limit-' + g"
            inputmode="decimal"
            [value]="gradeClerkLimit()[g] ?? ''"
            (input)="setGradeClerkLimit(g, value($event)); settingsError.set(null)"
          />
          <label [for]="'setting-clerk-limit-' + g + '-from'">From (blank: the sheet's today)</label>
          <input
            [id]="'setting-clerk-limit-' + g + '-from'"
            [attr.data-testid]="'setting-clerk-limit-' + g + '-from'"
            type="date"
            [value]="froms()['clerk-limit-' + g] ?? ''"
            (input)="setFrom('clerk-limit-' + g, value($event))"
          />
        }
        <label for="setting-floor">Floor (the least a live due can be)</label>
        <input
          id="setting-floor"
          data-testid="setting-floor"
          inputmode="decimal"
          [value]="floor()"
          (focus)="lastFocused = 'floor'"
          (input)="floor.set(value($event)); floorEdited = true; settingsError.set(null)"
        />
        <label for="setting-floor-from">From (blank: the sheet's today)</label>
        <input
          id="setting-floor-from"
          data-testid="setting-floor-from"
          type="date"
          [value]="froms()['floor'] ?? ''"
          (input)="setFrom('floor', value($event))"
        />
        <label for="setting-terms">Terms (days after its date a claim is due by)</label>
        <input
          id="setting-terms"
          data-testid="setting-terms"
          inputmode="numeric"
          [value]="terms()"
          (focus)="lastFocused = 'terms'"
          (input)="terms.set(value($event)); termsEdited = true; settingsError.set(null)"
        />
        <label for="setting-terms-from">From (blank: the sheet's today)</label>
        <input
          id="setting-terms-from"
          data-testid="setting-terms-from"
          type="date"
          [value]="froms()['terms'] ?? ''"
          (input)="setFrom('terms', value($event))"
        />
        @for (g of grades(); track g) {
          <label [for]="'setting-terms-' + g">Terms for grade {{ g }} (blank: the sheet's terms)</label>
          <input
            [id]="'setting-terms-' + g"
            [attr.data-testid]="'setting-terms-' + g"
            inputmode="numeric"
            [value]="gradeTerms()[g] ?? ''"
            (input)="setGradeTerms(g, value($event)); settingsError.set(null)"
          />
          <label [for]="'setting-terms-' + g + '-from'">From (blank: the sheet's today)</label>
          <input
            [id]="'setting-terms-' + g + '-from'"
            [attr.data-testid]="'setting-terms-' + g + '-from'"
            type="date"
            [value]="froms()['terms-' + g] ?? ''"
            (input)="setFrom('terms-' + g, value($event))"
          />
        }
        @for (g of grades(); track g) {
          <label [for]="'setting-grace-' + g">Grace days for grade {{ g }} (days past its due-by before a claim is overdue; blank: none)</label>
          <input
            [id]="'setting-grace-' + g"
            [attr.data-testid]="'setting-grace-' + g"
            inputmode="numeric"
            [value]="gradeGrace()[g] ?? ''"
            (input)="setGradeGrace(g, value($event)); settingsError.set(null)"
          />
          <label [for]="'setting-grace-' + g + '-from'">From (blank: the sheet's today)</label>
          <input
            [id]="'setting-grace-' + g + '-from'"
            [attr.data-testid]="'setting-grace-' + g + '-from'"
            type="date"
            [value]="froms()['grace-' + g] ?? ''"
            (input)="setFrom('grace-' + g, value($event))"
          />
        }
        <label for="setting-late-pct">Late percent (of an overdue claim's due, compounding every full 30 days)</label>
        <input
          id="setting-late-pct"
          data-testid="setting-late-pct"
          inputmode="decimal"
          [value]="latePct()"
          (focus)="lastFocused = 'latePct'"
          (input)="latePct.set(value($event)); latePctEdited = true; settingsError.set(null)"
        />
        <label for="setting-late-pct-from">From (blank: the sheet's today)</label>
        <input
          id="setting-late-pct-from"
          data-testid="setting-late-pct-from"
          type="date"
          [value]="froms()['late-pct'] ?? ''"
          (input)="setFrom('late-pct', value($event))"
        />
        <label for="setting-policy-date">Policy date (claims before it round half up, others half to even; blank: today)</label>
        <input
          id="setting-policy-date"
          data-testid="setting-policy-date"
          type="date"
          [value]="policyDate()"
          (focus)="lastFocused = 'policyDate'"
          (input)="policyDate.set(value($event)); policyDateEdited = true; settingsError.set(null)"
        />
        <label for="setting-period-numbering-from">Period numbering from (claims opened from then are numbered within their period; blank: off)</label>
        <input
          id="setting-period-numbering-from"
          data-testid="setting-period-numbering-from"
          type="date"
          [value]="periodNumberingFrom()"
          (focus)="lastFocused = 'periodNumberingFrom'"
          (input)="periodNumberingFrom.set(value($event)); periodNumberingFromEdited = true; settingsError.set(null)"
        />
        <label for="setting-entry-date">Entry date (the default date of a new claim; blank: off, the sheet's today)</label>
        <input
          id="setting-entry-date"
          data-testid="setting-entry-date"
          type="date"
          [value]="entryDate()"
          (focus)="lastFocused = 'entryDate'"
          (input)="entryDate.set(value($event)); entryDateEdited = true; settingsError.set(null)"
        />
        <label for="setting-grace-days">Grace days (an open period closes once today is past its end by more; blank: off)</label>
        <input
          id="setting-grace-days"
          data-testid="setting-grace-days"
          inputmode="numeric"
          [value]="graceDays()"
          (focus)="lastFocused = 'graceDays'"
          (input)="graceDays.set(value($event)); graceDaysEdited = true; settingsError.set(null)"
        />
        <label for="setting-escalate-after">Escalate after (days since a claim's previous reminder for a reminder to escalate it; blank: off)</label>
        <input
          id="setting-escalate-after"
          data-testid="setting-escalate-after"
          inputmode="numeric"
          [value]="escalateAfter()"
          (focus)="lastFocused = 'escalateAfter'"
          (input)="escalateAfter.set(value($event)); escalateAfterEdited = true; settingsError.set(null)"
        />
        <label for="setting-max-open">Most open claims per person (OPEN or PENDING; blank: none)</label>
        <input
          id="setting-max-open"
          data-testid="setting-max-open"
          inputmode="numeric"
          [value]="maxOpen()"
          (focus)="lastFocused = 'maxOpen'"
          (input)="maxOpen.set(value($event)); maxOpenEdited = true; settingsError.set(null)"
        />
        <label for="setting-review-sla">Review SLA (days a claim may wait in review after its review day; blank: none)</label>
        <input
          id="setting-review-sla"
          data-testid="setting-review-sla"
          inputmode="numeric"
          [value]="reviewSla()"
          (focus)="lastFocused = 'reviewSla'"
          (input)="reviewSla.set(value($event)); reviewSlaEdited = true; settingsError.set(null)"
        />
        <label for="setting-pending-sla">Pending SLA (days a claim may wait pending after its pending day; blank: none)</label>
        <input
          id="setting-pending-sla"
          data-testid="setting-pending-sla"
          inputmode="numeric"
          [value]="pendingSla()"
          (focus)="lastFocused = 'pendingSla'"
          (input)="pendingSla.set(value($event)); pendingSlaEdited = true; settingsError.set(null)"
        />
        <label for="setting-score-mid">Mid score threshold (whole number; blank: no bands)</label>
        <input
          id="setting-score-mid"
          data-testid="setting-score-mid"
          inputmode="numeric"
          [value]="scoreMid()"
          (focus)="lastFocused = 'scoreMid'"
          (input)="scoreMid.set(value($event)); scoreMidEdited = true; settingsError.set(null)"
        />
        <label for="setting-score-high">High score threshold (whole number; blank: no bands)</label>
        <input
          id="setting-score-high"
          data-testid="setting-score-high"
          inputmode="numeric"
          [value]="scoreHigh()"
          (focus)="lastFocused = 'scoreHigh'"
          (input)="scoreHigh.set(value($event)); scoreHighEdited = true; settingsError.set(null)"
        />
        <label for="setting-score-log">Score log (on, or blank: off)</label>
        <input
          id="setting-score-log"
          data-testid="setting-score-log"
          [value]="scoreLog()"
          (focus)="lastFocused = 'scoreLog'"
          (input)="scoreLog.set(value($event)); scoreLogEdited = true; settingsError.set(null)"
        />
        <label for="setting-fiscal-start-month">Fiscal year start month (1 to 12)</label>
        <input
          id="setting-fiscal-start-month"
          data-testid="setting-fiscal-start-month"
          inputmode="numeric"
          [value]="fiscalStartMonth()"
          (focus)="lastFocused = 'fiscalStartMonth'"
          (input)="fiscalStartMonth.set(value($event)); fiscalStartMonthEdited = true; settingsError.set(null)"
        />
        <label for="setting-pot">Pot (shared out over each balanced pool's members by their shares; blank: none)</label>
        <input
          id="setting-pot"
          data-testid="setting-pot"
          inputmode="decimal"
          [value]="pot()"
          (focus)="lastFocused = 'pot'"
          (input)="pot.set(value($event)); potEdited = true; settingsError.set(null)"
        />
        <label for="setting-alert-threshold">Alert threshold (a person whose due-total goes above it is reported; blank: off)</label>
        <input
          id="setting-alert-threshold"
          data-testid="setting-alert-threshold"
          inputmode="decimal"
          [value]="alertThreshold()"
          (focus)="lastFocused = 'alertThreshold'"
          (input)="alertThreshold.set(value($event)); alertThresholdEdited = true; settingsError.set(null)"
        />
        <label for="setting-proof-kinds">Proof kinds (comma separated, receipt or photo: a claim above the proof threshold needs a proof of each; blank: none)</label>
        <input
          id="setting-proof-kinds"
          data-testid="setting-proof-kinds"
          [value]="proofKinds()"
          (focus)="lastFocused = 'proofKinds'"
          (input)="proofKinds.set(value($event)); proofKindsEdited = true; settingsError.set(null)"
        />
        <label for="setting-default-grade">Default grade (one of the sheet's grades: given by quick add and an import line with a blank grade; blank: A)</label>
        <input
          id="setting-default-grade"
          data-testid="setting-default-grade"
          [value]="defaultGrade()"
          (focus)="lastFocused = 'defaultGrade'"
          (input)="defaultGrade.set(value($event)); defaultGradeEdited = true; settingsError.set(null)"
        />
        <label for="setting-parent">Parent sheet (its rates apply to a grade with none in force here; blank: none)</label>
        <input
          id="setting-parent"
          data-testid="setting-parent"
          [value]="parent()"
          (focus)="lastFocused = 'parent'"
          (input)="parent.set(value($event)); parentEdited = true; settingsError.set(null)"
        />
        <label for="setting-cap">Cap (the most a person's due-total can be; blank: no cap)</label>
        <input
          id="setting-cap"
          data-testid="setting-cap"
          inputmode="decimal"
          [value]="cap()"
          (focus)="lastFocused = 'cap'"
          (input)="cap.set(value($event)); capEdited = true; settingsError.set(null)"
        />
        <label for="setting-cap-from">From (blank: the sheet's today)</label>
        <input
          id="setting-cap-from"
          data-testid="setting-cap-from"
          type="date"
          [value]="froms()['cap'] ?? ''"
          (input)="setFrom('cap', value($event))"
        />
        <label for="setting-proof-threshold">Proof threshold (a claim due above it needs a proof to settle; blank: none)</label>
        <input
          id="setting-proof-threshold"
          data-testid="setting-proof-threshold"
          inputmode="decimal"
          [value]="proofThreshold()"
          (focus)="lastFocused = 'proofThreshold'"
          (input)="proofThreshold.set(value($event)); proofThresholdEdited = true; settingsError.set(null)"
        />
        <label for="setting-proof-threshold-from">From (blank: the sheet's today)</label>
        <input
          id="setting-proof-threshold-from"
          data-testid="setting-proof-threshold-from"
          type="date"
          [value]="froms()['proof-threshold'] ?? ''"
          (input)="setFrom('proof-threshold', value($event))"
        />
        @for (g of grades(); track g) {
          <label [for]="'setting-proof-threshold-' + g">Proof threshold for grade {{ g }} (blank: the sheet's proof threshold)</label>
          <input
            [id]="'setting-proof-threshold-' + g"
            [attr.data-testid]="'setting-proof-threshold-' + g"
            inputmode="decimal"
            [value]="gradeProofThreshold()[g] ?? ''"
            (input)="setGradeProofThreshold(g, value($event)); settingsError.set(null)"
          />
          <label [for]="'setting-proof-threshold-' + g + '-from'">From (blank: the sheet's today)</label>
          <input
            [id]="'setting-proof-threshold-' + g + '-from'"
            [attr.data-testid]="'setting-proof-threshold-' + g + '-from'"
            type="date"
            [value]="froms()['proof-threshold-' + g] ?? ''"
            (input)="setFrom('proof-threshold-' + g, value($event))"
          />
        }
        <label for="setting-note-threshold">Note threshold (a claim due above it needs a note to open; blank: none)</label>
        <input
          id="setting-note-threshold"
          data-testid="setting-note-threshold"
          inputmode="decimal"
          [value]="noteThreshold()"
          (focus)="lastFocused = 'noteThreshold'"
          (input)="noteThreshold.set(value($event)); noteThresholdEdited = true; settingsError.set(null)"
        />
        <label for="setting-note-threshold-from">From (blank: the sheet's today)</label>
        <input
          id="setting-note-threshold-from"
          data-testid="setting-note-threshold-from"
          type="date"
          [value]="froms()['note-threshold'] ?? ''"
          (input)="setFrom('note-threshold', value($event))"
        />
        <label for="setting-review-threshold">Review threshold (a claim due above it goes to review when opened; blank: none)</label>
        <input
          id="setting-review-threshold"
          data-testid="setting-review-threshold"
          inputmode="decimal"
          [value]="reviewThreshold()"
          (focus)="lastFocused = 'reviewThreshold'"
          (input)="reviewThreshold.set(value($event)); reviewThresholdEdited = true; settingsError.set(null)"
        />
        <label for="setting-review-threshold-from">From (blank: the sheet's today)</label>
        <input
          id="setting-review-threshold-from"
          data-testid="setting-review-threshold-from"
          type="date"
          [value]="froms()['review-threshold'] ?? ''"
          (input)="setFrom('review-threshold', value($event))"
        />
        <label for="setting-rounding">Rounding mode (half-up or half-even; blank: by the policy date)</label>
        <input
          id="setting-rounding"
          data-testid="setting-rounding"
          [value]="rounding()"
          (focus)="lastFocused = 'rounding'"
          (input)="rounding.set(value($event)); roundingEdited = true; settingsError.set(null)"
        />
        <label for="setting-rounding-from">From (blank: the sheet's today)</label>
        <input
          id="setting-rounding-from"
          data-testid="setting-rounding-from"
          type="date"
          [value]="froms()['rounding'] ?? ''"
          (input)="setFrom('rounding', value($event))"
        />
        <fieldset>
          <legend>Permissions (roles that may take each action, comma separated; blank: the standing rule)</legend>
          @for (a of actions; track a) {
            <label [for]="'setting-perm-' + a">Who may {{ a }} (clerk, steward)</label>
            <input
              [id]="'setting-perm-' + a"
              [attr.data-testid]="'setting-perm-' + a"
              [value]="perms()[a]"
              (focus)="focusPerm(a)"
              (input)="setPerm(a, value($event)); settingsError.set(null)"
            />
          }
        </fieldset>
        <button type="submit" data-testid="settings-save">Save settings</button>
        @if (settingsError(); as message) {
          <p role="alert" data-testid="settings-error">{{ message | fig }}</p>
        }
      </form>
      <p>Clerk limit in force: <strong data-testid="setting-clerk-limit-current">{{ session.settingOn('clerk-limit', session.today()) ?? '' | fig }}</strong></p>
      @for (g of grades(); track g) {
        <p>Clerk limit for grade {{ g }} in force: <strong [attr.data-testid]="'setting-clerk-limit-' + g + '-current'">{{ session.settingOn('clerk-limit-' + g, session.today()) ?? '' | fig }}</strong></p>
      }
      <p>Floor in force: <strong data-testid="setting-floor-current">{{ session.floor() ?? '' | fig }}</strong></p>
      <p>Terms in force: <strong data-testid="setting-terms-current">{{ session.terms() ?? '' | fig }}</strong></p>
      @for (g of grades(); track g) {
        <p>Terms for grade {{ g }} in force: <strong [attr.data-testid]="'setting-terms-' + g + '-current'">{{ session.settingOn('terms-' + g, session.today()) ?? '' | fig }}</strong></p>
      }
      @for (g of grades(); track g) {
        <p>Grace days for grade {{ g }} in force: <strong [attr.data-testid]="'setting-grace-' + g + '-current'">{{ session.settingOn('grace-' + g, session.today()) ?? '' | fig }}</strong></p>
      }
      <p>Late percent in force: <strong data-testid="setting-late-pct-current">{{ session.latePct() ?? '' | fig }}</strong></p>
      <p>Policy date in force: <strong data-testid="setting-policy-date-current">{{ session.policyDate() ?? '' | fig }}</strong></p>
      <p>Period numbering from: <strong data-testid="setting-period-numbering-from-current">{{ periodNumberingFromCurrent() ?? '' | fig }}</strong></p>
      <p>Entry date: <strong data-testid="setting-entry-date-current">{{ session.entryDate() ?? '' | fig }}</strong></p>
      <p>Grace days: <strong data-testid="setting-grace-days-current">{{ graceDaysCurrent() ?? '' | fig }}</strong></p>
      <p>Escalate after: <strong data-testid="setting-escalate-after-current">{{ escalateAfterCurrent() ?? '' | fig }}</strong></p>
      <p>Most open claims per person: <strong data-testid="setting-max-open-current">{{ maxOpenCurrent() ?? '' | fig }}</strong></p>
      <p>Review SLA: <strong data-testid="setting-review-sla-current">{{ session.reviewSla() ?? '' | fig }}</strong></p>
      <p>Pending SLA: <strong data-testid="setting-pending-sla-current">{{ session.pendingSla() ?? '' | fig }}</strong></p>
      <p>Mid score threshold: <strong data-testid="setting-score-mid-current">{{ session.scoreMid() ?? '' | fig }}</strong></p>
      <p>High score threshold: <strong data-testid="setting-score-high-current">{{ session.scoreHigh() ?? '' | fig }}</strong></p>
      <p>Score log: <strong data-testid="setting-score-log-current">{{ scoreLogCurrent() ?? '' | fig }}</strong></p>
      <p>Fiscal year start month: <strong data-testid="setting-fiscal-start-month-current">{{ fiscalStartMonthCurrent() ?? '' | fig }}</strong></p>
      <p>Pot: <strong data-testid="setting-pot-current">{{ session.pot() ?? '' | fig }}</strong></p>
      <p>Alert threshold: <strong data-testid="setting-alert-threshold-current">{{ alertThresholdCurrent() ?? '' | fig }}</strong></p>
      <p>Proof kinds: <strong data-testid="setting-proof-kinds-current">{{ proofKindsCurrent() ?? '' }}</strong></p>
      <p>Default grade: <strong data-testid="setting-default-grade-current">{{ defaultGradeCurrent() ?? '' }}</strong></p>
      <p>Parent sheet: <strong data-testid="setting-parent-current">{{ session.parent() ?? '' | fig }}</strong></p>
      <p>Cap in force: <strong data-testid="setting-cap-current">{{ session.cap() ?? '' | fig }}</strong></p>
      @for (a of actions; track a) {
        <p>Who may {{ a }}: <strong [attr.data-testid]="'setting-perm-' + a + '-current'">{{ permsCurrent()[a] ?? '' | fig }}</strong></p>
      }
      <p>Proof threshold in force: <strong data-testid="setting-proof-threshold-current">{{ session.settingOn('proof-threshold', session.today()) ?? '' | fig }}</strong></p>
      @for (g of grades(); track g) {
        <p>Proof threshold for grade {{ g }} in force: <strong [attr.data-testid]="'setting-proof-threshold-' + g + '-current'">{{ session.settingOn('proof-threshold-' + g, session.today()) ?? '' | fig }}</strong></p>
      }
      <p>Note threshold in force: <strong data-testid="setting-note-threshold-current">{{ session.settingOn('note-threshold', session.today()) ?? '' | fig }}</strong></p>
      <p>Review threshold in force: <strong data-testid="setting-review-threshold-current">{{ session.settingOn('review-threshold', session.today()) ?? '' | fig }}</strong></p>
      <p>Rounding mode in force: <strong data-testid="setting-rounding-current">{{ session.settingOn('rounding', session.today()) ?? '' | fig }}</strong></p>
    </section>
    <section aria-labelledby="roles-heading">
      <h2 id="roles-heading">Roles</h2>
      <form (submit)="saveRole($event)">
        <label for="role-form-user">User name</label>
        <input
          id="role-form-user"
          data-testid="role-form-user"
          [value]="roleUser()"
          (input)="roleUser.set(value($event)); roleError.set(null)"
        />
        <label for="role-form-role">Role (clerk, steward, viewer or restricted)</label>
        <input
          id="role-form-role"
          data-testid="role-form-role"
          list="role-form-roles"
          [value]="roleName()"
          (input)="roleName.set(value($event)); roleError.set(null)"
        />
        <datalist id="role-form-roles">
          @for (r of roles; track r) {
            <option [value]="r"></option>
          }
        </datalist>
        <button type="submit" data-testid="role-form-submit">Set role</button>
        @if (roleError(); as message) {
          <p role="alert" data-testid="role-form-error">{{ message | fig }}</p>
        }
      </form>
      <table>
        <caption>Roles set (anyone else is a clerk)</caption>
        <thead>
          <tr><th scope="col">User</th><th scope="col">Role</th></tr>
        </thead>
        <tbody>
          @for (r of roleList(); track r.user) {
            <tr [attr.data-testid]="'role-row-' + r.user">
              <th scope="row" data-testid="role-user">{{ r.user | fig }}</th>
              <td data-testid="role-value">{{ r.role | fig }}</td>
            </tr>
          }
        </tbody>
      </table>
      <h3 id="delegate-heading">Delegate the steward role</h3>
      <form (submit)="saveDelegation($event)" aria-labelledby="delegate-heading">
        <label for="delegate-user">User name</label>
        <input
          id="delegate-user"
          data-testid="delegate-user"
          [value]="delegateUser()"
          (input)="delegateUser.set(value($event)); delegateError.set(null)"
        />
        <label for="delegate-from">From (blank: today)</label>
        <input
          id="delegate-from"
          type="date"
          data-testid="delegate-from"
          [value]="delegateFrom()"
          (input)="delegateFrom.set(value($event)); delegateError.set(null)"
        />
        <label for="delegate-to">To</label>
        <input
          id="delegate-to"
          type="date"
          data-testid="delegate-to"
          [value]="delegateTo()"
          (input)="delegateTo.set(value($event)); delegateError.set(null)"
        />
        <button type="submit" data-testid="delegate-submit">Delegate</button>
        @if (delegateError(); as message) {
          <p role="alert" data-testid="delegate-error">{{ message | fig }}</p>
        }
      </form>
      <ul aria-label="Delegations">
        @for (d of delegations(); track d.id) {
          <li data-testid="delegate-line">{{ d.user }} acts as steward for {{ d.by }} from {{ d.from }} to {{ d.to }}</li>
        }
      </ul>
    </section>
  `,
})
export class Settings {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(Session);

  protected readonly roles = ROLES;
  protected readonly grades = this.session.grades;
  protected readonly actions = ACTIONS;
  /** Each action's roles as typed. */
  protected readonly perms = signal<Record<string, string>>({});
  /** Each action's roles as saved; null: the standing rule. */
  protected readonly permsCurrent = signal<Record<string, string | null>>({});
  /** The actions whose roles were typed in since the form was last saved or read. */
  protected permsEdited = new Set<string>();
  /** Each grade's terms as typed. */
  protected readonly gradeTerms = signal<Record<string, string>>({});
  /** The grades whose terms were typed in since the form was last saved or read. */
  protected gradeTermsEdited = new Set<string>();
  /** Each grade's grace days as typed. */
  protected readonly gradeGrace = signal<Record<string, string>>({});
  /** The grades whose grace days were typed in since the form was last saved or read. */
  protected gradeGraceEdited = new Set<string>();
  /** Each grade's proof threshold as typed. */
  protected readonly gradeProofThreshold = signal<Record<string, string>>({});
  /** The grades whose proof threshold was typed in since the form was last saved or read. */
  protected gradeProofThresholdEdited = new Set<string>();
  /** Each grade's clerk limit as typed. */
  protected readonly gradeClerkLimit = signal<Record<string, string>>({});
  /** The grades whose clerk limit was typed in since the form was last saved or read. */
  protected gradeClerkLimitEdited = new Set<string>();
  protected readonly clerkLimit = signal('');
  protected readonly floor = signal('');
  protected readonly terms = signal('');
  protected readonly latePct = signal('');
  protected readonly policyDate = signal('');
  protected readonly periodNumberingFrom = signal('');
  protected readonly periodNumberingFromCurrent = signal<string | null>(null);
  protected readonly entryDate = signal('');
  protected readonly graceDays = signal('');
  protected readonly graceDaysCurrent = signal<string | null>(null);
  protected readonly escalateAfter = signal('');
  protected readonly escalateAfterCurrent = signal<string | null>(null);
  protected readonly maxOpen = signal('');
  protected readonly maxOpenCurrent = signal<string | null>(null);
  protected readonly reviewSla = signal('');
  protected readonly pendingSla = signal('');
  protected readonly scoreMid = signal('');
  protected readonly scoreHigh = signal('');
  protected readonly scoreLog = signal('');
  protected readonly scoreLogCurrent = signal<string | null>(null);
  protected readonly fiscalStartMonth = signal('');
  protected readonly fiscalStartMonthCurrent = signal<string | null>(null);
  protected readonly pot = signal('');
  protected readonly alertThreshold = signal('');
  protected readonly alertThresholdCurrent = signal<string | null>(null);
  protected readonly proofKinds = signal('');
  protected readonly proofKindsCurrent = signal<string | null>(null);
  protected readonly defaultGrade = signal('');
  protected readonly defaultGradeCurrent = signal<string | null>(null);
  protected readonly parent = signal('');
  protected readonly cap = signal('');
  protected readonly proofThreshold = signal('');
  protected readonly noteThreshold = signal('');
  protected readonly reviewThreshold = signal('');
  protected readonly rounding = signal('');
  /** The from-date typed for each dated setting, by key; blank: the sheet's today. */
  protected readonly froms = signal<Record<string, string>>({});
  /** Which settings were typed in since the form was last saved or read: a save sends only those. */
  protected clerkLimitEdited = false;
  protected floorEdited = false;
  protected termsEdited = false;
  protected latePctEdited = false;
  protected policyDateEdited = false;
  protected periodNumberingFromEdited = false;
  protected entryDateEdited = false;
  protected graceDaysEdited = false;
  protected escalateAfterEdited = false;
  protected maxOpenEdited = false;
  protected reviewSlaEdited = false;
  protected pendingSlaEdited = false;
  protected scoreMidEdited = false;
  protected scoreHighEdited = false;
  protected scoreLogEdited = false;
  protected fiscalStartMonthEdited = false;
  protected potEdited = false;
  protected alertThresholdEdited = false;
  protected proofKindsEdited = false;
  protected defaultGradeEdited = false;
  protected parentEdited = false;
  protected capEdited = false;
  protected proofThresholdEdited = false;
  protected noteThresholdEdited = false;
  protected reviewThresholdEdited = false;
  protected roundingEdited = false;
  /** The setting whose field was focused last: a save with nothing typed saves it as it stands. */
  protected lastFocused: 'clerkLimit' | 'floor' | 'terms' | 'latePct' | 'policyDate' | 'periodNumberingFrom' | 'entryDate' | 'graceDays' | 'escalateAfter' | 'maxOpen' | 'reviewSla' | 'pendingSla' | 'scoreMid' | 'scoreHigh' | 'scoreLog' | 'fiscalStartMonth' | 'pot' | 'alertThreshold' | 'proofKinds' | 'defaultGrade' | 'parent' | 'cap' | 'proofThreshold' | 'noteThreshold' | 'reviewThreshold' | 'rounding' | `perm-${string}` | null =
    null;
  protected readonly settingsError = signal<string | null>(null);
  protected readonly roleList = signal<RoleView[]>([]);
  protected readonly roleUser = signal('');
  protected readonly roleName = signal('');
  protected readonly roleError = signal<string | null>(null);
  protected readonly delegations = signal<DelegationView[]>([]);
  protected readonly delegateUser = signal('');
  protected readonly delegateFrom = signal('');
  protected readonly delegateTo = signal('');
  protected readonly delegateError = signal<string | null>(null);
  private savingDelegation = false;
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private savingSettings = false;
  private savingRole = false;
  private reads = 0;

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loaded();
      untracked(() => {
        this.session.dated.set(null);
        this.session.policyVersions.set(null);
        this.session.policyDate.set(null);
        this.periodNumberingFromCurrent.set(null);
        this.session.entryDate.set(null);
        this.graceDaysCurrent.set(null);
        this.escalateAfterCurrent.set(null);
        this.maxOpenCurrent.set(null);
        this.session.reviewSla.set(null);
        this.session.pendingSla.set(null);
        this.session.scoreMid.set(null);
        this.session.scoreHigh.set(null);
        this.scoreLogCurrent.set(null);
        this.fiscalStartMonthCurrent.set(null);
        this.session.pot.set(null);
        this.alertThresholdCurrent.set(null);
        this.proofKindsCurrent.set(null);
        this.defaultGradeCurrent.set(null);
        this.session.parent.set(null);
        this.permsCurrent.set({});
        this.roleList.set([]);
        this.settingsError.set(null);
        this.roleError.set(null);
        this.delegations.set([]);
        this.delegateError.set(null);
        if (sheet) void this.read(sheet);
      });
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected setGradeTerms(grade: string, terms: string): void {
    this.gradeTerms.update((all) => ({ ...all, [grade]: terms }));
    this.gradeTermsEdited.add(grade);
  }

  protected setGradeGrace(grade: string, days: string): void {
    this.gradeGrace.update((all) => ({ ...all, [grade]: days }));
    this.gradeGraceEdited.add(grade);
  }

  protected setGradeClerkLimit(grade: string, limit: string): void {
    this.gradeClerkLimit.update((all) => ({ ...all, [grade]: limit }));
    this.gradeClerkLimitEdited.add(grade);
  }

  protected setGradeProofThreshold(grade: string, threshold: string): void {
    this.gradeProofThreshold.update((all) => ({ ...all, [grade]: threshold }));
    this.gradeProofThresholdEdited.add(grade);
  }

  protected focusPerm(action: string): void {
    this.lastFocused = `perm-${action}`;
  }

  protected setPerm(action: string, roles: string): void {
    this.perms.update((all) => ({ ...all, [action]: roles }));
    this.permsEdited.add(action);
  }

  /** A from-date typed for a dated setting: a save then saves that setting from it. */
  protected setFrom(key: string, from: string): void {
    this.froms.update((all) => ({ ...all, [key]: from }));
    switch (key) {
      case 'clerk-limit': this.clerkLimitEdited = true; break;
      case 'floor': this.floorEdited = true; break;
      case 'terms': this.termsEdited = true; break;
      case 'late-pct': this.latePctEdited = true; break;
      case 'cap': this.capEdited = true; break;
      case 'proof-threshold': this.proofThresholdEdited = true; break;
      case 'note-threshold': this.noteThresholdEdited = true; break;
      case 'review-threshold': this.reviewThresholdEdited = true; break;
      case 'rounding': this.roundingEdited = true; break;
      default:
        if (key.startsWith('clerk-limit-')) this.gradeClerkLimitEdited.add(key.slice('clerk-limit-'.length));
        else if (key.startsWith('proof-threshold-')) this.gradeProofThresholdEdited.add(key.slice('proof-threshold-'.length));
        else if (key.startsWith('grace-')) this.gradeGraceEdited.add(key.slice('grace-'.length));
        else this.gradeTermsEdited.add(key.slice('terms-'.length));
    }
    this.settingsError.set(null);
  }

  protected async saveSettings(event: Event): Promise<void> {
    event.preventDefault();
    if (this.savingSettings) return;
    this.settingsError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    // Saving with nothing typed saves the setting last focused as it stands (clearing an already blank field types
    // nothing), else the clerk limit.
    const nothingTyped = !this.clerkLimitEdited && this.gradeClerkLimitEdited.size === 0 && !this.floorEdited && !this.termsEdited && this.gradeTermsEdited.size === 0 && this.gradeGraceEdited.size === 0 && this.gradeProofThresholdEdited.size === 0 && !this.latePctEdited && !this.policyDateEdited && !this.periodNumberingFromEdited && !this.entryDateEdited && !this.graceDaysEdited && !this.escalateAfterEdited && !this.maxOpenEdited && !this.reviewSlaEdited && !this.pendingSlaEdited && !this.scoreMidEdited && !this.scoreHighEdited && !this.scoreLogEdited && !this.fiscalStartMonthEdited && !this.potEdited && !this.alertThresholdEdited && !this.proofKindsEdited && !this.defaultGradeEdited && !this.parentEdited && !this.capEdited && !this.proofThresholdEdited && !this.noteThresholdEdited && !this.reviewThresholdEdited && !this.roundingEdited && this.permsEdited.size === 0;
    if (nothingTyped) {
      switch (this.lastFocused) {
        case 'clerkLimit': this.clerkLimitEdited = true; break;
        case 'floor': this.floorEdited = true; break;
        case 'terms': this.termsEdited = true; break;
        case 'latePct': this.latePctEdited = true; break;
        case 'policyDate': this.policyDateEdited = true; break;
        case 'periodNumberingFrom': this.periodNumberingFromEdited = true; break;
        case 'entryDate': this.entryDateEdited = true; break;
        case 'graceDays': this.graceDaysEdited = true; break;
        case 'escalateAfter': this.escalateAfterEdited = true; break;
        case 'maxOpen': this.maxOpenEdited = true; break;
        case 'reviewSla': this.reviewSlaEdited = true; break;
        case 'pendingSla': this.pendingSlaEdited = true; break;
        case 'scoreMid': this.scoreMidEdited = true; break;
        case 'scoreHigh': this.scoreHighEdited = true; break;
        case 'scoreLog': this.scoreLogEdited = true; break;
        case 'fiscalStartMonth': this.fiscalStartMonthEdited = true; break;
        case 'pot': this.potEdited = true; break;
        case 'alertThreshold': this.alertThresholdEdited = true; break;
        case 'proofKinds': this.proofKindsEdited = true; break;
        case 'defaultGrade': this.defaultGradeEdited = true; break;
        case 'parent': this.parentEdited = true; break;
        case 'cap': this.capEdited = true; break;
        case 'proofThreshold': this.proofThresholdEdited = true; break;
        case 'noteThreshold': this.noteThresholdEdited = true; break;
        case 'reviewThreshold': this.reviewThresholdEdited = true; break;
        case 'rounding': this.roundingEdited = true; break;
        default: if (this.lastFocused) this.permsEdited.add(this.lastFocused.slice('perm-'.length));
      }
    }
    const saveLimit = this.clerkLimitEdited || (nothingTyped && !this.lastFocused);
    const saveGradeClerkLimit = this.grades().filter((g) => this.gradeClerkLimitEdited.has(g));
    const saveFloor = this.floorEdited;
    const saveTerms = this.termsEdited;
    const saveGradeTerms = this.grades().filter((g) => this.gradeTermsEdited.has(g));
    const saveGradeGrace = this.grades().filter((g) => this.gradeGraceEdited.has(g));
    const saveGradeProofThreshold = this.grades().filter((g) => this.gradeProofThresholdEdited.has(g));
    const saveLatePct = this.latePctEdited;
    const savePolicyDate = this.policyDateEdited;
    const savePeriodNumberingFrom = this.periodNumberingFromEdited;
    const saveEntryDate = this.entryDateEdited;
    const saveGraceDays = this.graceDaysEdited;
    const saveEscalateAfter = this.escalateAfterEdited;
    const saveMaxOpen = this.maxOpenEdited;
    const saveReviewSla = this.reviewSlaEdited;
    const savePendingSla = this.pendingSlaEdited;
    const saveScoreMid = this.scoreMidEdited;
    const saveScoreHigh = this.scoreHighEdited;
    const saveScoreLog = this.scoreLogEdited;
    const saveFiscalStartMonth = this.fiscalStartMonthEdited;
    const savePot = this.potEdited;
    const saveAlertThreshold = this.alertThresholdEdited;
    const saveProofKinds = this.proofKindsEdited;
    const saveDefaultGrade = this.defaultGradeEdited;
    const saveParent = this.parentEdited;
    const saveCap = this.capEdited;
    const saveProofThreshold = this.proofThresholdEdited;
    const saveNoteThreshold = this.noteThresholdEdited;
    const saveReviewThreshold = this.reviewThresholdEdited;
    const saveRounding = this.roundingEdited;
    const savePerms = ACTIONS.filter((a) => this.permsEdited.has(a));
    const limit = this.clerkLimit().trim();
    const floor = this.floor().trim();
    const terms = this.terms().trim();
    const latePct = this.latePct().trim();
    const policyDate = this.policyDate().trim();
    const periodNumberingFrom = this.periodNumberingFrom().trim();
    const entryDate = this.entryDate().trim();
    const graceDays = this.graceDays().trim();
    const escalateAfter = this.escalateAfter().trim();
    const maxOpen = this.maxOpen().trim();
    const reviewSla = this.reviewSla().trim();
    const pendingSla = this.pendingSla().trim();
    const scoreMid = this.scoreMid().trim();
    const scoreHigh = this.scoreHigh().trim();
    const scoreLog = this.scoreLog().trim();
    const fiscalStartMonth = this.fiscalStartMonth().trim();
    const pot = this.pot().trim();
    const alertThreshold = this.alertThreshold().trim();
    const proofKinds = this.proofKinds().trim();
    const defaultGrade = this.defaultGrade().trim();
    const parent = this.parent().trim();
    const cap = this.cap().trim();
    const proofThreshold = this.proofThreshold().trim();
    const noteThreshold = this.noteThreshold().trim();
    const reviewThreshold = this.reviewThreshold().trim();
    const rounding = this.rounding().trim();
    const froms = this.froms();
    const fromOf = (key: string): string | null => (froms[key] ?? '').trim() || null;
    const saving = [
      ...(saveLimit ? ['clerk-limit'] : []),
      ...saveGradeClerkLimit.map((g) => 'clerk-limit-' + g),
      ...(saveFloor ? ['floor'] : []),
      ...(saveTerms ? ['terms'] : []),
      ...saveGradeTerms.map((g) => 'terms-' + g),
      ...saveGradeGrace.map((g) => 'grace-' + g),
      ...(saveLatePct ? ['late-pct'] : []),
      ...(saveCap ? ['cap'] : []),
      ...(saveProofThreshold ? ['proof-threshold'] : []),
      ...saveGradeProofThreshold.map((g) => 'proof-threshold-' + g),
      ...(saveNoteThreshold ? ['note-threshold'] : []),
      ...(saveReviewThreshold ? ['review-threshold'] : []),
      ...(saveRounding ? ['rounding'] : []),
    ];
    if (saving.some((key) => { const from = fromOf(key); return from !== null && !DATE.test(from); })) {
      this.settingsError.set('Type a from-date as yyyy-mm-dd, or leave it blank for the sheet\'s today.');
      return;
    }
    if (saveLimit && limit !== '' && !/^\d{1,10}(\.\d{1,2})?$/.test(limit)) {
      this.settingsError.set('Type a clerk limit of 0.00 or more with at most two decimals, or leave it blank for no limit.');
      return;
    }
    if (saveFloor && !/^\d{1,10}(\.\d{1,2})?$/.test(floor)) {
      this.settingsError.set('Type a floor of 0.00 or more with at most two decimals.');
      return;
    }
    if (saveTerms && !/^\d{1,5}$/.test(terms)) {
      this.settingsError.set('Type terms of 0 or more whole days.');
      return;
    }
    if (saveGradeTerms.some((g) => !/^\d{1,5}$/.test((this.gradeTerms()[g] ?? '').trim()))) {
      this.settingsError.set('Type terms for a grade of 0 or more whole days.');
      return;
    }
    if (saveGradeGrace.some((g) => !/^(\d{1,5})?$/.test((this.gradeGrace()[g] ?? '').trim()))) {
      this.settingsError.set('Type grace days for a grade of 0 or more whole days, or leave it blank for none.');
      return;
    }
    if (saveLatePct && !/^\d{1,3}(\.\d{1,2})?$/.test(latePct)) {
      this.settingsError.set('Type a late percent of 0.00 or more with at most two decimals.');
      return;
    }
    if (savePolicyDate && policyDate !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(policyDate)) {
      this.settingsError.set('Type a policy date as yyyy-mm-dd, or leave it blank for the sheet\'s today.');
      return;
    }
    if (savePeriodNumberingFrom && periodNumberingFrom !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(periodNumberingFrom)) {
      this.settingsError.set('Type a period numbering from date as yyyy-mm-dd, or leave it blank for off.');
      return;
    }
    if (saveEntryDate && entryDate !== '' && !DATE.test(entryDate)) {
      this.settingsError.set('Type an entry date as yyyy-mm-dd, or leave it blank for off.');
      return;
    }
    if (saveGraceDays && graceDays !== '' && !/^\d{1,5}$/.test(graceDays)) {
      this.settingsError.set('Type grace days of 0 or more whole days, or leave it blank for off.');
      return;
    }
    if (saveEscalateAfter && escalateAfter !== '' && !/^\d{1,5}$/.test(escalateAfter)) {
      this.settingsError.set('Type an escalation gap of 0 or more whole days, or leave it blank for off.');
      return;
    }
    if (saveMaxOpen && maxOpen !== '' && !/^\d{1,5}$/.test(maxOpen)) {
      this.settingsError.set('Type a maximum of 0 or more open claims as a whole number, or leave it blank for none.');
      return;
    }
    if (saveReviewSla && reviewSla !== '' && !/^\d{1,5}$/.test(reviewSla)) {
      this.settingsError.set('Type a review SLA of 0 or more whole days, or leave it blank for none.');
      return;
    }
    if (savePendingSla && pendingSla !== '' && !/^\d{1,5}$/.test(pendingSla)) {
      this.settingsError.set('Type a pending SLA of 0 or more whole days, or leave it blank for none.');
      return;
    }
    if (saveScoreMid && scoreMid !== '' && !/^-?\d{1,5}$/.test(scoreMid)) {
      this.settingsError.set('Type a mid score threshold as a whole number, or leave it blank.');
      return;
    }
    if (saveScoreHigh && scoreHigh !== '' && !/^-?\d{1,5}$/.test(scoreHigh)) {
      this.settingsError.set('Type a high score threshold as a whole number, or leave it blank.');
      return;
    }
    if (saveScoreLog && scoreLog !== '' && scoreLog !== 'on') {
      this.settingsError.set('Type on for the score log, or leave it blank for off.');
      return;
    }
    if (saveFiscalStartMonth && !/^(0?[1-9]|1[0-2])$/.test(fiscalStartMonth)) {
      this.settingsError.set('Type a fiscal year start month from 1 to 12.');
      return;
    }
    if (savePot && pot !== '' && !/^\d{1,10}(\.\d{1,2})?$/.test(pot)) {
      this.settingsError.set('Type a pot of 0.00 or more with at most two decimals, or leave it blank for none.');
      return;
    }
    if (saveAlertThreshold && alertThreshold !== '' && !/^\d{1,10}(\.\d{1,2})?$/.test(alertThreshold)) {
      this.settingsError.set('Type an alert threshold of 0.00 or more with at most two decimals, or leave it blank for off.');
      return;
    }
    if (saveProofKinds && proofKinds !== '' && !/^(receipt|photo)(\s*,\s*(receipt|photo))*$/.test(proofKinds)) {
      this.settingsError.set('Type the proof kinds as a comma separated list of receipt and photo, or leave it blank for none.');
      return;
    }
    if (saveDefaultGrade && defaultGrade !== '' && !/^[ABC]$/.test(defaultGrade)) {
      this.settingsError.set('Type a default grade of A, B or C, or leave it blank for A.');
      return;
    }
    if (saveParent && parent.length > 100) {
      this.settingsError.set('Type a parent sheet id of at most 100 characters, or leave it blank for none.');
      return;
    }
    if (saveCap && cap !== '' && !/^\d{1,10}(\.\d{1,2})?$/.test(cap)) {
      this.settingsError.set('Type a cap of 0.00 or more with at most two decimals, or leave it blank for no cap.');
      return;
    }
    if (saveProofThreshold && proofThreshold !== '' && !/^\d{1,10}(\.\d{1,2})?$/.test(proofThreshold)) {
      this.settingsError.set('Type a proof threshold of 0.00 or more with at most two decimals, or leave it blank for none.');
      return;
    }
    if (saveGradeClerkLimit.some((g) => !/^(\d{1,10}(\.\d{1,2})?)?$/.test((this.gradeClerkLimit()[g] ?? '').trim()))) {
      this.settingsError.set('Type a clerk limit for a grade of 0.00 or more with at most two decimals, or leave it blank for none.');
      return;
    }
    if (saveGradeProofThreshold.some((g) => !/^(\d{1,10}(\.\d{1,2})?)?$/.test((this.gradeProofThreshold()[g] ?? '').trim()))) {
      this.settingsError.set('Type a proof threshold for a grade of 0.00 or more with at most two decimals, or leave it blank for none.');
      return;
    }
    if (saveNoteThreshold && noteThreshold !== '' && !/^\d{1,10}(\.\d{1,2})?$/.test(noteThreshold)) {
      this.settingsError.set('Type a note threshold of 0.00 or more with at most two decimals, or leave it blank for none.');
      return;
    }
    if (saveReviewThreshold && reviewThreshold !== '' && !/^\d{1,10}(\.\d{1,2})?$/.test(reviewThreshold)) {
      this.settingsError.set('Type a review threshold of 0.00 or more with at most two decimals, or leave it blank for none.');
      return;
    }
    if (saveRounding && rounding !== '' && rounding !== 'half-up' && rounding !== 'half-even') {
      this.settingsError.set('Type a rounding mode of half-up or half-even, or leave it blank for none.');
      return;
    }
    if (savePerms.some((a) => !PERM.test((this.perms()[a] ?? '').trim()))) {
      this.settingsError.set('Type the roles allowed as clerk and/or steward, comma separated, or leave it blank for the standing rule.');
      return;
    }
    this.savingSettings = true;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/settings`;
    try {
      let saved: SettingsView | null = null;
      if (saveLimit) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(url, { clerkLimit: limit === '' ? null : limit, from: fromOf('clerk-limit') }, this.session.headers()),
        );
        this.clerkLimitEdited = false;
      }
      for (const g of saveGradeClerkLimit) {
        const gradeLimit = (this.gradeClerkLimit()[g] ?? '').trim();
        saved = await firstValueFrom(
          this.http.put<SettingsView>(
            `${url}/clerk-limit/${g}`,
            { clerkLimit: gradeLimit === '' ? null : gradeLimit, from: fromOf('clerk-limit-' + g) },
            this.session.headers(),
          ),
        );
        this.gradeClerkLimitEdited.delete(g);
      }
      if (saveFloor) {
        saved = await firstValueFrom(this.http.put<SettingsView>(`${url}/floor`, { floor, from: fromOf('floor') }, this.session.headers()));
        this.floorEdited = false;
      }
      if (saveTerms) {
        saved = await firstValueFrom(this.http.put<SettingsView>(`${url}/terms`, { terms, from: fromOf('terms') }, this.session.headers()));
        this.termsEdited = false;
      }
      for (const g of saveGradeTerms) {
        const gradeTerms = (this.gradeTerms()[g] ?? '').trim();
        saved = await firstValueFrom(
          this.http.put<SettingsView>(`${url}/terms/${g}`, { terms: gradeTerms, from: fromOf('terms-' + g) }, this.session.headers()),
        );
        this.gradeTermsEdited.delete(g);
      }
      for (const g of saveGradeGrace) {
        const grace = (this.gradeGrace()[g] ?? '').trim();
        saved = await firstValueFrom(
          this.http.put<SettingsView>(
            `${url}/grace/${g}`,
            { grace: grace === '' ? null : grace, from: fromOf('grace-' + g) },
            this.session.headers(),
          ),
        );
        this.gradeGraceEdited.delete(g);
      }
      if (saveLatePct) {
        saved = await firstValueFrom(this.http.put<SettingsView>(`${url}/late-pct`, { latePct, from: fromOf('late-pct') }, this.session.headers()));
        this.latePctEdited = false;
      }
      if (savePolicyDate) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(
            `${url}/policy-date`,
            { policyDate: policyDate === '' ? null : policyDate },
            this.session.headers(),
          ),
        );
        this.policyDateEdited = false;
      }
      if (savePeriodNumberingFrom) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(
            `${url}/period-numbering-from`,
            { periodNumberingFrom: periodNumberingFrom === '' ? null : periodNumberingFrom },
            this.session.headers(),
          ),
        );
        this.periodNumberingFromEdited = false;
      }
      if (saveEntryDate) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(`${url}/entry-date`, { entryDate: entryDate === '' ? null : entryDate }, this.session.headers()),
        );
        this.entryDateEdited = false;
      }
      if (saveGraceDays) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(`${url}/grace-days`, { graceDays: graceDays === '' ? null : graceDays }, this.session.headers()),
        );
        this.graceDaysEdited = false;
      }
      if (saveEscalateAfter) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(`${url}/escalate-after`, { escalateAfter: escalateAfter === '' ? null : escalateAfter }, this.session.headers()),
        );
        this.escalateAfterEdited = false;
      }
      if (saveMaxOpen) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(`${url}/max-open`, { maxOpen: maxOpen === '' ? null : maxOpen }, this.session.headers()),
        );
        this.maxOpenEdited = false;
      }
      if (saveReviewSla) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(`${url}/review-sla`, { reviewSla: reviewSla === '' ? null : reviewSla }, this.session.headers()),
        );
        this.reviewSlaEdited = false;
      }
      if (savePendingSla) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(`${url}/pending-sla`, { pendingSla: pendingSla === '' ? null : pendingSla }, this.session.headers()),
        );
        this.pendingSlaEdited = false;
      }
      if (saveScoreMid) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(`${url}/score-mid`, { score: scoreMid === '' ? null : scoreMid }, this.session.headers()),
        );
        this.scoreMidEdited = false;
      }
      if (saveScoreHigh) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(`${url}/score-high`, { score: scoreHigh === '' ? null : scoreHigh }, this.session.headers()),
        );
        this.scoreHighEdited = false;
      }
      if (saveScoreLog) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(`${url}/score-log`, { scoreLog: scoreLog === '' ? null : scoreLog }, this.session.headers()),
        );
        this.scoreLogEdited = false;
      }
      if (saveFiscalStartMonth) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(`${url}/fiscal-start-month`, { fiscalStartMonth }, this.session.headers()),
        );
        this.fiscalStartMonthEdited = false;
      }
      if (savePot) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(`${url}/pot`, { pot: pot === '' ? null : pot }, this.session.headers()),
        );
        this.potEdited = false;
      }
      if (saveAlertThreshold) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(`${url}/alert-threshold`, { alertThreshold: alertThreshold === '' ? null : alertThreshold }, this.session.headers()),
        );
        this.alertThresholdEdited = false;
      }
      if (saveProofKinds) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(`${url}/proof-kinds`, { proofKinds: proofKinds === '' ? null : proofKinds }, this.session.headers()),
        );
        this.proofKindsEdited = false;
      }
      if (saveDefaultGrade) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(`${url}/default-grade`, { defaultGrade: defaultGrade === '' ? null : defaultGrade }, this.session.headers()),
        );
        this.defaultGradeEdited = false;
      }
      if (saveParent) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(`${url}/parent`, { parent: parent === '' ? null : parent }, this.session.headers()),
        );
        this.parentEdited = false;
      }
      if (saveCap) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(`${url}/cap`, { cap: cap === '' ? null : cap, from: fromOf('cap') }, this.session.headers()),
        );
        this.capEdited = false;
      }
      if (saveProofThreshold) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(
            `${url}/proof-threshold`,
            { proofThreshold: proofThreshold === '' ? null : proofThreshold, from: fromOf('proof-threshold') },
            this.session.headers(),
          ),
        );
        this.proofThresholdEdited = false;
      }
      for (const g of saveGradeProofThreshold) {
        const threshold = (this.gradeProofThreshold()[g] ?? '').trim();
        saved = await firstValueFrom(
          this.http.put<SettingsView>(
            `${url}/proof-threshold/${g}`,
            { proofThreshold: threshold === '' ? null : threshold, from: fromOf('proof-threshold-' + g) },
            this.session.headers(),
          ),
        );
        this.gradeProofThresholdEdited.delete(g);
      }
      if (saveNoteThreshold) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(
            `${url}/note-threshold`,
            { noteThreshold: noteThreshold === '' ? null : noteThreshold, from: fromOf('note-threshold') },
            this.session.headers(),
          ),
        );
        this.noteThresholdEdited = false;
      }
      if (saveReviewThreshold) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(
            `${url}/review-threshold`,
            { reviewThreshold: reviewThreshold === '' ? null : reviewThreshold, from: fromOf('review-threshold') },
            this.session.headers(),
          ),
        );
        this.reviewThresholdEdited = false;
      }
      if (saveRounding) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(
            `${url}/rounding`,
            { rounding: rounding === '' ? null : rounding, from: fromOf('rounding') },
            this.session.headers(),
          ),
        );
        this.roundingEdited = false;
      }
      for (const a of savePerms) {
        const roles = (this.perms()[a] ?? '').trim();
        saved = await firstValueFrom(
          this.http.put<SettingsView>(`${url}/perm/${a}`, { roles: roles === '' ? null : roles }, this.session.headers()),
        );
        this.permsEdited.delete(a);
      }
      if (saved && this.session.sheet() === sheet) this.show(saved);
    } catch (e) {
      this.settingsError.set(
        saveParent && e instanceof HttpErrorResponse && e.status === 422
          ? 'The parent sheet must be a sheet that has been loaded, not this sheet nor one that has this sheet as an ancestor.'
          : 'The settings could not be saved.',
      );
    } finally {
      this.savingSettings = false;
    }
  }

  protected async saveRole(event: Event): Promise<void> {
    event.preventDefault();
    if (this.savingRole) return;
    this.roleError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const user = this.roleUser().trim();
    const role = this.roleName().trim();
    if (!user) {
      this.roleError.set('Type the user name to give a role.');
      return;
    }
    if (!ROLES.includes(role)) {
      this.roleError.set('A role is clerk, steward, viewer or restricted.');
      return;
    }
    this.savingRole = true;
    try {
      const saved = await firstValueFrom(
        this.http.put<RoleView>(`/api/sheets/${encodeURIComponent(sheet)}/roles`, { user, role }, this.session.headers()),
      );
      if (this.session.sheet() === sheet) {
        this.roleList.update((list) =>
          [...list.filter((r) => r.user !== saved.user), saved].sort((a, b) => (a.user < b.user ? -1 : a.user > b.user ? 1 : 0)),
        );
        this.roleUser.set('');
        this.roleName.set('');
      }
    } catch {
      this.roleError.set('The role could not be set.');
    } finally {
      this.savingRole = false;
    }
  }

  protected async saveDelegation(event: Event): Promise<void> {
    event.preventDefault();
    if (this.savingDelegation) return;
    this.delegateError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const user = this.delegateUser().trim();
    const from = this.delegateFrom().trim();
    const to = this.delegateTo().trim();
    if (!user) {
      this.delegateError.set('Type the user name to delegate to.');
      return;
    }
    if (!to) {
      this.delegateError.set('Type the date the delegation ends.');
      return;
    }
    if (to < (from || this.session.sheetToday() || '')) {
      this.delegateError.set('The to-date cannot be before the from-date.');
      return;
    }
    this.savingDelegation = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<DelegationView>(
          `/api/sheets/${encodeURIComponent(sheet)}/delegations`,
          { user, from, to },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() === sheet) {
        this.delegations.update((list) => [...list, saved]);
        this.delegateUser.set('');
        this.delegateFrom.set('');
        this.delegateTo.set('');
      }
    } catch {
      this.delegateError.set('The steward role could not be delegated: only a steward may delegate it.');
    } finally {
      this.savingDelegation = false;
    }
  }

  private async read(sheet: string): Promise<void> {
    const read = ++this.reads;
    const load = this.session.loaded();
    const url = `/api/sheets/${encodeURIComponent(sheet)}`;
    const [settings, roles, delegations] = await Promise.all([
      firstValueFrom(this.http.get<SettingsView>(`${url}/settings`, this.session.headers())),
      firstValueFrom(this.http.get<RoleView[]>(`${url}/roles`, this.session.headers())),
      firstValueFrom(this.http.get<DelegationView[]>(`${url}/delegations`, this.session.headers())),
    ]).catch(() => [null, null, null]);
    // A later read wins over a slower earlier one.
    if (settings && roles && delegations && read === this.reads && this.session.sheet() === sheet) {
      this.show(settings);
      this.roleList.set(roles);
      this.delegations.set(delegations);
      this.session.shown('settings', load);
    }
  }

  /** Show the settings in force on the sheet's today, and put them back in the form with no from-dates. */
  private show(settings: SettingsView): void {
    this.session.dated.set(settings.dated);
    this.session.policyVersions.set(settings.policyVersions);
    this.clerkLimit.set(settings.clerkLimit ?? '');
    this.floor.set(settings.floor);
    this.terms.set(String(settings.terms));
    const gradeTerms: Record<string, string> = {};
    for (const [g, t] of Object.entries(settings.gradeTerms)) gradeTerms[g] = String(t);
    this.gradeTerms.set(gradeTerms);
    const gradeGrace: Record<string, string> = {};
    for (const g of this.grades()) gradeGrace[g] = this.session.settingOn('grace-' + g, this.session.today()) ?? '';
    this.gradeGrace.set(gradeGrace);
    const gradeClerkLimit: Record<string, string> = {};
    for (const g of this.grades()) gradeClerkLimit[g] = this.session.settingOn('clerk-limit-' + g, this.session.today()) ?? '';
    this.gradeClerkLimit.set(gradeClerkLimit);
    const gradeProofThreshold: Record<string, string> = {};
    for (const g of this.grades()) gradeProofThreshold[g] = this.session.settingOn('proof-threshold-' + g, this.session.today()) ?? '';
    this.gradeProofThreshold.set(gradeProofThreshold);
    this.latePct.set(settings.latePct);
    this.froms.set({});
    this.session.policyDate.set(settings.policyDate ?? '');
    this.policyDate.set(settings.policyDate ?? '');
    this.periodNumberingFromCurrent.set(settings.periodNumberingFrom ?? '');
    this.periodNumberingFrom.set(settings.periodNumberingFrom ?? '');
    this.session.entryDate.set(settings.entryDate ?? '');
    this.entryDate.set(settings.entryDate ?? '');
    this.graceDaysCurrent.set(settings.graceDays === null ? '' : String(settings.graceDays));
    this.graceDays.set(settings.graceDays === null ? '' : String(settings.graceDays));
    this.escalateAfterCurrent.set(settings.escalateAfter === null ? '' : String(settings.escalateAfter));
    this.escalateAfter.set(settings.escalateAfter === null ? '' : String(settings.escalateAfter));
    this.maxOpenCurrent.set(settings.maxOpen === null ? '' : String(settings.maxOpen));
    this.maxOpen.set(settings.maxOpen === null ? '' : String(settings.maxOpen));
    this.session.reviewSla.set(settings.reviewSla === null ? '' : String(settings.reviewSla));
    this.reviewSla.set(settings.reviewSla === null ? '' : String(settings.reviewSla));
    this.session.pendingSla.set(settings.pendingSla === null ? '' : String(settings.pendingSla));
    this.pendingSla.set(settings.pendingSla === null ? '' : String(settings.pendingSla));
    this.session.scoreMid.set(settings.scoreMid === null ? '' : String(settings.scoreMid));
    this.scoreMid.set(settings.scoreMid === null ? '' : String(settings.scoreMid));
    this.session.scoreHigh.set(settings.scoreHigh === null ? '' : String(settings.scoreHigh));
    this.scoreHigh.set(settings.scoreHigh === null ? '' : String(settings.scoreHigh));
    this.scoreLogCurrent.set(settings.scoreLog ? 'on' : '');
    this.scoreLog.set(settings.scoreLog ? 'on' : '');
    this.fiscalStartMonthCurrent.set(String(settings.fiscalStartMonth));
    this.fiscalStartMonth.set(String(settings.fiscalStartMonth));
    this.session.pot.set(settings.pot ?? '');
    this.pot.set(settings.pot ?? '');
    this.alertThresholdCurrent.set(settings.alertThreshold ?? '');
    this.alertThreshold.set(settings.alertThreshold ?? '');
    this.proofKindsCurrent.set(settings.proofKinds ?? '');
    this.proofKinds.set(settings.proofKinds ?? '');
    this.defaultGradeCurrent.set(settings.defaultGrade ?? '');
    this.defaultGrade.set(settings.defaultGrade ?? '');
    this.session.parent.set(settings.parent ?? '');
    this.parent.set(settings.parent ?? '');
    this.cap.set(settings.cap ?? '');
    this.proofThreshold.set(settings.proofThreshold ?? '');
    this.noteThreshold.set(settings.noteThreshold ?? '');
    this.reviewThreshold.set(settings.reviewThreshold ?? '');
    this.rounding.set(this.session.settingOn('rounding', this.session.today()) ?? '');
    this.permsCurrent.set(settings.permissions);
    const perms: Record<string, string> = {};
    for (const a of ACTIONS) perms[a] = settings.permissions[a] ?? '';
    this.perms.set(perms);
    this.permsEdited.clear();
    this.clerkLimitEdited = false;
    this.floorEdited = false;
    this.termsEdited = false;
    this.gradeTermsEdited.clear();
    this.gradeGraceEdited.clear();
    this.gradeClerkLimitEdited.clear();
    this.gradeProofThresholdEdited.clear();
    this.latePctEdited = false;
    this.policyDateEdited = false;
    this.periodNumberingFromEdited = false;
    this.entryDateEdited = false;
    this.graceDaysEdited = false;
    this.escalateAfterEdited = false;
    this.maxOpenEdited = false;
    this.reviewSlaEdited = false;
    this.pendingSlaEdited = false;
    this.scoreMidEdited = false;
    this.scoreHighEdited = false;
    this.scoreLogEdited = false;
    this.fiscalStartMonthEdited = false;
    this.potEdited = false;
    this.alertThresholdEdited = false;
    this.proofKindsEdited = false;
    this.defaultGradeEdited = false;
    this.parentEdited = false;
    this.capEdited = false;
    this.proofThresholdEdited = false;
    this.noteThresholdEdited = false;
    this.reviewThresholdEdited = false;
    this.roundingEdited = false;
  }
}
