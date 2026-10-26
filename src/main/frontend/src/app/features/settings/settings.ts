import { Component, computed, effect, inject, signal } from '@angular/core';
import { FigurePipe } from '../../core/figure.pipe';
import { AbstractControl, FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { PROOF_KINDS } from '../claims/claims-api';
import { GRADES, Grade } from '../people/people-api';
import { DatedValue, valueOn } from './dated';
import { DEFAULT_TERMS, DelegationView, PERMISSION_ACTIONS, PermissionAction, ROLES, Role, RoleView, SettingsApi, SettingsView } from './settings-api';
import { SheetCap } from './sheet-cap';
import { DEFAULT_FISCAL_START_MONTH, SheetFiscal } from './sheet-fiscal';
import { DEFAULT_FLOOR_CENTS, SheetFloor } from './sheet-floor';
import { ROUNDING_MODES, SheetRounding } from './sheet-rounding';
import { SheetGrace } from './sheet-grace';
import { SheetReviewSla } from './sheet-review-sla';
import { SheetScoreBands } from './sheet-score-bands';
import { DEFAULT_LATE_PCT, SheetLatePct } from './sheet-late-pct';
import { SheetParent } from './sheet-parent';
import { SheetPolicyDate } from './sheet-policy-date';
import { SheetPot } from './sheet-pot';

const MONEY = /^\d{1,10}(\.\d{1,2})?$/;
const DAYS = /^\d{1,5}$/;
const WHOLE = /^-?\d{1,9}$/;
const PCT = /^\d{1,10}(\.\d{1,2})?$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const MONTH = /^\d{1,2}$/;

/** Whether a yyyy-mm-dd text is a real calendar date. */
function isDate(text: string): boolean {
  if (!DATE.test(text)) return false;
  const [y, m, d] = text.split('-').map(Number);
  const days = [31, y % 4 === 0 && (y % 100 !== 0 || y % 400 === 0) ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return m >= 1 && m <= 12 && d >= 1 && d <= days[m - 1];
}

/** Whether a setting's field was changed or visited since the last save. */
function changed(control: AbstractControl): boolean {
  return control.dirty || control.touched;
}

/** A setting's field once its value is stored. */
function settled(control: AbstractControl): void {
  control.markAsPristine();
  control.markAsUntouched();
}

// The sheet's settings, with the clerk limit (the highest due a clerk may settle) and each grade's own clerk
// limit (a claim whose person's grade on the sheet's today has one in force then uses it instead; blank: the sheet's) and the floor (the
// least a claim's live due can be) and the terms (the business days after a claim's date it is due by, fixed when the
// claim is added) and each grade's own terms (blank: the sheet's terms) and the late
// percent (what an overdue claim gains of its figure for every full 30 days overdue) and each grade's grace
// (days after a claim's due-by, its person's grade on its date having them then, before it is overdue and its
// late charge counts; blank: none) and the policy date
// (a claim dated before it rounds half up, on or after it half to even; blank: the sheet's today) and period
// numbering from (once the sheet's today reaches it, a claim opened is numbered within its period; blank: off) and the cap
// (the most a person's due-total can be; blank: no cap) and the proof threshold (a claim whose due is above
// the one in force on its date needs a proof to be settled or approved; blank: none) and each grade's own proof
// threshold (a claim whose person's grade on its date has one in force then uses it instead; blank: the sheet's), and the roles it assigns user names, with one form for each.
// A steward can delegate the steward role to a user from a date (blank: the sheet's today) to a date, inclusive;
// while the sheet's today is inside the range the user acts as a steward.
// The settings that carry a figure (the clerk limit, each grade's clerk limit, the floor, the rounding mode (half-up or half-even; blank: the policy date decides), the terms, each grade's terms, the late
// percent, each grade's grace, the cap, the proof threshold and each grade's proof threshold) are dated: each has a from-date field (blank: the sheet's today), and saving records
// its value from that date, keeping the values recorded from other dates. Each shows the value in force on
// the sheet's today.
// The permission matrix names, for each of open, settle, void, reassign, close and reopen, the roles that
// may take it, comma separated (blank: the standing rule); it is not dated. Nor are the grace days (once the
// sheet's today is changed to after an open period's to-date plus them, the period is closed; blank: off),
// nor the entry date (while set, the date a new claim given none takes instead of the sheet's today), nor the
// pot (what each balanced pool shares out among its members by their shares; blank: none), nor the parent
// sheet (a grade with no rate in force on a date on this sheet takes its parent's, then that one's parent's,
// up to three levels up; blank: none), nor the fiscal year start month (the month, 1 to 12, each fiscal year
// starts on; 1 unless set), nor the alert threshold (a change taking a person's due-total from at or below
// it to above it audits an alert; blank: off), nor the escalation gap (reminding a claim whose previous
// reminder was at least these days before the sheet's today escalates it, once per claim ever; blank: off),
// nor the review SLA (a REVIEW claim whose review day plus these days is before the sheet's today is
// review-late; blank: none).
// Saving the settings stores each setting whose field (or from-date field) was changed or visited since
// the last save, so a value typed again as it was is still stored.
@Component({
  selector: 'app-settings',
  imports: [FigurePipe, ReactiveFormsModule],
  template: `
    <section aria-labelledby="settings-heading">
      <h2 id="settings-heading">Settings</h2>
      <p>Parent sheet: <strong data-testid="sheet-parent">{{ parent() ?? '' }}</strong></p>
      <form [formGroup]="settingsForm" (ngSubmit)="saveSettings()" aria-label="Change the settings">
        <label for="setting-clerk-limit">Clerk limit (blank: no limit)</label>
        <input id="setting-clerk-limit" data-testid="setting-clerk-limit" formControlName="clerkLimit" inputmode="decimal" />
        <label for="setting-clerk-limit-from">Clerk limit from (yyyy-mm-dd; blank: the sheet's today)</label>
        <input id="setting-clerk-limit-from" data-testid="setting-clerk-limit-from" formControlName="clerkLimitFrom" />
        <p>Clerk limit now: <span data-testid="setting-clerk-limit-current">{{ (clerkLimit() ?? '') | figure }}</span></p>
        @for (g of grades; track g) {
          <label [for]="'setting-clerk-limit-' + g">Clerk limit of grade {{ g }} (blank: the sheet's)</label>
          <input [id]="'setting-clerk-limit-' + g" [attr.data-testid]="'setting-clerk-limit-' + g" [formControl]="gradeClerkLimitForm.controls[g]" inputmode="decimal" />
          <label [for]="'setting-clerk-limit-' + g + '-from'">Clerk limit of grade {{ g }} from (yyyy-mm-dd; blank: the sheet's today)</label>
          <input [id]="'setting-clerk-limit-' + g + '-from'" [attr.data-testid]="'setting-clerk-limit-' + g + '-from'" [formControl]="gradeClerkLimitFromForm.controls[g]" />
          <p>Clerk limit of grade {{ g }} now: <span [attr.data-testid]="'setting-clerk-limit-' + g + '-current'">{{ (now('clerk-limit-' + g) ?? '') | figure }}</span></p>
        }
        <label for="setting-floor">Floor (the least a claim's live due can be)</label>
        <input id="setting-floor" data-testid="setting-floor" formControlName="floor" inputmode="decimal" />
        <label for="setting-floor-from">Floor from (yyyy-mm-dd; blank: the sheet's today)</label>
        <input id="setting-floor-from" data-testid="setting-floor-from" formControlName="floorFrom" />
        <p>Floor now: <span data-testid="setting-floor-current">{{ floor() | figure }}</span></p>
        <label for="setting-rounding">Rounding mode (half-up or half-even; blank: the policy date decides)</label>
        <input id="setting-rounding" data-testid="setting-rounding" formControlName="rounding" />
        <label for="setting-rounding-from">Rounding mode from (yyyy-mm-dd; blank: the sheet's today)</label>
        <input id="setting-rounding-from" data-testid="setting-rounding-from" formControlName="roundingFrom" />
        <p>Rounding mode now: <span data-testid="setting-rounding-current">{{ rounding() ?? '' }}</span></p>
        <label for="setting-terms">Terms (business days after a claim's date it is due by)</label>
        <input id="setting-terms" data-testid="setting-terms" formControlName="terms" inputmode="numeric" />
        <label for="setting-terms-from">Terms from (yyyy-mm-dd; blank: the sheet's today)</label>
        <input id="setting-terms-from" data-testid="setting-terms-from" formControlName="termsFrom" />
        <p>Terms now: <span data-testid="setting-terms-current">{{ terms() }}</span></p>
        @for (g of grades; track g) {
          <label [for]="'setting-terms-' + g">Terms of grade {{ g }} (business days; blank: the sheet's terms)</label>
          <input [id]="'setting-terms-' + g" [attr.data-testid]="'setting-terms-' + g" [formControl]="gradeTermsForm.controls[g]" inputmode="numeric" />
          <label [for]="'setting-terms-' + g + '-from'">Terms of grade {{ g }} from (yyyy-mm-dd; blank: the sheet's today)</label>
          <input [id]="'setting-terms-' + g + '-from'" [attr.data-testid]="'setting-terms-' + g + '-from'" [formControl]="gradeTermsFromForm.controls[g]" />
          <p>Terms of grade {{ g }} now: <span [attr.data-testid]="'setting-terms-' + g + '-current'">{{ gradeTermsNow(g) }}</span></p>
        }
        <label for="setting-late-pct">Late percent (gained for every full 30 days overdue)</label>
        <input id="setting-late-pct" data-testid="setting-late-pct" formControlName="latePct" inputmode="decimal" />
        <label for="setting-late-pct-from">Late percent from (yyyy-mm-dd; blank: the sheet's today)</label>
        <input id="setting-late-pct-from" data-testid="setting-late-pct-from" formControlName="latePctFrom" />
        <p>Late percent now: <span data-testid="setting-late-pct-current">{{ latePct() | figure }}</span></p>
        @for (g of grades; track g) {
          <label [for]="'setting-grace-' + g">Grace of grade {{ g }} (days after a claim's due-by before it is overdue; blank: none)</label>
          <input [id]="'setting-grace-' + g" [attr.data-testid]="'setting-grace-' + g" [formControl]="gradeGraceForm.controls[g]" inputmode="numeric" />
          <label [for]="'setting-grace-' + g + '-from'">Grace of grade {{ g }} from (yyyy-mm-dd; blank: the sheet's today)</label>
          <input [id]="'setting-grace-' + g + '-from'" [attr.data-testid]="'setting-grace-' + g + '-from'" [formControl]="gradeGraceFromForm.controls[g]" />
          <p>Grace of grade {{ g }} now: <span [attr.data-testid]="'setting-grace-' + g + '-current'">{{ now('grace-' + g) ?? '' }}</span></p>
        }
        <label for="setting-policy-date">Policy date (yyyy-mm-dd; claims dated before it round half up, on or after it half to even; blank: the sheet's today)</label>
        <input id="setting-policy-date" data-testid="setting-policy-date" formControlName="policyDate" />
        <p>Policy date now: <span data-testid="setting-policy-date-current">{{ policyDate() ?? '' }}</span></p>
        <label for="setting-period-numbering-from">Period numbering from (yyyy-mm-dd; once the sheet's today reaches it, claims opened are numbered within their period; blank: off)</label>
        <input id="setting-period-numbering-from" data-testid="setting-period-numbering-from" formControlName="periodNumberingFrom" />
        <p>Period numbering from now: <span data-testid="setting-period-numbering-from-current">{{ periodNumberingFrom() ?? '' }}</span></p>
        <label for="setting-cap">Cap (the most a person's due-total can be; blank: no cap)</label>
        <input id="setting-cap" data-testid="setting-cap" formControlName="cap" inputmode="decimal" />
        <label for="setting-cap-from">Cap from (yyyy-mm-dd; blank: the sheet's today)</label>
        <input id="setting-cap-from" data-testid="setting-cap-from" formControlName="capFrom" />
        <p>Cap now: <span data-testid="setting-cap-current">{{ (cap() ?? '') | figure }}</span></p>
        <label for="setting-proof-threshold">Proof threshold (a claim due above it needs a proof to be settled; blank: none)</label>
        <input id="setting-proof-threshold" data-testid="setting-proof-threshold" formControlName="proofThreshold" inputmode="decimal" />
        <label for="setting-proof-threshold-from">Proof threshold from (yyyy-mm-dd; blank: the sheet's today)</label>
        <input id="setting-proof-threshold-from" data-testid="setting-proof-threshold-from" formControlName="proofThresholdFrom" />
        <p>Proof threshold now: <span data-testid="setting-proof-threshold-current">{{ (proofThreshold() ?? '') | figure }}</span></p>
        @for (g of grades; track g) {
          <label [for]="'setting-proof-threshold-' + g">Proof threshold of grade {{ g }} (blank: the sheet's)</label>
          <input [id]="'setting-proof-threshold-' + g" [attr.data-testid]="'setting-proof-threshold-' + g" [formControl]="gradeProofThresholdForm.controls[g]" inputmode="decimal" />
          <label [for]="'setting-proof-threshold-' + g + '-from'">Proof threshold of grade {{ g }} from (yyyy-mm-dd; blank: the sheet's today)</label>
          <input [id]="'setting-proof-threshold-' + g + '-from'" [attr.data-testid]="'setting-proof-threshold-' + g + '-from'" [formControl]="gradeProofThresholdFromForm.controls[g]" />
          <p>Proof threshold of grade {{ g }} now: <span [attr.data-testid]="'setting-proof-threshold-' + g + '-current'">{{ (now('proof-threshold-' + g) ?? '') | figure }}</span></p>
        }
        <label for="setting-note-threshold">Note threshold (a claim due above it needs a note to be opened; blank: none)</label>
        <input id="setting-note-threshold" data-testid="setting-note-threshold" formControlName="noteThreshold" inputmode="decimal" />
        <label for="setting-note-threshold-from">Note threshold from (yyyy-mm-dd; blank: the sheet's today)</label>
        <input id="setting-note-threshold-from" data-testid="setting-note-threshold-from" formControlName="noteThresholdFrom" />
        <p>Note threshold now: <span data-testid="setting-note-threshold-current">{{ (noteThreshold() ?? '') | figure }}</span></p>
        <label for="setting-review-threshold">Review threshold (a claim due above it goes to review when opened; blank: none)</label>
        <input id="setting-review-threshold" data-testid="setting-review-threshold" formControlName="reviewThreshold" inputmode="decimal" />
        <label for="setting-review-threshold-from">Review threshold from (yyyy-mm-dd; blank: the sheet's today)</label>
        <input id="setting-review-threshold-from" data-testid="setting-review-threshold-from" formControlName="reviewThresholdFrom" />
        <p>Review threshold now: <span data-testid="setting-review-threshold-current">{{ (reviewThreshold() ?? '') | figure }}</span></p>
        <label for="setting-grace-days">Grace days (days after an open period's to-date before a change of today closes it; blank: off)</label>
        <input id="setting-grace-days" data-testid="setting-grace-days" formControlName="graceDays" inputmode="numeric" />
        <p>Grace days now: <span data-testid="setting-grace-days-current">{{ graceDays() ?? '' }}</span></p>
        <label for="setting-entry-date">Entry date (yyyy-mm-dd; the date a new claim given none takes; blank: the sheet's today)</label>
        <input id="setting-entry-date" data-testid="setting-entry-date" formControlName="entryDate" />
        <p>Entry date now: <span data-testid="setting-entry-date-current">{{ entryDate() ?? '' }}</span></p>
        <label for="setting-pot">Pot (shared out among a balanced pool's members by their shares; blank: none)</label>
        <input id="setting-pot" data-testid="setting-pot" formControlName="pot" inputmode="decimal" />
        <p>Pot now: <span data-testid="setting-pot-current">{{ (pot() ?? '') | figure }}</span></p>
        <label for="setting-proof-kinds">Proof kinds (comma separated, of receipt and photo; a claim above the proof threshold needs a proof of each; blank: none)</label>
        <input id="setting-proof-kinds" data-testid="setting-proof-kinds" formControlName="proofKinds" />
        <p>Proof kinds now: <span data-testid="setting-proof-kinds-current">{{ proofKinds() ?? '' }}</span></p>
        <label for="setting-default-grade">Default grade (A, B or C; the grade quick add gives a new person, and an imported line with a blank grade takes; blank: A)</label>
        <input id="setting-default-grade" data-testid="setting-default-grade" formControlName="defaultGrade" />
        <p>Default grade now: <span data-testid="setting-default-grade-current">{{ defaultGrade() ?? '' }}</span></p>
        <label for="setting-alert-threshold">Alert threshold (a due-total going above it is alerted; blank: off)</label>
        <input id="setting-alert-threshold" data-testid="setting-alert-threshold" formControlName="alertThreshold" inputmode="decimal" />
        <p>Alert threshold now: <span data-testid="setting-alert-threshold-current">{{ (alertThreshold() ?? '') | figure }}</span></p>
        <label for="setting-escalate-after">Escalation gap (days since a claim's previous reminder after which reminding it escalates it; blank: off)</label>
        <input id="setting-escalate-after" data-testid="setting-escalate-after" formControlName="escalateAfter" inputmode="numeric" />
        <p>Escalation gap now: <span data-testid="setting-escalate-after-current">{{ escalateAfter() ?? '' }}</span></p>
        <label for="setting-review-sla">Review SLA (days a claim may wait in review from the day it went to review; blank: none)</label>
        <input id="setting-review-sla" data-testid="setting-review-sla" formControlName="reviewSla" inputmode="numeric" />
        <p>Review SLA now: <span data-testid="setting-review-sla-current">{{ reviewSla() ?? '' }}</span></p>
        <label for="setting-score-mid">Mid score threshold (a whole number; a score at or above it is MID while both thresholds are set; blank: no bands)</label>
        <input id="setting-score-mid" data-testid="setting-score-mid" formControlName="scoreMid" inputmode="numeric" />
        <p>Mid score threshold now: <span data-testid="setting-score-mid-current">{{ scoreMid() ?? '' }}</span></p>
        <label for="setting-score-high">High score threshold (a whole number; a score at or above it is HIGH while both thresholds are set; blank: no bands)</label>
        <input id="setting-score-high" data-testid="setting-score-high" formControlName="scoreHigh" inputmode="numeric" />
        <p>High score threshold now: <span data-testid="setting-score-high-current">{{ scoreHigh() ?? '' }}</span></p>
        <label for="setting-score-log">Score log (on: changing today logs each person's score on the day being left; blank: off)</label>
        <input id="setting-score-log" data-testid="setting-score-log" formControlName="scoreLog" />
        <p>Score log now: <span data-testid="setting-score-log-current">{{ scoreLog() ?? '' }}</span></p>
        <label for="setting-parent">Parent sheet (its rates are used where this sheet has none; blank: none)</label>
        <input id="setting-parent" data-testid="setting-parent" formControlName="parent" />
        <p>Parent sheet now: <span data-testid="setting-parent-current">{{ parent() ?? '' }}</span></p>
        <label for="setting-fiscal-start-month">Fiscal year start month (1 to 12)</label>
        <input id="setting-fiscal-start-month" data-testid="setting-fiscal-start-month" formControlName="fiscalStartMonth" inputmode="numeric" />
        <p>Fiscal year start month now: <span data-testid="setting-fiscal-start-month-current">{{ fiscalStartMonth() }}</span></p>
        <fieldset>
          <legend>Permissions (the roles that may take each action, comma separated; blank: the standing rule)</legend>
          @for (a of permissionActions; track a) {
            <label [for]="'setting-perm-' + a">Who may {{ a }} (blank: {{ standing[a] }})</label>
            <input [id]="'setting-perm-' + a" [attr.data-testid]="'setting-perm-' + a" [formControl]="permissionsForm.controls[a]" />
            <p>Who may {{ a }} now: <span [attr.data-testid]="'setting-perm-' + a + '-current'">{{ permissions()[a] ?? '' }}</span></p>
          }
        </fieldset>
        <button type="submit" data-testid="settings-save" [disabled]="savingSettings()">Save settings</button>
        @if (settingsError()) {
          <p role="alert" data-testid="settings-error">{{ settingsError() }}</p>
        }
      </form>
      <h3 id="roles-heading">Roles</h3>
      <form [formGroup]="roleForm" (ngSubmit)="saveRole()" aria-label="Assign a role">
        <label for="role-form-user">User</label>
        <input id="role-form-user" data-testid="role-form-user" formControlName="user" />
        <label for="role-form-role">Role (clerk, steward, viewer or restricted)</label>
        <input id="role-form-role" data-testid="role-form-role" formControlName="role" list="role-options" />
        <datalist id="role-options">
          @for (r of roles; track r) {
            <option [value]="r"></option>
          }
        </datalist>
        <button type="submit" data-testid="role-form-submit" [disabled]="savingRole()">Assign role</button>
        @if (roleError()) {
          <p role="alert" data-testid="role-form-error">{{ roleError() }}</p>
        }
      </form>
      <table data-testid="roles-list" aria-labelledby="roles-heading">
        <thead>
          <tr><th scope="col">User</th><th scope="col">Role</th></tr>
        </thead>
        <tbody>
          @for (r of assigned(); track r.user) {
            <tr [attr.data-testid]="'role-row-' + r.user">
              <th scope="row" data-testid="role-user">{{ r.user }}</th>
              <td data-testid="role-value">{{ r.role }}</td>
            </tr>
          }
        </tbody>
      </table>
      <h3 id="delegations-heading">Delegations</h3>
      <form [formGroup]="delegateForm" (ngSubmit)="saveDelegation()" aria-label="Delegate the steward role">
        <label for="delegate-user">User</label>
        <input id="delegate-user" data-testid="delegate-user" formControlName="user" />
        <label for="delegate-from">From (yyyy-mm-dd; blank: the sheet's today)</label>
        <input id="delegate-from" data-testid="delegate-from" formControlName="from" />
        <label for="delegate-to">To (yyyy-mm-dd)</label>
        <input id="delegate-to" data-testid="delegate-to" formControlName="to" />
        <button type="submit" data-testid="delegate-submit" [disabled]="savingDelegation()">Delegate</button>
        @if (delegateError()) {
          <p role="alert" data-testid="delegate-error">{{ delegateError() }}</p>
        }
      </form>
      <ul data-testid="delegations-list" aria-labelledby="delegations-heading">
        @for (d of delegations(); track d.id) {
          <li data-testid="delegate-line">{{ d.by }} delegated to {{ d.user }} from {{ d.from }} to {{ d.to }}</li>
        }
      </ul>
    </section>
  `,
})
export class Settings {
  private readonly session = inject(Session);
  private readonly api = inject(SettingsApi);
  private readonly sheetFloor = inject(SheetFloor);
  private readonly sheetLatePct = inject(SheetLatePct);
  private readonly sheetRounding = inject(SheetRounding);
  private readonly sheetPolicyDate = inject(SheetPolicyDate);
  private readonly sheetCap = inject(SheetCap);
  private readonly sheetGrace = inject(SheetGrace);
  private readonly sheetPot = inject(SheetPot);
  private readonly sheetParent = inject(SheetParent);
  private readonly sheetFiscal = inject(SheetFiscal);
  private readonly sheetReviewSla = inject(SheetReviewSla);
  private readonly sheetScoreBands = inject(SheetScoreBands);

  protected readonly roles = ROLES;
  protected readonly grades = GRADES;
  protected readonly permissionActions = PERMISSION_ACTIONS;
  /** Who may take each action while its permission is blank. */
  protected readonly standing: Record<PermissionAction, string> = {
    open: 'clerk,steward', settle: 'clerk,steward', void: 'clerk,steward', close: 'clerk,steward',
    reassign: 'steward', reopen: 'steward',
  };
  /** By key, the values the dated settings take from their from-dates. */
  private readonly dated = signal<Partial<Record<string, DatedValue[]>>>({});
  /** The value of a dated setting in force on the sheet's today, or null when it has none. */
  protected now(key: string): string | null {
    return valueOn(this.dated()[key] ?? [], this.session.sheet()?.today);
  }
  protected readonly clerkLimit = computed(() => this.now('clerk-limit'));
  protected readonly floor = computed(() => this.now('floor') ?? (DEFAULT_FLOOR_CENTS / 100).toFixed(2));
  protected readonly rounding = computed(() => this.now('rounding'));
  protected readonly terms = computed(() => Number(this.now('terms') ?? DEFAULT_TERMS));
  protected readonly latePct = computed(() => this.now('late-pct') ?? DEFAULT_LATE_PCT);
  protected readonly policyDate = signal<string | null>(null);
  protected readonly periodNumberingFrom = signal<string | null>(null);
  protected readonly cap = computed(() => this.now('cap'));
  protected readonly proofThreshold = computed(() => this.now('proof-threshold'));
  protected readonly noteThreshold = computed(() => this.now('note-threshold'));
  protected readonly reviewThreshold = computed(() => this.now('review-threshold'));
  protected readonly graceDays = signal<number | null>(null);
  protected readonly entryDate = signal<string | null>(null);
  protected readonly pot = signal<string | null>(null);
  protected readonly alertThreshold = signal<string | null>(null);
  protected readonly proofKinds = signal<string | null>(null);
  protected readonly defaultGrade = signal<string | null>(null);
  protected readonly escalateAfter = signal<number | null>(null);
  protected readonly reviewSla = signal<number | null>(null);
  protected readonly scoreMid = signal<number | null>(null);
  protected readonly scoreHigh = signal<number | null>(null);
  protected readonly scoreLog = signal<'on' | null>(null);
  protected readonly parent = signal<string | null>(null);
  protected readonly fiscalStartMonth = signal(DEFAULT_FISCAL_START_MONTH);
  protected readonly assigned = signal<RoleView[]>([]);
  protected readonly permissions = signal<Partial<Record<PermissionAction, string>>>({});
  protected readonly delegations = signal<DelegationView[]>([]);

  protected readonly settingsForm = new FormGroup({
    clerkLimit: new FormControl('', { nonNullable: true }),
    clerkLimitFrom: new FormControl('', { nonNullable: true }),
    floor: new FormControl('', { nonNullable: true }),
    floorFrom: new FormControl('', { nonNullable: true }),
    rounding: new FormControl('', { nonNullable: true }),
    roundingFrom: new FormControl('', { nonNullable: true }),
    terms: new FormControl('', { nonNullable: true }),
    termsFrom: new FormControl('', { nonNullable: true }),
    latePct: new FormControl('', { nonNullable: true }),
    latePctFrom: new FormControl('', { nonNullable: true }),
    policyDate: new FormControl('', { nonNullable: true }),
    periodNumberingFrom: new FormControl('', { nonNullable: true }),
    cap: new FormControl('', { nonNullable: true }),
    capFrom: new FormControl('', { nonNullable: true }),
    proofThreshold: new FormControl('', { nonNullable: true }),
    proofThresholdFrom: new FormControl('', { nonNullable: true }),
    noteThreshold: new FormControl('', { nonNullable: true }),
    noteThresholdFrom: new FormControl('', { nonNullable: true }),
    reviewThreshold: new FormControl('', { nonNullable: true }),
    reviewThresholdFrom: new FormControl('', { nonNullable: true }),
    graceDays: new FormControl('', { nonNullable: true }),
    entryDate: new FormControl('', { nonNullable: true }),
    pot: new FormControl('', { nonNullable: true }),
    alertThreshold: new FormControl('', { nonNullable: true }),
    proofKinds: new FormControl('', { nonNullable: true }),
    defaultGrade: new FormControl('', { nonNullable: true }),
    escalateAfter: new FormControl('', { nonNullable: true }),
    reviewSla: new FormControl('', { nonNullable: true }),
    scoreMid: new FormControl('', { nonNullable: true }),
    scoreHigh: new FormControl('', { nonNullable: true }),
    scoreLog: new FormControl('', { nonNullable: true }),
    parent: new FormControl('', { nonNullable: true }),
    fiscalStartMonth: new FormControl('', { nonNullable: true }),
  });
  /** Each grade's own terms, a field per grade; part of the settings form. */
  protected readonly gradeTermsForm = new FormGroup({
    A: new FormControl('', { nonNullable: true }),
    B: new FormControl('', { nonNullable: true }),
    C: new FormControl('', { nonNullable: true }),
  } satisfies Record<Grade, FormControl<string>>);
  /** The date each grade's own terms are recorded from, a field per grade; blank: the sheet's today. */
  protected readonly gradeTermsFromForm = new FormGroup({
    A: new FormControl('', { nonNullable: true }),
    B: new FormControl('', { nonNullable: true }),
    C: new FormControl('', { nonNullable: true }),
  } satisfies Record<Grade, FormControl<string>>);
  /** Each grade's grace days, a field per grade; part of the settings form. */
  protected readonly gradeGraceForm = new FormGroup({
    A: new FormControl('', { nonNullable: true }),
    B: new FormControl('', { nonNullable: true }),
    C: new FormControl('', { nonNullable: true }),
  } satisfies Record<Grade, FormControl<string>>);
  /** The date each grade's grace days are recorded from, a field per grade; blank: the sheet's today. */
  protected readonly gradeGraceFromForm = new FormGroup({
    A: new FormControl('', { nonNullable: true }),
    B: new FormControl('', { nonNullable: true }),
    C: new FormControl('', { nonNullable: true }),
  } satisfies Record<Grade, FormControl<string>>);
  /** Each grade's own clerk limit, a field per grade; part of the settings form. */
  protected readonly gradeClerkLimitForm = new FormGroup({
    A: new FormControl('', { nonNullable: true }),
    B: new FormControl('', { nonNullable: true }),
    C: new FormControl('', { nonNullable: true }),
  } satisfies Record<Grade, FormControl<string>>);
  /** The date each grade's clerk limit is recorded from, a field per grade; blank: the sheet's today. */
  protected readonly gradeClerkLimitFromForm = new FormGroup({
    A: new FormControl('', { nonNullable: true }),
    B: new FormControl('', { nonNullable: true }),
    C: new FormControl('', { nonNullable: true }),
  } satisfies Record<Grade, FormControl<string>>);
  /** Each grade's own proof threshold, a field per grade; part of the settings form. */
  protected readonly gradeProofThresholdForm = new FormGroup({
    A: new FormControl('', { nonNullable: true }),
    B: new FormControl('', { nonNullable: true }),
    C: new FormControl('', { nonNullable: true }),
  } satisfies Record<Grade, FormControl<string>>);
  /** The date each grade's proof threshold is recorded from, a field per grade; blank: the sheet's today. */
  protected readonly gradeProofThresholdFromForm = new FormGroup({
    A: new FormControl('', { nonNullable: true }),
    B: new FormControl('', { nonNullable: true }),
    C: new FormControl('', { nonNullable: true }),
  } satisfies Record<Grade, FormControl<string>>);
  /** The roles that may take each action, a field per action; part of the settings form. */
  protected readonly permissionsForm = new FormGroup({
    open: new FormControl('', { nonNullable: true }),
    settle: new FormControl('', { nonNullable: true }),
    void: new FormControl('', { nonNullable: true }),
    reassign: new FormControl('', { nonNullable: true }),
    close: new FormControl('', { nonNullable: true }),
    reopen: new FormControl('', { nonNullable: true }),
  } satisfies Record<PermissionAction, FormControl<string>>);
  protected readonly roleForm = new FormGroup({
    user: new FormControl('', { nonNullable: true }),
    role: new FormControl('', { nonNullable: true }),
  });
  protected readonly delegateForm = new FormGroup({
    user: new FormControl('', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
  });
  protected readonly savingDelegation = signal(false);
  protected readonly delegateError = signal('');
  protected readonly savingSettings = signal(false);
  protected readonly savingRole = signal(false);
  protected readonly settingsError = signal('');
  protected readonly roleError = signal('');

  constructor() {
    this.settingsForm.valueChanges.subscribe(() => this.settingsError.set(''));
    this.gradeTermsForm.valueChanges.subscribe(() => this.settingsError.set(''));
    this.gradeTermsFromForm.valueChanges.subscribe(() => this.settingsError.set(''));
    this.gradeGraceForm.valueChanges.subscribe(() => this.settingsError.set(''));
    this.gradeGraceFromForm.valueChanges.subscribe(() => this.settingsError.set(''));
    this.gradeClerkLimitForm.valueChanges.subscribe(() => this.settingsError.set(''));
    this.gradeClerkLimitFromForm.valueChanges.subscribe(() => this.settingsError.set(''));
    this.gradeProofThresholdForm.valueChanges.subscribe(() => this.settingsError.set(''));
    this.gradeProofThresholdFromForm.valueChanges.subscribe(() => this.settingsError.set(''));
    this.permissionsForm.valueChanges.subscribe(() => this.settingsError.set(''));
    this.roleForm.valueChanges.subscribe(() => this.roleError.set(''));
    this.delegateForm.valueChanges.subscribe(() => this.delegateError.set(''));
    // Each load of a sheet re-reads its settings from the store and starts the forms afresh.
    effect(() => {
      const sheet = this.session.loaded();
      this.settingsError.set('');
      this.roleError.set('');
      this.delegateError.set('');
      this.show({ clerkLimit: null, floor: (DEFAULT_FLOOR_CENTS / 100).toFixed(2), terms: DEFAULT_TERMS, latePct: DEFAULT_LATE_PCT, policyDate: null, roles: [], periodNumberingFrom: null, gradeTerms: {}, cap: null, proofThreshold: null, noteThreshold: null, dated: {}, permissions: {}, graceDays: null, delegations: [], entryDate: null, pot: null, parent: null, fiscalStartMonth: DEFAULT_FISCAL_START_MONTH, alertThreshold: null, escalateAfter: null, reviewSla: null, proofKinds: null, defaultGrade: null, scoreMid: null, scoreHigh: null, scoreLog: null });
      this.settingsForm.markAsPristine();
      this.settingsForm.markAsUntouched();
      this.gradeTermsForm.markAsPristine();
      this.gradeTermsForm.markAsUntouched();
      this.gradeTermsFromForm.markAsPristine();
      this.gradeTermsFromForm.markAsUntouched();
      this.gradeGraceForm.markAsPristine();
      this.gradeGraceForm.markAsUntouched();
      this.gradeGraceFromForm.markAsPristine();
      this.gradeGraceFromForm.markAsUntouched();
      this.gradeClerkLimitForm.markAsPristine();
      this.gradeClerkLimitForm.markAsUntouched();
      this.gradeClerkLimitFromForm.markAsPristine();
      this.gradeClerkLimitFromForm.markAsUntouched();
      this.gradeProofThresholdForm.markAsPristine();
      this.gradeProofThresholdForm.markAsUntouched();
      this.gradeProofThresholdFromForm.markAsPristine();
      this.gradeProofThresholdFromForm.markAsUntouched();
      this.permissionsForm.markAsPristine();
      this.permissionsForm.markAsUntouched();
      if (sheet) void this.reload(sheet.id);
    });
  }

  private async reload(sheetId: string): Promise<void> {
    try {
      const settings = await this.session.reading(this.api.get(sheetId));
      if (this.session.sheet()?.id === sheetId) this.show(settings);
    } catch {
      this.settingsError.set('The settings could not be loaded.');
    }
  }

  protected async saveSettings(): Promise<void> {
    // A second submit before the first has answered is ignored, so it stores one change, not two.
    if (this.savingSettings()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.settingsError.set('');
    const { clerkLimit, clerkLimitFrom, floor, floorFrom, rounding, roundingFrom, terms, termsFrom, latePct, latePctFrom, policyDate,
      periodNumberingFrom, cap, capFrom, proofThreshold, proofThresholdFrom,
      noteThreshold, noteThresholdFrom, reviewThreshold, reviewThresholdFrom, graceDays, entryDate, pot, alertThreshold, proofKinds, defaultGrade, escalateAfter, reviewSla, scoreMid, scoreHigh, scoreLog, parent, fiscalStartMonth } = this.settingsForm.controls;
    // A dated setting is saved when its value or its from-date was changed; the from-date checked first.
    const froms = [
      { label: 'clerk limit', control: clerkLimitFrom }, { label: 'floor', control: floorFrom },
      { label: 'rounding mode', control: roundingFrom },
      { label: 'terms', control: termsFrom }, { label: 'late percent', control: latePctFrom },
      { label: 'cap', control: capFrom }, { label: 'proof threshold', control: proofThresholdFrom },
      { label: 'note threshold', control: noteThresholdFrom },
      { label: 'review threshold', control: reviewThresholdFrom },
      ...GRADES.map((g) => ({ label: `clerk limit of grade ${g}`, control: this.gradeClerkLimitFromForm.controls[g] })),
      ...GRADES.map((g) => ({ label: `terms of grade ${g}`, control: this.gradeTermsFromForm.controls[g] })),
      ...GRADES.map((g) => ({ label: `grace of grade ${g}`, control: this.gradeGraceFromForm.controls[g] })),
      ...GRADES.map((g) => ({ label: `proof threshold of grade ${g}`, control: this.gradeProofThresholdFromForm.controls[g] })),
    ];
    for (const { label, control } of froms) {
      const from = control.value.trim();
      if (from !== '' && !isDate(from)) {
        this.settingsError.set(`Type the date the ${label} is from as yyyy-mm-dd, or leave it blank for the sheet's today.`);
        return;
      }
    }
    const fromOf = (control: AbstractControl<string>): string | null => control.value.trim() === '' ? null : control.value.trim();
    const dirty = (value: AbstractControl, from: AbstractControl): boolean => changed(value) || changed(from);
    const limit = clerkLimit.value.trim();
    const floorValue = floor.value.trim();
    const roundingValue = rounding.value.trim();
    const termsValue = terms.value.trim();
    const latePctValue = latePct.value.trim();
    const policyDateValue = policyDate.value.trim();
    const periodNumberingFromValue = periodNumberingFrom.value.trim();
    const capValue = cap.value.trim();
    const proofThresholdValue = proofThreshold.value.trim();
    const noteThresholdValue = noteThreshold.value.trim();
    const reviewThresholdValue = reviewThreshold.value.trim();
    const graceDaysValue = graceDays.value.trim();
    const entryDateValue = entryDate.value.trim();
    const potValue = pot.value.trim();
    const alertThresholdValue = alertThreshold.value.trim();
    const proofKindsValue = proofKinds.value.split(',').map((k) => k.trim().toLowerCase()).filter((k) => k !== '').join(',');
    const defaultGradeValue = defaultGrade.value.trim();
    const escalateAfterValue = escalateAfter.value.trim();
    const reviewSlaValue = reviewSla.value.trim();
    const scoreMidValue = scoreMid.value.trim();
    const scoreHighValue = scoreHigh.value.trim();
    const scoreLogValue = scoreLog.value.trim().toLowerCase();
    const parentValue = parent.value.trim();
    const fiscalStartMonthValue = fiscalStartMonth.value.trim();
    if (dirty(clerkLimit, clerkLimitFrom) && limit !== '' && !MONEY.test(limit)) {
      this.settingsError.set('Type a clerk limit of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    const gradeClerkLimitValues = GRADES.map((g) => ({ grade: g, control: this.gradeClerkLimitForm.controls[g],
      from: this.gradeClerkLimitFromForm.controls[g], value: this.gradeClerkLimitForm.controls[g].value.trim() }));
    for (const { grade, control, from, value } of gradeClerkLimitValues) {
      if (dirty(control, from) && value !== '' && !MONEY.test(value)) {
        this.settingsError.set(`Type the clerk limit of grade ${grade} as 0.00 or more, with up to two decimals, or leave it blank for the sheet's.`);
        return;
      }
    }
    if (dirty(floor, floorFrom) && !MONEY.test(floorValue)) {
      this.settingsError.set('Type a floor of 0.00 or more, with up to two decimals.');
      return;
    }
    if (dirty(rounding, roundingFrom) && roundingValue !== ''
      && !ROUNDING_MODES.some((m) => m === roundingValue)) {
      this.settingsError.set('Type a rounding mode of half-up or half-even, or leave it blank for none.');
      return;
    }
    if (dirty(terms, termsFrom) && (!DAYS.test(termsValue) || Number(termsValue) > 36500)) {
      this.settingsError.set('Type terms as a whole number of business days, from 0 to 36500.');
      return;
    }
    const gradeTermsValues = GRADES.map((g) => ({ grade: g, control: this.gradeTermsForm.controls[g],
      from: this.gradeTermsFromForm.controls[g], value: this.gradeTermsForm.controls[g].value.trim() }));
    for (const { grade, control, from, value } of gradeTermsValues) {
      if (dirty(control, from) && value !== '' && (!DAYS.test(value) || Number(value) > 36500)) {
        this.settingsError.set(`Type the terms of grade ${grade} as a whole number of business days, from 0 to 36500, or leave them blank for the sheet's terms.`);
        return;
      }
    }
    const gradeGraceValues = GRADES.map((g) => ({ grade: g, control: this.gradeGraceForm.controls[g],
      from: this.gradeGraceFromForm.controls[g], value: this.gradeGraceForm.controls[g].value.trim() }));
    for (const { grade, control, from, value } of gradeGraceValues) {
      if (dirty(control, from) && value !== '' && (!DAYS.test(value) || Number(value) > 36500)) {
        this.settingsError.set(`Type the grace of grade ${grade} as a whole number of days, from 0 to 36500, or leave it blank for none.`);
        return;
      }
    }
    if (dirty(latePct, latePctFrom) && !PCT.test(latePctValue)) {
      this.settingsError.set('Type a late percent of 0.00 or more, with up to two decimals.');
      return;
    }
    if (changed(policyDate) && policyDateValue !== '' && !isDate(policyDateValue)) {
      this.settingsError.set('Type a policy date as yyyy-mm-dd, or leave it blank for the sheet\'s today.');
      return;
    }
    if (changed(periodNumberingFrom) && periodNumberingFromValue !== '' && !isDate(periodNumberingFromValue)) {
      this.settingsError.set('Type a period numbering date as yyyy-mm-dd, or leave it blank for sheet-wide numbering.');
      return;
    }
    if (dirty(cap, capFrom) && capValue !== '' && !MONEY.test(capValue)) {
      this.settingsError.set('Type a cap of 0.00 or more, with up to two decimals, or leave it blank for no cap.');
      return;
    }
    if (dirty(proofThreshold, proofThresholdFrom) && proofThresholdValue !== '' && !MONEY.test(proofThresholdValue)) {
      this.settingsError.set('Type a proof threshold of 0.00 or more, with up to two decimals, or leave it blank for none.');
      return;
    }
    const gradeProofThresholdValues = GRADES.map((g) => ({ grade: g, control: this.gradeProofThresholdForm.controls[g],
      from: this.gradeProofThresholdFromForm.controls[g], value: this.gradeProofThresholdForm.controls[g].value.trim() }));
    for (const { grade, control, from, value } of gradeProofThresholdValues) {
      if (dirty(control, from) && value !== '' && !MONEY.test(value)) {
        this.settingsError.set(`Type the proof threshold of grade ${grade} as 0.00 or more, with up to two decimals, or leave it blank for the sheet's.`);
        return;
      }
    }
    if (dirty(noteThreshold, noteThresholdFrom) && noteThresholdValue !== '' && !MONEY.test(noteThresholdValue)) {
      this.settingsError.set('Type a note threshold of 0.00 or more, with up to two decimals, or leave it blank for none.');
      return;
    }
    if (dirty(reviewThreshold, reviewThresholdFrom) && reviewThresholdValue !== '' && !MONEY.test(reviewThresholdValue)) {
      this.settingsError.set('Type a review threshold of 0.00 or more, with up to two decimals, or leave it blank for none.');
      return;
    }
    if (changed(graceDays) && graceDaysValue !== '' && (!DAYS.test(graceDaysValue) || Number(graceDaysValue) > 36500)) {
      this.settingsError.set('Type grace days as a whole number of business days, from 0 to 36500, or leave them blank for off.');
      return;
    }
    if (changed(entryDate) && entryDateValue !== '' && !isDate(entryDateValue)) {
      this.settingsError.set('Type an entry date as yyyy-mm-dd, or leave it blank for the sheet\'s today.');
      return;
    }
    if (changed(pot) && potValue !== '' && !MONEY.test(potValue)) {
      this.settingsError.set('Type a pot of 0.00 or more, with up to two decimals, or leave it blank for none.');
      return;
    }
    if (changed(alertThreshold) && alertThresholdValue !== '' && !MONEY.test(alertThresholdValue)) {
      this.settingsError.set('Type an alert threshold of 0.00 or more, with up to two decimals, or leave it blank for off.');
      return;
    }
    if (changed(proofKinds) && proofKindsValue.split(',').some((k) => k !== '' && !(PROOF_KINDS as readonly string[]).includes(k))) {
      this.settingsError.set('Type proof kinds as a comma separated list of receipt and photo, or leave it blank for none.');
      return;
    }
    if (changed(defaultGrade) && defaultGradeValue !== '' && !(GRADES as readonly string[]).includes(defaultGradeValue)) {
      this.settingsError.set('Type a default grade of A, B or C, or leave it blank for A.');
      return;
    }
    if (changed(parent) && parentValue === sheet.id) {
      this.settingsError.set('A sheet cannot be its own parent.');
      return;
    }
    if (changed(escalateAfter) && escalateAfterValue !== '' && (!DAYS.test(escalateAfterValue) || Number(escalateAfterValue) > 36500)) {
      this.settingsError.set('Type the escalation gap as a whole number of days, from 0 to 36500, or leave it blank for off.');
      return;
    }
    if (changed(reviewSla) && reviewSlaValue !== '' && (!DAYS.test(reviewSlaValue) || Number(reviewSlaValue) > 36500)) {
      this.settingsError.set('Type the review SLA as a whole number of days, from 0 to 36500, or leave it blank for none.');
      return;
    }
    if (changed(scoreMid) && scoreMidValue !== '' && !WHOLE.test(scoreMidValue)) {
      this.settingsError.set('Type the mid score threshold as a whole number, or leave it blank.');
      return;
    }
    if (changed(scoreHigh) && scoreHighValue !== '' && !WHOLE.test(scoreHighValue)) {
      this.settingsError.set('Type the high score threshold as a whole number, or leave it blank.');
      return;
    }
    if (changed(scoreLog) && scoreLogValue !== '' && scoreLogValue !== 'on') {
      this.settingsError.set('Type on for the score log, or leave it blank for off.');
      return;
    }
    if (changed(fiscalStartMonth) && (!MONTH.test(fiscalStartMonthValue) || Number(fiscalStartMonthValue) < 1
      || Number(fiscalStartMonthValue) > 12)) {
      this.settingsError.set('Type the fiscal year start month as a whole number from 1 to 12.');
      return;
    }
    const permissionValues = PERMISSION_ACTIONS.map((a) => ({ action: a, control: this.permissionsForm.controls[a],
      value: this.permissionsForm.controls[a].value.split(',').map((r) => r.trim()).join(',') }));
    for (const { action, control, value } of permissionValues) {
      if (changed(control) && value !== '' && !value.split(',').every((r) => ROLES.includes(r as Role))) {
        this.settingsError.set(`Name who may ${action} as a comma separated list of clerk, steward and viewer, or leave it blank for the standing rule.`);
        return;
      }
    }
    this.savingSettings.set(true);
    try {
      if (dirty(clerkLimit, clerkLimitFrom)) {
        const settings = await this.api.setClerkLimit(sheet.id, limit === '' ? null : limit, fromOf(clerkLimitFrom));
        settled(clerkLimit);
        settled(clerkLimitFrom);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      for (const { grade, control, from, value } of gradeClerkLimitValues) {
        if (!dirty(control, from)) continue;
        const settings = await this.api.setGradeClerkLimit(sheet.id, grade, value === '' ? null : value, fromOf(from));
        settled(control);
        settled(from);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      if (dirty(floor, floorFrom)) {
        const settings = await this.api.setFloor(sheet.id, floorValue, fromOf(floorFrom));
        settled(floor);
        settled(floorFrom);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      if (dirty(rounding, roundingFrom)) {
        const settings = await this.api.setRounding(sheet.id, roundingValue, fromOf(roundingFrom));
        settled(rounding);
        settled(roundingFrom);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      if (dirty(terms, termsFrom)) {
        const settings = await this.api.setTerms(sheet.id, Number(termsValue), fromOf(termsFrom));
        settled(terms);
        settled(termsFrom);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      for (const { grade, control, from, value } of gradeTermsValues) {
        if (!dirty(control, from)) continue;
        const settings = await this.api.setGradeTerms(sheet.id, grade, value === '' ? null : Number(value), fromOf(from));
        settled(control);
        settled(from);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      if (dirty(latePct, latePctFrom)) {
        const settings = await this.api.setLatePct(sheet.id, latePctValue, fromOf(latePctFrom));
        settled(latePct);
        settled(latePctFrom);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      for (const { grade, control, from, value } of gradeGraceValues) {
        if (!dirty(control, from)) continue;
        const settings = await this.api.setGradeGrace(sheet.id, grade, value === '' ? null : Number(value), fromOf(from));
        settled(control);
        settled(from);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      if (changed(policyDate)) {
        const settings = await this.api.setPolicyDate(sheet.id, policyDateValue === '' ? null : policyDateValue);
        settled(policyDate);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      if (changed(periodNumberingFrom)) {
        const settings = await this.api.setPeriodNumberingFrom(sheet.id, periodNumberingFromValue === '' ? null : periodNumberingFromValue);
        settled(periodNumberingFrom);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      if (dirty(cap, capFrom)) {
        const settings = await this.api.setCap(sheet.id, capValue === '' ? null : capValue, fromOf(capFrom));
        settled(cap);
        settled(capFrom);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      if (dirty(proofThreshold, proofThresholdFrom)) {
        const settings = await this.api.setProofThreshold(sheet.id, proofThresholdValue === '' ? null : proofThresholdValue,
          fromOf(proofThresholdFrom));
        settled(proofThreshold);
        settled(proofThresholdFrom);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      for (const { grade, control, from, value } of gradeProofThresholdValues) {
        if (!dirty(control, from)) continue;
        const settings = await this.api.setGradeProofThreshold(sheet.id, grade, value === '' ? null : value, fromOf(from));
        settled(control);
        settled(from);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      if (dirty(noteThreshold, noteThresholdFrom)) {
        const settings = await this.api.setNoteThreshold(sheet.id, noteThresholdValue === '' ? null : noteThresholdValue,
          fromOf(noteThresholdFrom));
        settled(noteThreshold);
        settled(noteThresholdFrom);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      if (dirty(reviewThreshold, reviewThresholdFrom)) {
        const settings = await this.api.setReviewThreshold(sheet.id, reviewThresholdValue === '' ? null : reviewThresholdValue,
          fromOf(reviewThresholdFrom));
        settled(reviewThreshold);
        settled(reviewThresholdFrom);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      if (changed(graceDays)) {
        const settings = await this.api.setGraceDays(sheet.id, graceDaysValue === '' ? null : Number(graceDaysValue));
        settled(graceDays);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      if (changed(entryDate)) {
        const settings = await this.api.setEntryDate(sheet.id, entryDateValue === '' ? null : entryDateValue);
        settled(entryDate);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      if (changed(pot)) {
        const settings = await this.api.setPot(sheet.id, potValue === '' ? null : potValue);
        settled(pot);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      if (changed(alertThreshold)) {
        const settings = await this.api.setAlertThreshold(sheet.id, alertThresholdValue === '' ? null : alertThresholdValue);
        settled(alertThreshold);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      if (changed(proofKinds)) {
        const settings = await this.api.setProofKinds(sheet.id, proofKindsValue === '' ? null : proofKindsValue);
        settled(proofKinds);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      if (changed(defaultGrade)) {
        const settings = await this.api.setDefaultGrade(sheet.id, defaultGradeValue === '' ? null : defaultGradeValue);
        settled(defaultGrade);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      if (changed(escalateAfter)) {
        const settings = await this.api.setEscalateAfter(sheet.id, escalateAfterValue === '' ? null : Number(escalateAfterValue));
        settled(escalateAfter);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      if (changed(reviewSla)) {
        const settings = await this.api.setReviewSla(sheet.id, reviewSlaValue === '' ? null : Number(reviewSlaValue));
        settled(reviewSla);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      if (changed(scoreMid)) {
        const settings = await this.api.setScoreMid(sheet.id, scoreMidValue === '' ? null : Number(scoreMidValue));
        settled(scoreMid);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      if (changed(scoreHigh)) {
        const settings = await this.api.setScoreHigh(sheet.id, scoreHighValue === '' ? null : Number(scoreHighValue));
        settled(scoreHigh);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      if (changed(scoreLog)) {
        const settings = await this.api.setScoreLog(sheet.id, scoreLogValue === '' ? null : 'on');
        settled(scoreLog);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      if (changed(parent)) {
        const settings = await this.api.setParent(sheet.id, parentValue === '' ? null : parentValue);
        settled(parent);
        if (this.session.sheet()?.id === sheet.id) {
          this.show(settings);
          this.sheetParent.changes.update((n) => n + 1);
        }
      }
      if (changed(fiscalStartMonth)) {
        const settings = await this.api.setFiscalStartMonth(sheet.id, Number(fiscalStartMonthValue));
        settled(fiscalStartMonth);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      for (const { action, control, value } of permissionValues) {
        if (!changed(control)) continue;
        const settings = await this.api.setPermission(sheet.id, action, value);
        settled(control);
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
    } catch {
      this.settingsError.set('The settings could not be saved.');
    } finally {
      this.savingSettings.set(false);
    }
  }

  /** The terms in force for a grade on the sheet's today: its own, else the sheet's. */
  protected gradeTermsNow(grade: Grade): number {
    const own = this.now(`terms-${grade}`);
    return own === null ? this.terms() : Number(own);
  }

  /** Show the settings as the store answered them, and share the dated floor, late percent, cap and grades' grace, and
   *  the policy date, the pot and the fiscal start month, with the screens showing them. */
  private show(settings: SettingsView): void {
    this.dated.set(settings.dated);
    this.sheetFloor.history.set(settings.dated['floor'] ?? []);
    this.sheetLatePct.history.set(settings.dated['late-pct'] ?? []);
    this.sheetRounding.history.set(settings.dated['rounding'] ?? []);
    this.sheetCap.history.set(settings.dated['cap'] ?? []);
    this.sheetGrace.history.set(Object.fromEntries(GRADES.map((g) => [g, settings.dated[`grace-${g}`] ?? []])));
    this.policyDate.set(settings.policyDate);
    this.sheetPolicyDate.date.set(settings.policyDate);
    this.periodNumberingFrom.set(settings.periodNumberingFrom);
    this.assigned.set(settings.roles);
    this.permissions.set(settings.permissions);
    this.graceDays.set(settings.graceDays);
    this.entryDate.set(settings.entryDate);
    this.pot.set(settings.pot);
    this.sheetPot.pot.set(settings.pot);
    this.alertThreshold.set(settings.alertThreshold);
    this.proofKinds.set(settings.proofKinds);
    this.defaultGrade.set(settings.defaultGrade);
    this.escalateAfter.set(settings.escalateAfter);
    this.reviewSla.set(settings.reviewSla);
    this.sheetReviewSla.days.set(settings.reviewSla);
    this.scoreMid.set(settings.scoreMid);
    this.scoreHigh.set(settings.scoreHigh);
    this.scoreLog.set(settings.scoreLog);
    this.sheetScoreBands.mid.set(settings.scoreMid);
    this.sheetScoreBands.high.set(settings.scoreHigh);
    this.parent.set(settings.parent);
    this.sheetParent.parent.set(settings.parent);
    this.delegations.set(settings.delegations);
    this.fiscalStartMonth.set(settings.fiscalStartMonth);
    this.sheetFiscal.startMonth.set(settings.fiscalStartMonth);
  }

  protected async saveRole(): Promise<void> {
    if (this.savingRole()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.roleError.set('');
    const user = this.roleForm.getRawValue().user.trim();
    const role = this.roleForm.getRawValue().role.trim();
    if (user === '') {
      this.roleError.set('Type the user name to assign a role to.');
      return;
    }
    if (!ROLES.includes(role as Role)) {
      this.roleError.set('Choose a role of clerk, steward, viewer or restricted.');
      return;
    }
    this.savingRole.set(true);
    try {
      const saved = await this.api.setRole(sheet.id, user, role as Role);
      if (this.session.sheet()?.id === sheet.id) {
        this.assigned.update((list) =>
          [...list.filter((r) => r.user !== saved.user), saved].sort((a, b) => (a.user < b.user ? -1 : a.user > b.user ? 1 : 0)));
      }
    } catch {
      this.roleError.set('The role could not be assigned.');
    } finally {
      this.savingRole.set(false);
    }
  }

  protected async saveDelegation(): Promise<void> {
    if (this.savingDelegation()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.delegateError.set('');
    const { user, from, to } = this.delegateForm.getRawValue();
    const name = user.trim();
    const start = from.trim();
    const end = to.trim();
    if (name === '') {
      this.delegateError.set('Type the user name to delegate the steward role to.');
      return;
    }
    if ((start !== '' && !isDate(start)) || !isDate(end)) {
      this.delegateError.set('Type the dates as yyyy-mm-dd.');
      return;
    }
    if (end < (start === '' ? sheet.today : start)) {
      this.delegateError.set('The to-date cannot be before the from-date.');
      return;
    }
    this.savingDelegation.set(true);
    try {
      const saved = await this.api.delegate(sheet.id, name, start === '' ? null : start, end);
      if (this.session.sheet()?.id === sheet.id) this.delegations.update((list) => [...list, saved]);
    } catch {
      this.delegateError.set('Only a steward can delegate the steward role.');
    } finally {
      this.savingDelegation.set(false);
    }
  }
}
