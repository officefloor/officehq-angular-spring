import { Component, inject, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { EMPTY, Observable, concat, last, switchMap } from 'rxjs';
import { GRADES, StandingGrade } from '../../core/people';
import { Rates } from '../../core/rates';
import { ROLES, RoleName, Roles } from '../../core/roles';
import { PERM_ACTIONS, PermAction, STANDING_PERMISSIONS, SheetView, Sheets } from '../../core/sheet';
import { FigurePipe } from '../../core/figure';
import { PROOF_KINDS } from '../../core/claims';

/** A clerk limit the form accepts: an amount of 0.00 or more with up to two decimals. */
const LIMIT_PATTERN = /^\d{1,12}(\.\d{1,2})?$/;
/** Terms the form accepts: a whole number of days, 0 or more. */
const TERMS_PATTERN = /^\d{1,5}$/;
/** A score threshold the form accepts: a whole number, negative or not. */
const SCORE_PATTERN = /^-?\d{1,9}$/;
/** A late percent the form accepts: 0.00 or more, up to 999.99, with up to two decimals. */
const LATE_PCT_PATTERN = /^\d{1,3}(\.\d{1,2})?$/;
/** A parent sheet id the form accepts: letters, digits, dots, underscores and dashes, up to 100 of them. */
const SHEET_ID_PATTERN = /^[A-Za-z0-9._-]{1,100}$/;
/** A policy date the form accepts: yyyy-mm-dd. */
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;
/** The rounding modes the form accepts. */
const ROUNDING_MODES = ['half-up', 'half-even'];

/** A field of the settings form. */
type SettingField = 'clerkLimit' | 'clerkLimitA' | 'clerkLimitB' | 'clerkLimitC' | 'floor' | 'terms' | 'termsA' | 'termsB' | 'termsC' | 'graceA' | 'graceB' | 'graceC' | 'proofThresholdA' | 'proofThresholdB' | 'proofThresholdC' | 'latePct' | 'policyDate' | 'periodNumberingFrom' | 'entryDate' | 'graceDays' | 'escalateAfter' | 'reviewSla' | 'pendingSla' | 'maxOpen' | 'scoreMid' | 'scoreHigh' | 'scoreLog' | 'fiscalStartMonth' | 'pot' | 'alertThreshold' | 'parent' | 'proofKinds' | 'defaultGrade' | 'cap' | 'proofThreshold' | 'noteThreshold' | 'reviewThreshold' | 'rounding' | PermField;

/** A setting that carries a figure: it is saved from a from-date. */
type DatedField = Exclude<SettingField, 'policyDate' | 'periodNumberingFrom' | 'entryDate' | 'graceDays' | 'escalateAfter' | 'reviewSla' | 'pendingSla' | 'maxOpen' | 'scoreMid' | 'scoreHigh' | 'scoreLog' | 'fiscalStartMonth' | 'pot' | 'alertThreshold' | 'parent' | 'proofKinds' | 'defaultGrade' | PermField>;

/** The from-date field of each dated setting. */
type FromField = `${DatedField}From`;

/** The form field of each grade's own terms. */
const GRADE_TERMS_FIELD: Record<StandingGrade, 'termsA' | 'termsB' | 'termsC'> = { A: 'termsA', B: 'termsB', C: 'termsC' };

/** The form field of each grade's grace days. */
const GRADE_GRACE_FIELD: Record<StandingGrade, 'graceA' | 'graceB' | 'graceC'> = { A: 'graceA', B: 'graceB', C: 'graceC' };

/** The form field of each grade's own clerk limit. */
const GRADE_CLERK_LIMIT_FIELD: Record<StandingGrade, 'clerkLimitA' | 'clerkLimitB' | 'clerkLimitC'> = { A: 'clerkLimitA', B: 'clerkLimitB', C: 'clerkLimitC' };

/** The form field of each grade's own proof threshold. */
const GRADE_PROOF_THRESHOLD_FIELD: Record<StandingGrade, 'proofThresholdA' | 'proofThresholdB' | 'proofThresholdC'> = { A: 'proofThresholdA', B: 'proofThresholdB', C: 'proofThresholdC' };

/** The form field of each action's permission entry. */
type PermField = `perm_${PermAction}`;

/** The form field of each action's permission entry. */
const PERM_FIELD = Object.fromEntries(PERM_ACTIONS.map((a) => [a, `perm_${a}`])) as { [A in PermAction]: `perm_${A}` };

/** Whether a yyyy-mm-dd text is a real calendar day. */
function isDate(value: string): boolean {
  const [y, m, d] = value.split('-').map(Number);
  const day = new Date(Date.UTC(y, m - 1, d));
  return day.getUTCFullYear() === y && day.getUTCMonth() === m - 1 && day.getUTCDate() === d;
}

// The sheet's settings (those with a figure dated, each shown as in force on the sheet's today): its clerk limit, each grade's own clerk limit, its floor, its terms, each grade's own terms, each grade's grace days, its late percent, its policy date, its period numbering from, its grace days (not dated), its pot (not dated), its alert threshold (not dated), its parent sheet (not dated), its cap, its proof threshold, its proof kinds (not dated), its note threshold, its review threshold, its permission matrix (not dated), and the role assigned to each user name.
@Component({
  selector: 'app-settings',
  imports: [ReactiveFormsModule, FigurePipe],
  template: `
    <section aria-labelledby="settings-heading">
      <h2 id="settings-heading">Settings</h2>
      <p>Clerk limit: <span data-testid="setting-clerk-limit-current">{{ (sheets.current()?.clerkLimit ?? '') | figure }}</span></p>
      @for (g of grades; track g) {
        <p>Clerk limit for grade {{ g }}: <span [attr.data-testid]="'setting-clerk-limit-' + g + '-current'">{{ (sheets.current()?.gradeClerkLimit?.[g] ?? '') | figure }}</span></p>
      }
      <p>Floor: <span data-testid="setting-floor-current">{{ (sheets.current()?.floor ?? '') | figure }}</span></p>
      <p>Terms (business days): <span data-testid="setting-terms-current">{{ sheets.current()?.terms ?? '' }}</span></p>
      @for (g of grades; track g) {
        <p>Terms for grade {{ g }} (days): <span [attr.data-testid]="'setting-terms-' + g + '-current'">{{ sheets.current()?.gradeTerms?.[g] ?? '' }}</span></p>
      }
      @for (g of grades; track g) {
        <p>Grace days for grade {{ g }}: <span [attr.data-testid]="'setting-grace-' + g + '-current'">{{ sheets.current()?.gradeGrace?.[g] ?? '' }}</span></p>
      }
      <p>Late percent: <span data-testid="setting-late-pct-current">{{ (sheets.current()?.latePct ?? '') | figure }}</span></p>
      <p>Policy date: <span data-testid="setting-policy-date-current">{{ sheets.current()?.policyDate ?? '' }}</span></p>
      <p>Period numbering from: <span data-testid="setting-period-numbering-from-current">{{ sheets.current()?.periodNumberingFrom ?? '' }}</span></p>
      <p>Entry date: <span data-testid="setting-entry-date-current">{{ sheets.current()?.entryDate ?? '' }}</span></p>
      <p>Grace days: <span data-testid="setting-grace-days-current">{{ sheets.current()?.graceDays ?? '' }}</span></p>
      <p>Escalate after: <span data-testid="setting-escalate-after-current">{{ sheets.current()?.escalateAfter ?? '' }}</span></p>
      <p>Review SLA: <span data-testid="setting-review-sla-current">{{ sheets.current()?.reviewSla ?? '' }}</span></p>
      <p>Pending SLA: <span data-testid="setting-pending-sla-current">{{ sheets.current()?.pendingSla ?? '' }}</span></p>
      <p>Max open claims: <span data-testid="setting-max-open-current">{{ sheets.current()?.maxOpen ?? '' }}</span></p>
      <p>Score mid threshold: <span data-testid="setting-score-mid-current">{{ sheets.current()?.scoreMid ?? '' }}</span></p>
      <p>Score high threshold: <span data-testid="setting-score-high-current">{{ sheets.current()?.scoreHigh ?? '' }}</span></p>
      <p>Score log: <span data-testid="setting-score-log-current">{{ sheets.current()?.scoreLog ?? '' }}</span></p>
      <p>Fiscal year start month: <span data-testid="setting-fiscal-start-month-current">{{ sheets.current()?.fiscalStartMonth ?? '' }}</span></p>
      <p>Pot: <span data-testid="setting-pot-current">{{ (sheets.current()?.pot ?? '') | figure }}</span></p>
      <p>Alert threshold: <span data-testid="setting-alert-threshold-current">{{ (sheets.current()?.alertThreshold ?? '') | figure }}</span></p>
      <p>Parent sheet: <span data-testid="setting-parent-current">{{ sheets.current()?.parent ?? '' }}</span></p>

      <p>Cap: <span data-testid="setting-cap-current">{{ (sheets.current()?.cap ?? '') | figure }}</span></p>
      <p>Proof threshold: <span data-testid="setting-proof-threshold-current">{{ (sheets.current()?.proofThreshold ?? '') | figure }}</span></p>
      @for (g of grades; track g) {
        <p>Proof threshold for grade {{ g }}: <span [attr.data-testid]="'setting-proof-threshold-' + g + '-current'">{{ (sheets.current()?.gradeProofThreshold?.[g] ?? '') | figure }}</span></p>
      }
      <p>Proof kinds: <span data-testid="setting-proof-kinds-current">{{ sheets.current()?.proofKinds ?? '' }}</span></p>
      <p>Default grade: <span data-testid="setting-default-grade-current">{{ sheets.current()?.defaultGrade ?? '' }}</span></p>
      <p>Note threshold: <span data-testid="setting-note-threshold-current">{{ (sheets.current()?.noteThreshold ?? '') | figure }}</span></p>
      <p>Review threshold: <span data-testid="setting-review-threshold-current">{{ (sheets.current()?.reviewThreshold ?? '') | figure }}</span></p>
      <p>Rounding mode: <span data-testid="setting-rounding-current">{{ sheets.current()?.rounding ?? '' }}</span></p>
      @for (a of permActions; track a) {
        <p>Who may {{ a }}: <span [attr.data-testid]="'setting-perm-' + a + '-current'">{{ sheets.current()?.permissions?.[a] ?? '' }}</span></p>
      }
      <p>Each setting with a figure is saved from its from-date (blank: the sheet's today), keeping the values saved from
        other dates; a claim takes the floor, late percent and terms in force on its date.</p>

      <form [formGroup]="settingsForm" (ngSubmit)="saveSettings()" aria-label="Change the sheet's settings">
        <label for="setting-clerk-limit">Clerk limit (blank: no limit)</label>
        <input id="setting-clerk-limit" data-testid="setting-clerk-limit" formControlName="clerkLimit" (focus)="lastFocused = 'clerkLimit'"
               inputmode="decimal" autocomplete="off" />
        <label for="setting-clerk-limit-from">Clerk limit from (yyyy-mm-dd; blank: today)</label>
        <input id="setting-clerk-limit-from" data-testid="setting-clerk-limit-from" formControlName="clerkLimitFrom" (focus)="lastFocused = 'clerkLimit'"
               placeholder="yyyy-mm-dd" autocomplete="off" />
        @for (g of grades; track g) {
          <label [for]="'setting-clerk-limit-' + g">Clerk limit for grade {{ g }} (in place of the sheet's for a claim whose person is of the grade on the sheet's today; blank: the sheet's)</label>
          <input [id]="'setting-clerk-limit-' + g" [attr.data-testid]="'setting-clerk-limit-' + g" [formControlName]="clerkLimitField[g]"
                 (focus)="lastFocused = clerkLimitField[g]" inputmode="decimal" autocomplete="off" />
          <label [for]="'setting-clerk-limit-' + g + '-from'">Clerk limit for grade {{ g }} from (yyyy-mm-dd; blank: today)</label>
          <input [id]="'setting-clerk-limit-' + g + '-from'" [attr.data-testid]="'setting-clerk-limit-' + g + '-from'"
                 [formControlName]="clerkLimitField[g] + 'From'" (focus)="lastFocused = clerkLimitField[g]" placeholder="yyyy-mm-dd" autocomplete="off" />
        }
        <label for="setting-floor">Floor (a claim's due is at least this)</label>
        <input id="setting-floor" data-testid="setting-floor" formControlName="floor" (focus)="lastFocused = 'floor'"
               inputmode="decimal" autocomplete="off" />
        <label for="setting-floor-from">Floor from (yyyy-mm-dd; blank: today)</label>
        <input id="setting-floor-from" data-testid="setting-floor-from" formControlName="floorFrom" (focus)="lastFocused = 'floor'"
               placeholder="yyyy-mm-dd" autocomplete="off" />
        <label for="setting-terms">Terms (business days a claim is due by after its date)</label>
        <input id="setting-terms" data-testid="setting-terms" formControlName="terms" (focus)="lastFocused = 'terms'"
               inputmode="numeric" autocomplete="off" />
        <label for="setting-terms-from">Terms from (yyyy-mm-dd; blank: today)</label>
        <input id="setting-terms-from" data-testid="setting-terms-from" formControlName="termsFrom" (focus)="lastFocused = 'terms'"
               placeholder="yyyy-mm-dd" autocomplete="off" />
        @for (g of grades; track g) {
          <label [for]="'setting-terms-' + g">Terms for grade {{ g }} (blank: the sheet's terms)</label>
          <input [id]="'setting-terms-' + g" [attr.data-testid]="'setting-terms-' + g" [formControlName]="gradeField[g]"
                 (focus)="lastFocused = gradeField[g]" inputmode="numeric" autocomplete="off" />
          <label [for]="'setting-terms-' + g + '-from'">Terms for grade {{ g }} from (yyyy-mm-dd; blank: today)</label>
          <input [id]="'setting-terms-' + g + '-from'" [attr.data-testid]="'setting-terms-' + g + '-from'"
                 [formControlName]="gradeField[g] + 'From'" (focus)="lastFocused = gradeField[g]" placeholder="yyyy-mm-dd" autocomplete="off" />
        }
        @for (g of grades; track g) {
          <label [for]="'setting-grace-' + g">Grace days for grade {{ g }} (a claim of the grade on its date is overdue only once its due-by plus these has passed; blank: none)</label>
          <input [id]="'setting-grace-' + g" [attr.data-testid]="'setting-grace-' + g" [formControlName]="graceField[g]"
                 (focus)="lastFocused = graceField[g]" inputmode="numeric" autocomplete="off" />
          <label [for]="'setting-grace-' + g + '-from'">Grace days for grade {{ g }} from (yyyy-mm-dd; blank: today)</label>
          <input [id]="'setting-grace-' + g + '-from'" [attr.data-testid]="'setting-grace-' + g + '-from'"
                 [formControlName]="graceField[g] + 'From'" (focus)="lastFocused = graceField[g]" placeholder="yyyy-mm-dd" autocomplete="off" />
        }
        <label for="setting-late-pct">Late percent (added to an overdue claim's due for every full 30 days)</label>
        <input id="setting-late-pct" data-testid="setting-late-pct" formControlName="latePct" (focus)="lastFocused = 'latePct'"
               inputmode="decimal" autocomplete="off" />
        <label for="setting-late-pct-from">Late percent from (yyyy-mm-dd; blank: today)</label>
        <input id="setting-late-pct-from" data-testid="setting-late-pct-from" formControlName="latePctFrom" (focus)="lastFocused = 'latePct'"
               placeholder="yyyy-mm-dd" autocomplete="off" />
        <label for="setting-policy-date">Policy date (a claim dated before it rounds half up, on or after it half to even; blank: today)</label>
        <input id="setting-policy-date" data-testid="setting-policy-date" formControlName="policyDate" (focus)="lastFocused = 'policyDate'"
               placeholder="yyyy-mm-dd" autocomplete="off" />
        <label for="setting-period-numbering-from">Period numbering from (from this day, a claim opened is numbered within its period; blank: off)</label>
        <input id="setting-period-numbering-from" data-testid="setting-period-numbering-from" formControlName="periodNumberingFrom" (focus)="lastFocused = 'periodNumberingFrom'"
               placeholder="yyyy-mm-dd" autocomplete="off" />
        <label for="setting-entry-date">Entry date (a new claim given no date takes it in place of today; blank: off)</label>
        <input id="setting-entry-date" data-testid="setting-entry-date" formControlName="entryDate" (focus)="lastFocused = 'entryDate'"
               placeholder="yyyy-mm-dd" autocomplete="off" />
        <label for="setting-grace-days">Grace days (when today changes, an open period ended more than these days before it is closed; blank: off)</label>
        <input id="setting-grace-days" data-testid="setting-grace-days" formControlName="graceDays" (focus)="lastFocused = 'graceDays'"
               inputmode="numeric" autocomplete="off" />
        <label for="setting-escalate-after">Escalate after (days: reminding a claim whose previous reminder was at least these days before today escalates it; blank: off)</label>
        <input id="setting-escalate-after" data-testid="setting-escalate-after" formControlName="escalateAfter" (focus)="lastFocused = 'escalateAfter'"
               inputmode="numeric" autocomplete="off" />
        <label for="setting-review-sla">Review SLA (days: a claim in review longer than these days past the day it went to review is late; blank: none)</label>
        <input id="setting-review-sla" data-testid="setting-review-sla" formControlName="reviewSla" (focus)="lastFocused = 'reviewSla'"
               inputmode="numeric" autocomplete="off" />
        <label for="setting-pending-sla">Pending SLA (days: a claim pending longer than these days past the day it went to pending is late; blank: none)</label>
        <input id="setting-pending-sla" data-testid="setting-pending-sla" formControlName="pendingSla" (focus)="lastFocused = 'pendingSla'"
               inputmode="numeric" autocomplete="off" />
        <label for="setting-max-open">Max open claims (whole number: opening a claim is refused when its person already has this many open or pending; blank: none)</label>
        <input id="setting-max-open" data-testid="setting-max-open" formControlName="maxOpen" (focus)="lastFocused = 'maxOpen'"
               inputmode="numeric" autocomplete="off" />
        <label for="setting-score-mid">Score mid threshold (whole number: with the high one set, a score at or above it is MID; blank: none)</label>
        <input id="setting-score-mid" data-testid="setting-score-mid" formControlName="scoreMid" (focus)="lastFocused = 'scoreMid'"
               inputmode="numeric" autocomplete="off" />
        <label for="setting-score-high">Score high threshold (whole number: with the mid one set, a score at or above it is HIGH; blank: none)</label>
        <input id="setting-score-high" data-testid="setting-score-high" formControlName="scoreHigh" (focus)="lastFocused = 'scoreHigh'"
               inputmode="numeric" autocomplete="off" />
        <label for="setting-score-log">Score log (on: a change of today records each person's score on the day left; blank: off)</label>
        <input id="setting-score-log" data-testid="setting-score-log" formControlName="scoreLog" (focus)="lastFocused = 'scoreLog'"
               autocomplete="off" />
        <label for="setting-fiscal-start-month">Fiscal year start month (1 to 12)</label>
        <input id="setting-fiscal-start-month" data-testid="setting-fiscal-start-month" formControlName="fiscalStartMonth" (focus)="lastFocused = 'fiscalStartMonth'"
               inputmode="numeric" autocomplete="off" />
        <label for="setting-pot">Pot (cut among the members of each balanced pool that is a member of none, by their shares; blank: none)</label>
        <input id="setting-pot" data-testid="setting-pot" formControlName="pot" (focus)="lastFocused = 'pot'"
               inputmode="decimal" autocomplete="off" />
        <label for="setting-alert-threshold">Alert threshold (a person whose due-total rises above this is alerted; blank: off)</label>
        <input id="setting-alert-threshold" data-testid="setting-alert-threshold" formControlName="alertThreshold" (focus)="lastFocused = 'alertThreshold'"
               inputmode="decimal" autocomplete="off" />
        <label for="setting-parent">Parent sheet (a sheet id: a grade with no rate in force on a date here takes the parent's, up to three levels up; blank: none)</label>
        <input id="setting-parent" data-testid="setting-parent" formControlName="parent" (focus)="lastFocused = 'parent'"
               autocomplete="off" />
        <label for="setting-cap">Cap (a person's due-total is at most this; blank: none)</label>
        <input id="setting-cap" data-testid="setting-cap" formControlName="cap" (focus)="lastFocused = 'cap'"
               inputmode="decimal" autocomplete="off" />
        <label for="setting-cap-from">Cap from (yyyy-mm-dd; blank: today)</label>
        <input id="setting-cap-from" data-testid="setting-cap-from" formControlName="capFrom" (focus)="lastFocused = 'cap'"
               placeholder="yyyy-mm-dd" autocomplete="off" />
        <label for="setting-proof-threshold">Proof threshold (a claim due above this on its date needs a proof to be settled; blank: none)</label>
        <input id="setting-proof-threshold" data-testid="setting-proof-threshold" formControlName="proofThreshold" (focus)="lastFocused = 'proofThreshold'"
               inputmode="decimal" autocomplete="off" />
        <label for="setting-proof-threshold-from">Proof threshold from (yyyy-mm-dd; blank: today)</label>
        <input id="setting-proof-threshold-from" data-testid="setting-proof-threshold-from" formControlName="proofThresholdFrom" (focus)="lastFocused = 'proofThreshold'"
               placeholder="yyyy-mm-dd" autocomplete="off" />
        @for (g of grades; track g) {
          <label [for]="'setting-proof-threshold-' + g">Proof threshold for grade {{ g }} (in place of the sheet's for a claim whose person is of the grade on its date; blank: the sheet's)</label>
          <input [id]="'setting-proof-threshold-' + g" [attr.data-testid]="'setting-proof-threshold-' + g" [formControlName]="proofThresholdField[g]"
                 (focus)="lastFocused = proofThresholdField[g]" inputmode="decimal" autocomplete="off" />
          <label [for]="'setting-proof-threshold-' + g + '-from'">Proof threshold for grade {{ g }} from (yyyy-mm-dd; blank: today)</label>
          <input [id]="'setting-proof-threshold-' + g + '-from'" [attr.data-testid]="'setting-proof-threshold-' + g + '-from'"
                 [formControlName]="proofThresholdField[g] + 'From'" (focus)="lastFocused = proofThresholdField[g]" placeholder="yyyy-mm-dd" autocomplete="off" />
        }
        <label for="setting-proof-kinds">Proof kinds (comma separated, of receipt and photo: a claim above the proof threshold needs a proof of each to be settled; blank: none)</label>
        <input id="setting-proof-kinds" data-testid="setting-proof-kinds" formControlName="proofKinds" (focus)="lastFocused = 'proofKinds'"
               autocomplete="off" />
        <label for="setting-default-grade">Default grade (one of the sheet's grades: the grade a person added by the quick-add bar or imported with a blank grade is given; blank: A)</label>
        <input id="setting-default-grade" data-testid="setting-default-grade" formControlName="defaultGrade" (focus)="lastFocused = 'defaultGrade'"
               autocomplete="off" />
        <label for="setting-note-threshold">Note threshold (a claim due above this on its date needs a note to be opened; blank: none)</label>
        <input id="setting-note-threshold" data-testid="setting-note-threshold" formControlName="noteThreshold" (focus)="lastFocused = 'noteThreshold'"
               inputmode="decimal" autocomplete="off" />
        <label for="setting-note-threshold-from">Note threshold from (yyyy-mm-dd; blank: today)</label>
        <input id="setting-note-threshold-from" data-testid="setting-note-threshold-from" formControlName="noteThresholdFrom" (focus)="lastFocused = 'noteThreshold'"
               placeholder="yyyy-mm-dd" autocomplete="off" />
        <label for="setting-review-threshold">Review threshold (a claim due above this on its date goes to review when opened; blank: none)</label>
        <input id="setting-review-threshold" data-testid="setting-review-threshold" formControlName="reviewThreshold" (focus)="lastFocused = 'reviewThreshold'"
               inputmode="decimal" autocomplete="off" />
        <label for="setting-review-threshold-from">Review threshold from (yyyy-mm-dd; blank: today)</label>
        <input id="setting-review-threshold-from" data-testid="setting-review-threshold-from" formControlName="reviewThresholdFrom" (focus)="lastFocused = 'reviewThreshold'"
               placeholder="yyyy-mm-dd" autocomplete="off" />
        <label for="setting-rounding">Rounding mode (half-up or half-even: a claim dated while one is in force rounds by it; blank: none, by the policy date)</label>
        <input id="setting-rounding" data-testid="setting-rounding" formControlName="rounding" (focus)="lastFocused = 'rounding'"
               autocomplete="off" />
        <label for="setting-rounding-from">Rounding mode from (yyyy-mm-dd; blank: today)</label>
        <input id="setting-rounding-from" data-testid="setting-rounding-from" formControlName="roundingFrom" (focus)="lastFocused = 'rounding'"
               placeholder="yyyy-mm-dd" autocomplete="off" />
        @for (a of permActions; track a) {
          <label [for]="'setting-perm-' + a">Roles that may {{ a }} (comma separated; blank: {{ standing[a] }})</label>
          <input [id]="'setting-perm-' + a" [attr.data-testid]="'setting-perm-' + a" [formControlName]="permField[a]"
                 (focus)="lastFocused = permField[a]" autocomplete="off" />
        }
        <button type="submit" data-testid="settings-save" [disabled]="savingSettings()">Save settings</button>
        @if (settingsError(); as err) {
          <p role="alert" data-testid="settings-error">{{ err }}</p>
        }
      </form>

      <h3>Roles</h3>
      <p>A name with no role assigned is a clerk.</p>
      <ul aria-label="Roles assigned">
        @for (r of roles.all(); track r.user) {
          <li [attr.data-testid]="'role-row-' + r.user">
            {{ r.user }}: <span data-testid="role-value">{{ r.role }}</span>
          </li>
        }
      </ul>

      <form [formGroup]="roleForm" (ngSubmit)="saveRole()" aria-label="Assign a role">
        <label for="role-form-user">User name</label>
        <input id="role-form-user" data-testid="role-form-user" formControlName="user" autocomplete="off" />
        <label for="role-form-role">Role ({{ roleNames }})</label>
        <input id="role-form-role" data-testid="role-form-role" formControlName="role" autocomplete="off" list="role-names" />
        <datalist id="role-names">
          @for (r of roleChoices; track r) {
            <option [value]="r"></option>
          }
        </datalist>
        <button type="submit" data-testid="role-form-submit" [disabled]="savingRole()">Set role</button>
        @if (roleError(); as err) {
          <p role="alert" data-testid="role-form-error">{{ err }}</p>
        }
      </form>
    </section>
  `,
})
export class SettingsPanel {
  protected readonly sheets = inject(Sheets);
  protected readonly roles = inject(Roles);
  private readonly rates = inject(Rates);

  protected readonly grades = GRADES;
  protected readonly gradeField = GRADE_TERMS_FIELD;
  protected readonly graceField = GRADE_GRACE_FIELD;
  protected readonly clerkLimitField = GRADE_CLERK_LIMIT_FIELD;
  protected readonly proofThresholdField = GRADE_PROOF_THRESHOLD_FIELD;
  protected readonly permActions = PERM_ACTIONS;
  protected readonly standing = STANDING_PERMISSIONS;
  protected readonly permField = PERM_FIELD;
  protected readonly roleChoices = ROLES;
  protected readonly roleNames = ROLES.join(' or ');
  protected readonly settingsError = signal<string | null>(null);
  protected readonly roleError = signal<string | null>(null);
  protected readonly savingSettings = signal(false);
  protected readonly savingRole = signal(false);

  /** The setting field last focused: with no setting changed, it is the one saved as it is. */
  protected lastFocused: SettingField = 'clerkLimit';

  protected readonly settingsForm = new FormGroup({
    clerkLimit: new FormControl('', { nonNullable: true }),
    clerkLimitA: new FormControl('', { nonNullable: true }),
    clerkLimitB: new FormControl('', { nonNullable: true }),
    clerkLimitC: new FormControl('', { nonNullable: true }),
    floor: new FormControl('', { nonNullable: true }),
    terms: new FormControl('', { nonNullable: true }),
    termsA: new FormControl('', { nonNullable: true }),
    termsB: new FormControl('', { nonNullable: true }),
    termsC: new FormControl('', { nonNullable: true }),
    graceA: new FormControl('', { nonNullable: true }),
    graceB: new FormControl('', { nonNullable: true }),
    graceC: new FormControl('', { nonNullable: true }),
    latePct: new FormControl('', { nonNullable: true }),
    policyDate: new FormControl('', { nonNullable: true }),
    periodNumberingFrom: new FormControl('', { nonNullable: true }),
    entryDate: new FormControl('', { nonNullable: true }),
    graceDays: new FormControl('', { nonNullable: true }),
    escalateAfter: new FormControl('', { nonNullable: true }),
    reviewSla: new FormControl('', { nonNullable: true }),
    pendingSla: new FormControl('', { nonNullable: true }),
    maxOpen: new FormControl('', { nonNullable: true }),
    scoreMid: new FormControl('', { nonNullable: true }),
    scoreHigh: new FormControl('', { nonNullable: true }),
    scoreLog: new FormControl('', { nonNullable: true }),
    fiscalStartMonth: new FormControl('', { nonNullable: true }),
    pot: new FormControl('', { nonNullable: true }),
    proofKinds: new FormControl('', { nonNullable: true }),
    defaultGrade: new FormControl('', { nonNullable: true }),
    alertThreshold: new FormControl('', { nonNullable: true }),
    parent: new FormControl('', { nonNullable: true }),
    cap: new FormControl('', { nonNullable: true }),
    proofThreshold: new FormControl('', { nonNullable: true }),
    proofThresholdA: new FormControl('', { nonNullable: true }),
    proofThresholdB: new FormControl('', { nonNullable: true }),
    proofThresholdC: new FormControl('', { nonNullable: true }),
    noteThreshold: new FormControl('', { nonNullable: true }),
    reviewThreshold: new FormControl('', { nonNullable: true }),
    rounding: new FormControl('', { nonNullable: true }),
    clerkLimitFrom: new FormControl('', { nonNullable: true }),
    clerkLimitAFrom: new FormControl('', { nonNullable: true }),
    clerkLimitBFrom: new FormControl('', { nonNullable: true }),
    clerkLimitCFrom: new FormControl('', { nonNullable: true }),
    floorFrom: new FormControl('', { nonNullable: true }),
    termsFrom: new FormControl('', { nonNullable: true }),
    termsAFrom: new FormControl('', { nonNullable: true }),
    termsBFrom: new FormControl('', { nonNullable: true }),
    termsCFrom: new FormControl('', { nonNullable: true }),
    graceAFrom: new FormControl('', { nonNullable: true }),
    graceBFrom: new FormControl('', { nonNullable: true }),
    graceCFrom: new FormControl('', { nonNullable: true }),
    latePctFrom: new FormControl('', { nonNullable: true }),
    capFrom: new FormControl('', { nonNullable: true }),
    proofThresholdFrom: new FormControl('', { nonNullable: true }),
    proofThresholdAFrom: new FormControl('', { nonNullable: true }),
    proofThresholdBFrom: new FormControl('', { nonNullable: true }),
    proofThresholdCFrom: new FormControl('', { nonNullable: true }),
    noteThresholdFrom: new FormControl('', { nonNullable: true }),
    reviewThresholdFrom: new FormControl('', { nonNullable: true }),
    roundingFrom: new FormControl('', { nonNullable: true }),
    perm_open: new FormControl('', { nonNullable: true }),
    perm_settle: new FormControl('', { nonNullable: true }),
    perm_void: new FormControl('', { nonNullable: true }),
    perm_reassign: new FormControl('', { nonNullable: true }),
    perm_close: new FormControl('', { nonNullable: true }),
    perm_reopen: new FormControl('', { nonNullable: true }),
  });
  protected readonly roleForm = new FormGroup({
    user: new FormControl('', { nonNullable: true }),
    role: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    // Each (re)load of a sheet re-reads its roles and settings from the store.
    toObservable(this.sheets.loaded)
      .pipe(
        switchMap((sheet) => {
          this.settingsError.set(null);
          this.roleError.set(null);
          this.resetSettings(sheet);
          return this.roles.load(sheet?.id ?? null) ?? EMPTY;
        }),
        takeUntilDestroyed(),
      )
      .subscribe({ error: () => this.roleError.set('Could not read the roles.') });
    // An error is removed as soon as its form is changed.
    this.settingsForm.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.settingsError.set(null));
    this.roleForm.valueChanges.pipe(takeUntilDestroyed()).subscribe(() => this.roleError.set(null));
  }

  protected saveSettings(): void {
    // A second submit before the first has answered is ignored, so it stores one change.
    if (this.savingSettings()) return;
    this.settingsError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const { clerkLimit, floor, terms, latePct, policyDate, periodNumberingFrom, entryDate, graceDays, escalateAfter, reviewSla, pendingSla, maxOpen, scoreMid, scoreHigh, scoreLog, fiscalStartMonth, pot, alertThreshold, parent, proofKinds, defaultGrade, cap, proofThreshold, noteThreshold, reviewThreshold, rounding } = this.settingsForm.controls;
    const limit = clerkLimit.value.trim();
    const floorValue = floor.value.trim();
    const termsValue = terms.value.trim();
    const latePctValue = latePct.value.trim();
    const policyDateValue = policyDate.value.trim();
    const periodNumberingFromValue = periodNumberingFrom.value.trim();
    const entryDateValue = entryDate.value.trim();
    const graceDaysValue = graceDays.value.trim();
    const escalateAfterValue = escalateAfter.value.trim();
    const reviewSlaValue = reviewSla.value.trim();
    const pendingSlaValue = pendingSla.value.trim();
    const maxOpenValue = maxOpen.value.trim();
    const scoreMidValue = scoreMid.value.trim();
    const scoreHighValue = scoreHigh.value.trim();
    const scoreLogValue = scoreLog.value.trim().toLowerCase();
    const fiscalStartMonthValue = fiscalStartMonth.value.trim();
    const potValue = pot.value.trim();
    const proofKindsValue = proofKinds.value.split(',').map((k) => k.trim()).filter((k) => k !== '');
    const defaultGradeValue = defaultGrade.value.trim();
    const alertThresholdValue = alertThreshold.value.trim();
    const parentValue = parent.value.trim();
    const capValue = cap.value.trim();
    const proofThresholdValue = proofThreshold.value.trim();
    const noteThresholdValue = noteThreshold.value.trim();
    const reviewThresholdValue = reviewThreshold.value.trim();
    const roundingValue = rounding.value.trim();
    // Only the settings changed in the form (a value or its from-date) are saved; with none changed, the field last
    // focused (else the clerk limit) is saved as it is.
    const unchanged = !this.settingsForm.dirty;
    const controls = this.settingsForm.controls;
    const isSaved = (field: SettingField) => controls[field].dirty
      || (field !== 'policyDate' && field !== 'periodNumberingFrom' && field !== 'entryDate' && field !== 'graceDays' && field !== 'escalateAfter' && field !== 'reviewSla' && field !== 'pendingSla' && field !== 'maxOpen' && field !== 'scoreMid' && field !== 'scoreHigh' && field !== 'scoreLog' && field !== 'fiscalStartMonth' && field !== 'pot' && field !== 'alertThreshold' && field !== 'parent' && field !== 'proofKinds' && field !== 'defaultGrade' && !field.startsWith('perm_')
        && controls[`${field as DatedField}From`].dirty)
      || (unchanged && this.lastFocused === field);
    const fromOf = (field: DatedField): string | null => controls[`${field}From` as FromField].value.trim() || null;
    const saveFloor = isSaved('floor');
    const saveTerms = isSaved('terms');
    const saveLatePct = isSaved('latePct');
    const savePolicyDate = isSaved('policyDate');
    const savePeriodNumberingFrom = isSaved('periodNumberingFrom');
    const saveEntryDate = isSaved('entryDate');
    const saveGraceDays = isSaved('graceDays');
    const saveEscalateAfter = isSaved('escalateAfter');
    const saveReviewSla = isSaved('reviewSla');
    const savePendingSla = isSaved('pendingSla');
    const saveMaxOpen = isSaved('maxOpen');
    const saveScoreMid = isSaved('scoreMid');
    const saveScoreHigh = isSaved('scoreHigh');
    const saveScoreLog = isSaved('scoreLog');
    const saveFiscalStartMonth = isSaved('fiscalStartMonth');
    const savePot = isSaved('pot');
    const saveProofKinds = isSaved('proofKinds');
    const saveDefaultGrade = isSaved('defaultGrade');
    const saveAlertThreshold = isSaved('alertThreshold');
    const saveParent = isSaved('parent');
    const saveLimit = isSaved('clerkLimit');
    const saveCap = isSaved('cap');
    const saveProofThreshold = isSaved('proofThreshold');
    const saveNoteThreshold = isSaved('noteThreshold');
    const saveReviewThreshold = isSaved('reviewThreshold');
    const saveRounding = isSaved('rounding');
    if (saveLimit && limit !== '' && !LIMIT_PATTERN.test(limit)) {
      this.settingsError.set('Type a clerk limit of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    const gradeClerkLimitSaved = GRADES.filter((g) => isSaved(GRADE_CLERK_LIMIT_FIELD[g]))
      .map((g) => ({ grade: g, value: controls[GRADE_CLERK_LIMIT_FIELD[g]].value.trim() }));
    if (gradeClerkLimitSaved.some((t) => t.value !== '' && !LIMIT_PATTERN.test(t.value))) {
      this.settingsError.set('Type a grade\'s clerk limit of 0.00 or more, with up to two decimals, or leave it blank for the sheet\'s.');
      return;
    }
    if (saveFloor && !LIMIT_PATTERN.test(floorValue)) {
      this.settingsError.set('Type a floor of 0.00 or more, with up to two decimals.');
      return;
    }
    if (saveTerms && !TERMS_PATTERN.test(termsValue)) {
      this.settingsError.set('Type terms of 0 or more whole days.');
      return;
    }
    const gradeTermsSaved = GRADES.filter((g) => isSaved(GRADE_TERMS_FIELD[g]))
      .map((g) => ({ grade: g, value: this.settingsForm.controls[GRADE_TERMS_FIELD[g]].value.trim() }));
    if (gradeTermsSaved.some((t) => t.value !== '' && !TERMS_PATTERN.test(t.value))) {
      this.settingsError.set('Type a grade\'s terms as 0 or more whole days, or leave them blank for the sheet\'s terms.');
      return;
    }
    const gradeGraceSaved = GRADES.filter((g) => isSaved(GRADE_GRACE_FIELD[g]))
      .map((g) => ({ grade: g, value: this.settingsForm.controls[GRADE_GRACE_FIELD[g]].value.trim() }));
    if (gradeGraceSaved.some((t) => t.value !== '' && !TERMS_PATTERN.test(t.value))) {
      this.settingsError.set('Type a grade\'s grace days as 0 or more whole days, or leave them blank for none.');
      return;
    }
    if (saveLatePct && !LATE_PCT_PATTERN.test(latePctValue)) {
      this.settingsError.set('Type a late percent of 0.00 or more, up to 999.99, with up to two decimals.');
      return;
    }
    if (savePolicyDate && policyDateValue !== '' && !(DATE_PATTERN.test(policyDateValue) && isDate(policyDateValue))) {
      this.settingsError.set('Type a policy date as yyyy-mm-dd, or leave it blank for the sheet\'s today.');
      return;
    }
    if (savePeriodNumberingFrom && periodNumberingFromValue !== ''
        && !(DATE_PATTERN.test(periodNumberingFromValue) && isDate(periodNumberingFromValue))) {
      this.settingsError.set('Type a period numbering from date as yyyy-mm-dd, or leave it blank for off.');
      return;
    }
    if (saveEntryDate && entryDateValue !== '' && !(DATE_PATTERN.test(entryDateValue) && isDate(entryDateValue))) {
      this.settingsError.set('Type an entry date as yyyy-mm-dd, or leave it blank for off.');
      return;
    }
    if (saveGraceDays && graceDaysValue !== '' && !TERMS_PATTERN.test(graceDaysValue)) {
      this.settingsError.set('Type grace days as 0 or more whole days, or leave them blank for off.');
      return;
    }
    if (saveEscalateAfter && escalateAfterValue !== '' && !TERMS_PATTERN.test(escalateAfterValue)) {
      this.settingsError.set('Type an escalation gap as 0 or more whole days, or leave it blank for off.');
      return;
    }
    if (saveReviewSla && reviewSlaValue !== '' && !TERMS_PATTERN.test(reviewSlaValue)) {
      this.settingsError.set('Type a review SLA as 0 or more whole days, or leave it blank for none.');
      return;
    }
    if (savePendingSla && pendingSlaValue !== '' && !TERMS_PATTERN.test(pendingSlaValue)) {
      this.settingsError.set('Type a pending SLA as 0 or more whole days, or leave it blank for none.');
      return;
    }
    if (saveMaxOpen && maxOpenValue !== '' && !TERMS_PATTERN.test(maxOpenValue)) {
      this.settingsError.set('Type a maximum of open claims as 0 or more, a whole number, or leave it blank for none.');
      return;
    }
    if ((saveScoreMid && scoreMidValue !== '' && !SCORE_PATTERN.test(scoreMidValue))
        || (saveScoreHigh && scoreHighValue !== '' && !SCORE_PATTERN.test(scoreHighValue))) {
      this.settingsError.set('Type a score threshold as a whole number, or leave it blank for none.');
      return;
    }
    if (saveScoreLog && scoreLogValue !== '' && scoreLogValue !== 'on') {
      this.settingsError.set('Type on for the score log, or leave it blank for off.');
      return;
    }
    if (saveFiscalStartMonth && !/^(0?[1-9]|1[0-2])$/.test(fiscalStartMonthValue)) {
      this.settingsError.set('Type a fiscal year start month from 1 to 12.');
      return;
    }
    if (saveProofKinds && (proofKindsValue.some((k) => !(PROOF_KINDS as readonly string[]).includes(k))
        || new Set(proofKindsValue).size !== proofKindsValue.length)) {
      this.settingsError.set('Type proof kinds as a comma separated list of receipt and photo, each at most once, or leave it blank for none.');
      return;
    }
    if (saveDefaultGrade && defaultGradeValue !== '' && !this.sheets.grades().includes(defaultGradeValue)) {
      this.settingsError.set(`Type a default grade of ${this.sheets.grades().join(', ')}, or leave it blank for A.`);
      return;
    }
    if (savePot && potValue !== '' && !LIMIT_PATTERN.test(potValue)) {
      this.settingsError.set('Type a pot of 0.00 or more, with up to two decimals, or leave it blank for none.');
      return;
    }
    if (saveAlertThreshold && alertThresholdValue !== '' && !LIMIT_PATTERN.test(alertThresholdValue)) {
      this.settingsError.set('Type an alert threshold of 0.00 or more, with up to two decimals, or leave it blank for off.');
      return;
    }
    if (saveParent && parentValue !== '' && !SHEET_ID_PATTERN.test(parentValue)) {
      this.settingsError.set('Type the id of a sheet that has been loaded, or leave it blank for no parent.');
      return;
    }
    if (saveCap && capValue !== '' && !LIMIT_PATTERN.test(capValue)) {
      this.settingsError.set('Type a cap of 0.00 or more, with up to two decimals, or leave it blank for none.');
      return;
    }
    if (saveProofThreshold && proofThresholdValue !== '' && !LIMIT_PATTERN.test(proofThresholdValue)) {
      this.settingsError.set('Type a proof threshold of 0.00 or more, with up to two decimals, or leave it blank for none.');
      return;
    }
    const gradeProofThresholdSaved = GRADES.filter((g) => isSaved(GRADE_PROOF_THRESHOLD_FIELD[g]))
      .map((g) => ({ grade: g, value: controls[GRADE_PROOF_THRESHOLD_FIELD[g]].value.trim() }));
    if (gradeProofThresholdSaved.some((t) => t.value !== '' && !LIMIT_PATTERN.test(t.value))) {
      this.settingsError.set('Type a grade\'s proof threshold of 0.00 or more, with up to two decimals, or leave it blank for the sheet\'s.');
      return;
    }
    if (saveNoteThreshold && noteThresholdValue !== '' && !LIMIT_PATTERN.test(noteThresholdValue)) {
      this.settingsError.set('Type a note threshold of 0.00 or more, with up to two decimals, or leave it blank for none.');
      return;
    }
    if (saveReviewThreshold && reviewThresholdValue !== '' && !LIMIT_PATTERN.test(reviewThresholdValue)) {
      this.settingsError.set('Type a review threshold of 0.00 or more, with up to two decimals, or leave it blank for none.');
      return;
    }
    if (saveRounding && roundingValue !== '' && !ROUNDING_MODES.includes(roundingValue)) {
      this.settingsError.set('Type a rounding mode of half-up or half-even, or leave it blank for none.');
      return;
    }
    const permsSaved = PERM_ACTIONS.filter((a) => isSaved(`perm_${a}`))
      .map((a) => ({ action: a, roles: controls[`perm_${a}`].value.split(',').map((r) => r.trim()).filter((r) => r !== '') }));
    if (permsSaved.some((p) => p.roles.some((r) => !(ROLES as readonly string[]).includes(r)))) {
      this.settingsError.set(`List the roles that may take an action, comma separated (${this.roleNames}), or leave it blank for the standing rule.`);
      return;
    }
    const datedFields: DatedField[] = ['clerkLimit', 'clerkLimitA', 'clerkLimitB', 'clerkLimitC', 'floor', 'terms', 'termsA', 'termsB', 'termsC', 'graceA', 'graceB', 'graceC', 'latePct', 'cap', 'proofThreshold', 'proofThresholdA', 'proofThresholdB', 'proofThresholdC', 'noteThreshold', 'reviewThreshold', 'rounding'];
    const badFrom = datedFields.find((f) => {
      const from = fromOf(f);
      return isSaved(f) && from !== null && !(DATE_PATTERN.test(from) && isDate(from));
    });
    if (badFrom !== undefined) {
      this.settingsError.set('Type a from-date as yyyy-mm-dd, or leave it blank for the sheet\'s today.');
      return;
    }
    const saves: Observable<SheetView>[] = [];
    if (saveLimit) saves.push(this.sheets.setSettings(sheet.id, limit === '' ? null : limit, fromOf('clerkLimit')));
    for (const t of gradeClerkLimitSaved) saves.push(this.sheets.setGradeClerkLimit(sheet.id, t.grade, t.value === '' ? null : t.value, fromOf(GRADE_CLERK_LIMIT_FIELD[t.grade])));
    if (saveFloor) saves.push(this.sheets.setFloor(sheet.id, floorValue, fromOf('floor')));
    if (saveTerms) saves.push(this.sheets.setTerms(sheet.id, Number(termsValue), fromOf('terms')));
    for (const t of gradeTermsSaved) saves.push(this.sheets.setGradeTerms(sheet.id, t.grade, t.value === '' ? null : Number(t.value), fromOf(GRADE_TERMS_FIELD[t.grade])));
    for (const t of gradeGraceSaved) saves.push(this.sheets.setGradeGrace(sheet.id, t.grade, t.value === '' ? null : Number(t.value), fromOf(GRADE_GRACE_FIELD[t.grade])));
    if (saveLatePct) saves.push(this.sheets.setLatePct(sheet.id, latePctValue, fromOf('latePct')));
    if (savePolicyDate) saves.push(this.sheets.setPolicyDate(sheet.id, policyDateValue === '' ? null : policyDateValue));
    if (savePeriodNumberingFrom) saves.push(this.sheets.setPeriodNumberingFrom(sheet.id, periodNumberingFromValue === '' ? null : periodNumberingFromValue));
    if (saveEntryDate) saves.push(this.sheets.setEntryDate(sheet.id, entryDateValue === '' ? null : entryDateValue));
    if (saveGraceDays) saves.push(this.sheets.setGraceDays(sheet.id, graceDaysValue === '' ? null : Number(graceDaysValue)));
    if (saveEscalateAfter) saves.push(this.sheets.setEscalateAfter(sheet.id, escalateAfterValue === '' ? null : Number(escalateAfterValue)));
    if (saveReviewSla) saves.push(this.sheets.setReviewSla(sheet.id, reviewSlaValue === '' ? null : Number(reviewSlaValue)));
    if (savePendingSla) saves.push(this.sheets.setPendingSla(sheet.id, pendingSlaValue === '' ? null : Number(pendingSlaValue)));
    if (saveMaxOpen) saves.push(this.sheets.setMaxOpen(sheet.id, maxOpenValue === '' ? null : Number(maxOpenValue)));
    if (saveScoreMid) saves.push(this.sheets.setScoreMid(sheet.id, scoreMidValue === '' ? null : Number(scoreMidValue)));
    if (saveScoreLog) saves.push(this.sheets.setScoreLog(sheet.id, scoreLogValue === 'on' ? 'on' : null));
    if (saveScoreHigh) saves.push(this.sheets.setScoreHigh(sheet.id, scoreHighValue === '' ? null : Number(scoreHighValue)));
    if (saveFiscalStartMonth) saves.push(this.sheets.setFiscalStartMonth(sheet.id, Number(fiscalStartMonthValue)));
    if (saveProofKinds) saves.push(this.sheets.setProofKinds(sheet.id, proofKindsValue.length === 0 ? null : proofKindsValue.join(',')));
    if (saveDefaultGrade) saves.push(this.sheets.setDefaultGrade(sheet.id, defaultGradeValue === '' ? null : defaultGradeValue));
    if (savePot) saves.push(this.sheets.setPot(sheet.id, potValue === '' ? null : potValue));
    if (saveAlertThreshold) saves.push(this.sheets.setAlertThreshold(sheet.id, alertThresholdValue === '' ? null : alertThresholdValue));
    if (saveParent) saves.push(this.sheets.setParent(sheet.id, parentValue === '' ? null : parentValue));
    if (saveCap) saves.push(this.sheets.setCap(sheet.id, capValue === '' ? null : capValue, fromOf('cap')));
    if (saveProofThreshold) saves.push(this.sheets.setProofThreshold(sheet.id, proofThresholdValue === '' ? null : proofThresholdValue, fromOf('proofThreshold')));
    for (const t of gradeProofThresholdSaved) saves.push(this.sheets.setGradeProofThreshold(sheet.id, t.grade, t.value === '' ? null : t.value, fromOf(GRADE_PROOF_THRESHOLD_FIELD[t.grade])));
    if (saveNoteThreshold) saves.push(this.sheets.setNoteThreshold(sheet.id, noteThresholdValue === '' ? null : noteThresholdValue, fromOf('noteThreshold')));
    if (saveReviewThreshold) saves.push(this.sheets.setReviewThreshold(sheet.id, reviewThresholdValue === '' ? null : reviewThresholdValue, fromOf('reviewThreshold')));
    if (saveRounding) saves.push(this.sheets.setRounding(sheet.id, roundingValue === '' ? null : roundingValue, fromOf('rounding')));
    for (const p of permsSaved) saves.push(this.sheets.setPermission(sheet.id, p.action, p.roles.join(',')));
    this.savingSettings.set(true);
    concat(...saves).pipe(last()).subscribe({
      next: (saved) => {
        this.savingSettings.set(false);
        this.resetSettings(saved);
        // A new parent changes the rates its grades fall back to.
        if (saveParent) this.rates.loadInherited(saved.id).subscribe();
      },
      error: () => {
        this.savingSettings.set(false);
        this.settingsError.set('Could not save the settings.');
      },
    });
  }

  /** Show the sheet's settings in force on its today in the form, as not yet changed, with blank from-dates. */
  private resetSettings(sheet: SheetView | null): void {
    this.settingsForm.reset({ clerkLimit: sheet?.clerkLimit ?? '', clerkLimitA: sheet?.gradeClerkLimit?.A ?? '', clerkLimitB: sheet?.gradeClerkLimit?.B ?? '', clerkLimitC: sheet?.gradeClerkLimit?.C ?? '', floor: sheet?.floor ?? '', terms: String(sheet?.terms ?? ''), termsA: String(sheet?.gradeTerms?.A ?? ''), termsB: String(sheet?.gradeTerms?.B ?? ''), termsC: String(sheet?.gradeTerms?.C ?? ''), graceA: String(sheet?.gradeGrace?.A ?? ''), graceB: String(sheet?.gradeGrace?.B ?? ''), graceC: String(sheet?.gradeGrace?.C ?? ''), latePct: sheet?.latePct ?? '', policyDate: sheet?.policyDate ?? '', periodNumberingFrom: sheet?.periodNumberingFrom ?? '', entryDate: sheet?.entryDate ?? '', graceDays: String(sheet?.graceDays ?? ''), escalateAfter: String(sheet?.escalateAfter ?? ''), reviewSla: String(sheet?.reviewSla ?? ''), pendingSla: String(sheet?.pendingSla ?? ''), maxOpen: String(sheet?.maxOpen ?? ''), scoreMid: String(sheet?.scoreMid ?? ''), scoreHigh: String(sheet?.scoreHigh ?? ''), scoreLog: sheet?.scoreLog ?? '', fiscalStartMonth: String(sheet?.fiscalStartMonth ?? ''), pot: sheet?.pot ?? '', proofKinds: sheet?.proofKinds ?? '', defaultGrade: sheet?.defaultGrade ?? '', alertThreshold: sheet?.alertThreshold ?? '', parent: sheet?.parent ?? '', cap: sheet?.cap ?? '', proofThreshold: sheet?.proofThreshold ?? '', proofThresholdA: sheet?.gradeProofThreshold?.A ?? '', proofThresholdB: sheet?.gradeProofThreshold?.B ?? '', proofThresholdC: sheet?.gradeProofThreshold?.C ?? '', noteThreshold: sheet?.noteThreshold ?? '', reviewThreshold: sheet?.reviewThreshold ?? '', rounding: sheet?.rounding ?? '', clerkLimitFrom: '', clerkLimitAFrom: '', clerkLimitBFrom: '', clerkLimitCFrom: '', floorFrom: '', termsFrom: '', termsAFrom: '', termsBFrom: '', termsCFrom: '', graceAFrom: '', graceBFrom: '', graceCFrom: '', latePctFrom: '', capFrom: '', proofThresholdFrom: '', proofThresholdAFrom: '', proofThresholdBFrom: '', proofThresholdCFrom: '', noteThresholdFrom: '', reviewThresholdFrom: '', roundingFrom: '',
      perm_open: sheet?.permissions?.open ?? '', perm_settle: sheet?.permissions?.settle ?? '', perm_void: sheet?.permissions?.void ?? '',
      perm_reassign: sheet?.permissions?.reassign ?? '', perm_close: sheet?.permissions?.close ?? '', perm_reopen: sheet?.permissions?.reopen ?? '' }, { emitEvent: false });
  }

  protected saveRole(): void {
    // A second submit before the first has answered is ignored, so it stores one record.
    if (this.savingRole()) return;
    this.roleError.set(null);
    const sheet = this.sheets.current();
    if (!sheet) return;
    const user = this.roleForm.controls.user.value.trim();
    const role = this.roleForm.controls.role.value.trim();
    if (!user) {
      this.roleError.set('Type the user name to assign a role to.');
      return;
    }
    if (!(ROLES as readonly string[]).includes(role)) {
      this.roleError.set(`Choose a role: ${this.roleNames}.`);
      return;
    }
    this.savingRole.set(true);
    this.roles.set(sheet.id, user, role as RoleName).subscribe({
      next: () => {
        this.savingRole.set(false);
        this.roleForm.reset({ user: '', role: '' }, { emitEvent: false });
      },
      error: () => {
        this.savingRole.set(false);
        this.roleError.set('Could not assign that role.');
      },
    });
  }
}
