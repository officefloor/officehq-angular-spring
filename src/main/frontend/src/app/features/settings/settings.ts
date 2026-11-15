import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { AbstractControl, FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { FISCAL_START_MONTH_PATTERN, GRADES, LATE_PCT_PATTERN, LIMIT_PATTERN, PERMISSIONS, PERMISSION_PATTERN, POLICY_DATE_PATTERN, ROUNDING_PATTERN, PermissionAction, ROLES, TERMS_PATTERN, Delegation, RoleGrant, SETTINGS_INVALID, SettingsApi, delegateError, holidayError, roleError, settingsError, voidReasonError } from './settings-api';

// The sheet's settings (its permission matrix: the roles that may open, settle, void, reassign, close or reopen, each blank for its standing rule; its clerk limit: a clerk settles only claims whose due is at or below it;
// its floor: a claim's live due is at least it; its terms: a claim created is due by its date plus them, unless its person's grade has terms of its own; its late percent: an overdue claim's due gains it for every full 30 days overdue; its rounding mode: a claim dated while one is in force rounds its live figures by it (half-up or half-even); its policy date: a claim dated with no rounding mode in force before it rounds half up, one on or after it half to even; its period numbering from-date: from it a claim opened is numbered within its period; its entry date: while set, a new claim left undated takes it; its cap: a person's due-total is at most it; its proof threshold: a claim whose due is above it cannot be settled or approved without a proof; its grace days: when its today changes, every open period ended more than them before is closed; its fiscal start month: each period shows the fiscal year its from-date falls in; its pot: each balanced pool's members cut it by their shares; its alert threshold: a change that takes a person's due-total above it alerts) with the form that saves the ones changed in it (each one carrying a figure from a from-date, and shown as in force on the sheet's today), and the role each user name holds with the form that sets one, and the steward role's delegations with the form that makes one, and the sheet's holidays (a claim's due-by, as it is created, is moved past them) with the form that adds one, and the sheet's void reasons (while there are any, a claim is voided only with one of them) with the form that adds one.
@Component({
  selector: 'app-settings',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="settings-heading">
      <h2 id="settings-heading">Settings</h2>
      <p>Clerk limit in force <strong data-testid="setting-clerk-limit-current">{{ session.clerkLimit() ?? '' }}</strong></p>
      <p>Floor in force <strong data-testid="setting-floor-current">{{ session.floor() }}</strong></p>
      <p>Terms in force (business days) <strong data-testid="setting-terms-current">{{ session.terms() }}</strong></p>
      @for (g of grades; track g) {
        <p>Terms in force for grade {{ g }} (business days) <strong [attr.data-testid]="'setting-terms-' + g + '-current'">{{ termsOf(g) }}</strong></p>
      }
      @for (g of grades; track g) {
        <p>Grace days in force for grade {{ g }} (blank: none) <strong [attr.data-testid]="'setting-grace-' + g + '-current'">{{ session.gradeGraceOn(g, session.today()) ?? '' }}</strong></p>
      }
      <p>Late percent in force <strong data-testid="setting-late-pct-current">{{ session.latePct() }}</strong></p>
      <p>Policy date in force (blank: the sheet's today) <strong data-testid="setting-policy-date-current">{{ session.policyDate() ?? '' }}</strong></p>
      <p>Period numbering from (blank: off) <strong data-testid="setting-period-numbering-from-current">{{ periodNumberingFrom() ?? '' }}</strong></p>
      <p>Entry date: new claims left undated take it (blank: off, the sheet's today) <strong data-testid="setting-entry-date-current">{{ entryDate() ?? '' }}</strong></p>
      <p>Cap in force (blank: none) <strong data-testid="setting-cap-current">{{ session.cap() ?? '' }}</strong></p>
      <p>Proof threshold in force (blank: none) <strong data-testid="setting-proof-threshold-current">{{ session.proofThreshold() ?? '' }}</strong></p>
      @for (g of grades; track g) {
        <p>Proof threshold in force for grade {{ g }} (blank: none, the sheet's) <strong [attr.data-testid]="'setting-proof-threshold-' + g + '-current'">{{ session.gradeProofThresholdOn(g, session.today()) ?? '' }}</strong></p>
      }
      <p>Rounding mode in force (blank: none, by the policy date) <strong data-testid="setting-rounding-current">{{ session.rounding() ?? '' }}</strong></p>
      <p>Note threshold in force (blank: none) <strong data-testid="setting-note-threshold-current">{{ session.noteThreshold() ?? '' }}</strong></p>
      <p>Grace days (blank: off) <strong data-testid="setting-grace-days-current">{{ graceDays() ?? '' }}</strong></p>
      <p>Pot (blank: none) <strong data-testid="setting-pot-current">{{ session.pot() ?? '' }}</strong></p>
      <p>Alert threshold (blank: off) <strong data-testid="setting-alert-threshold-current">{{ alertThreshold() ?? '' }}</strong></p>
      <p>Fiscal year start month <strong data-testid="setting-fiscal-start-month-current">{{ session.fiscalStartMonth() }}</strong></p>
      <p>Parent sheet (blank: none) <strong data-testid="setting-parent-current">{{ session.parent() ?? '' }}</strong></p>
      @for (p of permissionList; track p.action) {
        <p>Roles that may {{ p.action }} (blank: {{ p.standing }}) <strong [attr.data-testid]="'setting-perm-' + p.action + '-current'">{{ permissions()[p.action] ?? '' }}</strong></p>
      }
      <form [formGroup]="settingsForm" (ngSubmit)="saveSettings()" aria-label="Settings">
        <label for="setting-clerk-limit">Clerk limit (blank: no limit)</label>
        <input id="setting-clerk-limit" data-testid="setting-clerk-limit" formControlName="clerkLimit" inputmode="decimal" autocomplete="off" />
        <label for="setting-clerk-limit-from">Clerk limit from (blank: the sheet's today)</label>
        <input id="setting-clerk-limit-from" type="date" data-testid="setting-clerk-limit-from" formControlName="clerkLimitFrom" />
        <label for="setting-floor">Floor (blank: 1.00)</label>
        <input id="setting-floor" data-testid="setting-floor" formControlName="floor" inputmode="decimal" autocomplete="off" />
        <label for="setting-floor-from">Floor from (blank: the sheet's today)</label>
        <input id="setting-floor-from" type="date" data-testid="setting-floor-from" formControlName="floorFrom" />
        <label for="setting-terms">Terms in days (blank: 14)</label>
        <input id="setting-terms" data-testid="setting-terms" formControlName="terms" inputmode="numeric" autocomplete="off" />
        <label for="setting-terms-from">Terms from (blank: the sheet's today)</label>
        <input id="setting-terms-from" type="date" data-testid="setting-terms-from" formControlName="termsFrom" />
        @for (g of grades; track g) {
          <label [for]="'setting-terms-' + g">Terms in days for grade {{ g }} (blank: the sheet's terms)</label>
          <input [id]="'setting-terms-' + g" [attr.data-testid]="'setting-terms-' + g" [formControl]="gradeTermsForm.controls[g]" inputmode="numeric" autocomplete="off" />
          <label [for]="'setting-terms-' + g + '-from'">Terms for grade {{ g }} from (blank: the sheet's today)</label>
          <input [id]="'setting-terms-' + g + '-from'" type="date" [attr.data-testid]="'setting-terms-' + g + '-from'" [formControl]="gradeTermsFromForm.controls[g]" />
        }
        @for (g of grades; track g) {
          <label [for]="'setting-grace-' + g">Grace days for grade {{ g }}: its claims are overdue only once past their due-by plus them (blank: none)</label>
          <input [id]="'setting-grace-' + g" [attr.data-testid]="'setting-grace-' + g" [formControl]="gradeGraceForm.controls[g]" inputmode="numeric" autocomplete="off" />
          <label [for]="'setting-grace-' + g + '-from'">Grace days for grade {{ g }} from (blank: the sheet's today)</label>
          <input [id]="'setting-grace-' + g + '-from'" type="date" [attr.data-testid]="'setting-grace-' + g + '-from'" [formControl]="gradeGraceFromForm.controls[g]" />
        }
        <label for="setting-late-pct">Late percent per 30 days overdue (blank: 0.00)</label>
        <input id="setting-late-pct" data-testid="setting-late-pct" formControlName="latePct" inputmode="decimal" autocomplete="off" />
        <label for="setting-late-pct-from">Late percent from (blank: the sheet's today)</label>
        <input id="setting-late-pct-from" type="date" data-testid="setting-late-pct-from" formControlName="latePctFrom" />
        <label for="setting-policy-date">Policy date: claims dated before it round half up, others half to even (blank: the sheet's today)</label>
        <input id="setting-policy-date" type="date" data-testid="setting-policy-date" formControlName="policyDate" />
        <label for="setting-period-numbering-from">Period numbering from: claims opened from it are numbered within their period (blank: off)</label>
        <input id="setting-period-numbering-from" type="date" data-testid="setting-period-numbering-from" formControlName="periodNumberingFrom" />
        <label for="setting-entry-date">Entry date: a new claim left undated takes it instead of the sheet's today (blank: off)</label>
        <input id="setting-entry-date" type="date" data-testid="setting-entry-date" formControlName="entryDate" />
        <label for="setting-cap">Cap on a person's due-total (blank: none)</label>
        <input id="setting-cap" data-testid="setting-cap" formControlName="cap" inputmode="decimal" autocomplete="off" />
        <label for="setting-cap-from">Cap from (blank: the sheet's today)</label>
        <input id="setting-cap-from" type="date" data-testid="setting-cap-from" formControlName="capFrom" />
        <label for="setting-proof-threshold">Proof threshold: a claim whose due is above it needs a proof to be settled (blank: none)</label>
        <input id="setting-proof-threshold" data-testid="setting-proof-threshold" formControlName="proofThreshold" inputmode="decimal" autocomplete="off" />
        <label for="setting-proof-threshold-from">Proof threshold from (blank: the sheet's today)</label>
        <input id="setting-proof-threshold-from" type="date" data-testid="setting-proof-threshold-from" formControlName="proofThresholdFrom" />
        @for (g of grades; track g) {
          <label [for]="'setting-proof-threshold-' + g">Proof threshold for grade {{ g }} (blank: none, the sheet's)</label>
          <input [id]="'setting-proof-threshold-' + g" [attr.data-testid]="'setting-proof-threshold-' + g" [formControl]="gradeProofThresholdForm.controls[g]" inputmode="decimal" autocomplete="off" />
          <label [for]="'setting-proof-threshold-' + g + '-from'">Proof threshold for grade {{ g }} from (blank: the sheet's today)</label>
          <input [id]="'setting-proof-threshold-' + g + '-from'" type="date" [attr.data-testid]="'setting-proof-threshold-' + g + '-from'" [formControl]="gradeProofThresholdFromForm.controls[g]" />
        }
        <label for="setting-rounding">Rounding mode: half-up or half-even, a claim dated while it is in force rounds by it (blank: none, by the policy date)</label>
        <input id="setting-rounding" data-testid="setting-rounding" formControlName="rounding" autocomplete="off" />
        <label for="setting-rounding-from">Rounding mode from (blank: the sheet's today)</label>
        <input id="setting-rounding-from" type="date" data-testid="setting-rounding-from" formControlName="roundingFrom" />
        <label for="setting-note-threshold">Note threshold: a claim whose due is above it needs a note to be opened (blank: none)</label>
        <input id="setting-note-threshold" data-testid="setting-note-threshold" formControlName="noteThreshold" inputmode="decimal" autocomplete="off" />
        <label for="setting-note-threshold-from">Note threshold from (blank: the sheet's today)</label>
        <input id="setting-note-threshold-from" type="date" data-testid="setting-note-threshold-from" formControlName="noteThresholdFrom" />
        <label for="setting-grace-days">Grace days: when the sheet's today changes, open periods ended more than this many days before it are closed (blank: off)</label>
        <input id="setting-grace-days" data-testid="setting-grace-days" formControlName="graceDays" inputmode="numeric" autocomplete="off" />
        <label for="setting-pot">Pot: each balanced pool's members cut it by their shares (blank: none)</label>
        <input id="setting-pot" data-testid="setting-pot" formControlName="pot" inputmode="decimal" autocomplete="off" />
        <label for="setting-alert-threshold">Alert threshold: a change that takes a person's due-total above it alerts (blank: off)</label>
        <input id="setting-alert-threshold" data-testid="setting-alert-threshold" formControlName="alertThreshold" inputmode="decimal" autocomplete="off" />
        <label for="setting-fiscal-start-month">Fiscal year start month: 1 to 12</label>
        <input id="setting-fiscal-start-month" data-testid="setting-fiscal-start-month" formControlName="fiscalStartMonth" inputmode="numeric" autocomplete="off" />
        <label for="setting-parent">Parent sheet id: a grade with no rate of its own takes the parent's (blank: none)</label>
        <input id="setting-parent" data-testid="setting-parent" formControlName="parent" autocomplete="off" />
        @for (p of permissionList; track p.action) {
          <label [for]="'setting-perm-' + p.action">Roles that may {{ p.action }}, comma separated (blank: {{ p.standing }})</label>
          <input [id]="'setting-perm-' + p.action" [attr.data-testid]="'setting-perm-' + p.action" [formControl]="permissionsForm.controls[p.action]" autocomplete="off" />
        }
        <button type="submit" data-testid="settings-save">Save settings</button>
        @if (settingsErr(); as err) {
          <p role="alert" data-testid="settings-error">{{ err }}</p>
        }
      </form>
      <form [formGroup]="roleForm" (ngSubmit)="saveRole()" aria-label="Set role">
        <label for="role-form-user">User</label>
        <input id="role-form-user" data-testid="role-form-user" formControlName="user" autocomplete="off" />
        <label for="role-form-role">Role ({{ roleNames }})</label>
        <input id="role-form-role" data-testid="role-form-role" formControlName="role" list="role-options" autocomplete="off" />
        <datalist id="role-options">
          @for (r of roles; track r) {
            <option [value]="r"></option>
          }
        </datalist>
        <button type="submit" data-testid="role-form-submit">Set role</button>
        @if (roleErr(); as err) {
          <p role="alert" data-testid="role-form-error">{{ err }}</p>
        }
      </form>
      <table data-testid="roles-list">
        <caption>Roles (anyone else is a clerk)</caption>
        <thead>
          <tr><th scope="col">User</th><th scope="col">Role</th></tr>
        </thead>
        <tbody>
          @for (g of sortedGrants(); track g.user) {
            <tr [attr.data-testid]="'role-row-' + g.user">
              <th scope="row" data-testid="role-user">{{ g.user }}</th>
              <td data-testid="role-value">{{ g.role }}</td>
            </tr>
          }
        </tbody>
      </table>
      <form [formGroup]="delegateForm" (ngSubmit)="delegate()" aria-label="Delegate the steward role">
        <label for="delegate-user">Delegate the steward role to</label>
        <input id="delegate-user" data-testid="delegate-user" formControlName="user" autocomplete="off" />
        <label for="delegate-from">From (yyyy-mm-dd, blank: the sheet's today)</label>
        <input id="delegate-from" data-testid="delegate-from" formControlName="from" autocomplete="off" />
        <label for="delegate-to">To (yyyy-mm-dd, inclusive, blank: the from-date)</label>
        <input id="delegate-to" data-testid="delegate-to" formControlName="to" autocomplete="off" />
        <button type="submit" data-testid="delegate-submit">Delegate</button>
        @if (delegateErr(); as err) {
          <p role="alert" data-testid="delegate-error">{{ err }}</p>
        }
      </form>
      <ul aria-label="Delegations of the steward role" data-testid="delegations-list">
        @for (d of delegations(); track d.id) {
          <li data-testid="delegate-line">{{ d.steward }} to {{ d.user }}, {{ d.from }} to {{ d.to }}</li>
        }
      </ul>
      <form [formGroup]="holidayForm" (ngSubmit)="addHoliday()" aria-label="Add a holiday">
        <label for="holiday-date">Holiday (yyyy-mm-dd)</label>
        <input id="holiday-date" data-testid="holiday-date" formControlName="date" autocomplete="off" />
        <button type="submit" data-testid="holiday-add">Add holiday</button>
        @if (holidayErr(); as err) {
          <p role="alert" data-testid="holiday-error">{{ err }}</p>
        }
      </form>
      <ul aria-label="Holidays" data-testid="holidays-list">
        @for (h of holidays(); track h) {
          <li [attr.data-testid]="'holiday-row-' + h">{{ h }}</li>
        }
      </ul>
      <form [formGroup]="voidReasonForm" (ngSubmit)="addVoidReason()" aria-label="Add a void reason">
        <label for="void-reason-text">Void reason</label>
        <input id="void-reason-text" data-testid="void-reason-text" formControlName="reason" autocomplete="off" />
        <button type="submit" data-testid="void-reason-add">Add void reason</button>
        @if (voidReasonErr(); as err) {
          <p role="alert" data-testid="void-reason-error">{{ err }}</p>
        }
      </form>
      <ul aria-label="Void reasons" data-testid="void-reasons-list">
        @for (r of session.voidReasons(); track r) {
          <li [attr.data-testid]="'void-reason-row-' + r">{{ r }}</li>
        }
      </ul>
    </section>
  `,
})
export class Settings {
  protected readonly session = inject(Session);
  private readonly api = inject(SettingsApi);

  protected readonly roles = ROLES;
  protected readonly roleNames = ROLES.join(' or ');
  /** The sheet's period numbering from-date (yyyy-mm-dd); null when blank (off). */
  protected readonly periodNumberingFrom = signal<string | null>(null);
  /** The sheet's entry date (yyyy-mm-dd); null when blank (off). */
  protected readonly entryDate = signal<string | null>(null);
  /** The sheet's grace days; null when blank (off). */
  protected readonly graceDays = signal<number | null>(null);
  /** The sheet's alert threshold (two decimals); null when blank (off). */
  protected readonly alertThreshold = signal<string | null>(null);
  protected readonly grades = GRADES;
  protected readonly permissionList = PERMISSIONS;
  /** The roles set for each action, as saved; an action not here keeps its standing rule. */
  protected readonly permissions = signal<Partial<Record<PermissionAction, string>>>({});
  /** The roles set on the sheet. */
  protected readonly grants = signal<RoleGrant[]>([]);
  protected readonly sortedGrants = computed(() => [...this.grants()].sort((a, b) => a.user.localeCompare(b.user)));
  protected readonly settingsErr = signal<string | null>(null);
  protected readonly roleErr = signal<string | null>(null);
  /** The steward role's delegations on the sheet, in the order they were made. */
  protected readonly delegations = signal<Delegation[]>([]);
  protected readonly delegateErr = signal<string | null>(null);
  private delegating = false;
  /** The sheet's holidays (yyyy-mm-dd), earliest first. */
  protected readonly holidays = signal<string[]>([]);
  protected readonly holidayErr = signal<string | null>(null);
  private addingHoliday = false;
  protected readonly voidReasonErr = signal<string | null>(null);
  private addingVoidReason = false;
  /** Whether a save is waiting for its answer; a second one meanwhile runs once it is answered. */
  private savingSettings = false;
  /** Whether a save was submitted while another was waiting; it runs once that one is answered. */
  private resaveSettings = false;
  private savingRole = false;
  /** Guards against a slower, older read landing over a newer one. */
  private readSeq = 0;

  protected readonly settingsForm = new FormGroup({
    clerkLimit: new FormControl('', { nonNullable: true }),
    clerkLimitFrom: new FormControl('', { nonNullable: true }),
    floor: new FormControl('', { nonNullable: true }),
    floorFrom: new FormControl('', { nonNullable: true }),
    terms: new FormControl('', { nonNullable: true }),
    termsFrom: new FormControl('', { nonNullable: true }),
    latePct: new FormControl('', { nonNullable: true }),
    latePctFrom: new FormControl('', { nonNullable: true }),
    policyDate: new FormControl('', { nonNullable: true }),
    periodNumberingFrom: new FormControl('', { nonNullable: true }),
    entryDate: new FormControl('', { nonNullable: true }),
    cap: new FormControl('', { nonNullable: true }),
    capFrom: new FormControl('', { nonNullable: true }),
    proofThreshold: new FormControl('', { nonNullable: true }),
    proofThresholdFrom: new FormControl('', { nonNullable: true }),
    rounding: new FormControl('', { nonNullable: true }),
    roundingFrom: new FormControl('', { nonNullable: true }),
    noteThreshold: new FormControl('', { nonNullable: true }),
    noteThresholdFrom: new FormControl('', { nonNullable: true }),
    graceDays: new FormControl('', { nonNullable: true }),
    pot: new FormControl('', { nonNullable: true }),
    alertThreshold: new FormControl('', { nonNullable: true }),
    fiscalStartMonth: new FormControl('', { nonNullable: true }),
    parent: new FormControl('', { nonNullable: true }),
  });
  /** A grade's own terms, typed in beside the other settings and saved with them. */
  protected readonly gradeTermsForm = new FormGroup({
    A: new FormControl('', { nonNullable: true }),
    B: new FormControl('', { nonNullable: true }),
    C: new FormControl('', { nonNullable: true }),
  });
  /** The from-date of a grade's own terms, typed in beside them. */
  protected readonly gradeTermsFromForm = new FormGroup({
    A: new FormControl('', { nonNullable: true }),
    B: new FormControl('', { nonNullable: true }),
    C: new FormControl('', { nonNullable: true }),
  });
  /** A grade's grace days, typed in beside the other settings and saved with them. */
  protected readonly gradeGraceForm = new FormGroup({
    A: new FormControl('', { nonNullable: true }),
    B: new FormControl('', { nonNullable: true }),
    C: new FormControl('', { nonNullable: true }),
  });
  /** The from-date of a grade's grace days, typed in beside them. */
  protected readonly gradeGraceFromForm = new FormGroup({
    A: new FormControl('', { nonNullable: true }),
    B: new FormControl('', { nonNullable: true }),
    C: new FormControl('', { nonNullable: true }),
  });
  /** A grade's proof threshold, typed in beside the other settings and saved with them. */
  protected readonly gradeProofThresholdForm = new FormGroup({
    A: new FormControl('', { nonNullable: true }),
    B: new FormControl('', { nonNullable: true }),
    C: new FormControl('', { nonNullable: true }),
  });
  /** The from-date of a grade's proof threshold, typed in beside it. */
  protected readonly gradeProofThresholdFromForm = new FormGroup({
    A: new FormControl('', { nonNullable: true }),
    B: new FormControl('', { nonNullable: true }),
    C: new FormControl('', { nonNullable: true }),
  });
  /** The roles that may take each action, typed in beside the other settings and saved with them. */
  protected readonly permissionsForm = new FormGroup({
    open: new FormControl('', { nonNullable: true }),
    settle: new FormControl('', { nonNullable: true }),
    void: new FormControl('', { nonNullable: true }),
    reassign: new FormControl('', { nonNullable: true }),
    close: new FormControl('', { nonNullable: true }),
    reopen: new FormControl('', { nonNullable: true }),
  });
  protected readonly roleForm = new FormGroup({
    user: new FormControl('', { nonNullable: true }),
    role: new FormControl('', { nonNullable: true }),
  });

  protected readonly delegateForm = new FormGroup({
    user: new FormControl('', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
  });

  protected readonly holidayForm = new FormGroup({
    date: new FormControl('', { nonNullable: true }),
  });

  protected readonly voidReasonForm = new FormGroup({
    reason: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.settingsForm.valueChanges.subscribe(() => this.settingsErr.set(null));
    this.gradeTermsForm.valueChanges.subscribe(() => this.settingsErr.set(null));
    this.gradeTermsFromForm.valueChanges.subscribe(() => this.settingsErr.set(null));
    this.gradeGraceForm.valueChanges.subscribe(() => this.settingsErr.set(null));
    this.gradeGraceFromForm.valueChanges.subscribe(() => this.settingsErr.set(null));
    this.gradeProofThresholdForm.valueChanges.subscribe(() => this.settingsErr.set(null));
    this.gradeProofThresholdFromForm.valueChanges.subscribe(() => this.settingsErr.set(null));
    this.permissionsForm.valueChanges.subscribe(() => this.settingsErr.set(null));
    this.roleForm.valueChanges.subscribe(() => this.roleErr.set(null));
    this.delegateForm.valueChanges.subscribe(() => this.delegateErr.set(null));
    this.holidayForm.valueChanges.subscribe(() => this.holidayErr.set(null));
    this.voidReasonForm.valueChanges.subscribe(() => this.voidReasonErr.set(null));
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.periodNumberingFrom.set(null);
        this.entryDate.set(null);
        this.graceDays.set(null);
        this.alertThreshold.set(null);
        this.session.pot.set(null);
        this.session.fiscalStartMonth.set(1);
        this.grants.set([]);
        this.permissions.set({});
        this.settingsErr.set(null);
        this.roleErr.set(null);
        this.delegations.set([]);
        this.delegateErr.set(null);
        this.delegateForm.reset({ user: '', from: '', to: '' }, { emitEvent: false });
        this.holidays.set([]);
        this.holidayErr.set(null);
        this.holidayForm.reset({ date: '' }, { emitEvent: false });
        this.session.voidReasons.set([]);
        this.voidReasonErr.set(null);
        this.voidReasonForm.reset({ reason: '' }, { emitEvent: false });
        this.settingsForm.reset({
          clerkLimit: '', clerkLimitFrom: '', floor: '', floorFrom: '', terms: '', termsFrom: '', latePct: '', latePctFrom: '',
          policyDate: '', periodNumberingFrom: '', entryDate: '', cap: '', capFrom: '', proofThreshold: '', proofThresholdFrom: '', rounding: '', roundingFrom: '', noteThreshold: '', noteThresholdFrom: '', graceDays: '', pot: '', alertThreshold: '', fiscalStartMonth: '', parent: '',
        }, { emitEvent: false });
        this.gradeTermsForm.reset({ A: '', B: '', C: '' }, { emitEvent: false });
        this.gradeTermsFromForm.reset({ A: '', B: '', C: '' }, { emitEvent: false });
        this.gradeGraceForm.reset({ A: '', B: '', C: '' }, { emitEvent: false });
        this.gradeGraceFromForm.reset({ A: '', B: '', C: '' }, { emitEvent: false });
        this.gradeProofThresholdForm.reset({ A: '', B: '', C: '' }, { emitEvent: false });
        this.gradeProofThresholdFromForm.reset({ A: '', B: '', C: '' }, { emitEvent: false });
        this.permissionsForm.reset({ open: '', settle: '', void: '', reassign: '', close: '', reopen: '' }, { emitEvent: false });
        this.roleForm.reset({ user: '', role: '' }, { emitEvent: false });
        if (id) void this.read(id);
      });
    });
  }

  /** The terms in force for a grade on the sheet's today: its own, else the sheet's. */
  protected termsOf(grade: string): number {
    return this.session.gradeTermsOn(grade, this.session.today()) ?? this.session.terms();
  }

  protected async saveSettings(): Promise<void> {
    if (this.savingSettings) {
      this.resaveSettings = true;
      return;
    }
    this.settingsErr.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const { clerkLimit, clerkLimitFrom, floor, floorFrom, terms, termsFrom, latePct, latePctFrom, policyDate, periodNumberingFrom, entryDate, cap, capFrom, proofThreshold, proofThresholdFrom, rounding, roundingFrom, noteThreshold, noteThresholdFrom, graceDays, pot, alertThreshold, fiscalStartMonth, parent } = this.settingsForm.controls;
    // only the settings typed into (or entered and left, as when one already blank is cleared),
    // or whose from-date was, since the last save are saved (the clerk limit when none is)
    const typed = (c: AbstractControl) => c.dirty || c.touched;
    const saveFloor = typed(floor) || typed(floorFrom);
    const saveTerms = typed(terms) || typed(termsFrom);
    const saveLatePct = typed(latePct) || typed(latePctFrom);
    const savePolicyDate = typed(policyDate);
    const savePeriodNumberingFrom = typed(periodNumberingFrom);
    const saveEntryDate = typed(entryDate);
    const saveCap = typed(cap) || typed(capFrom);
    const saveProofThreshold = typed(proofThreshold) || typed(proofThresholdFrom);
    const saveRounding = typed(rounding) || typed(roundingFrom);
    const saveNoteThreshold = typed(noteThreshold) || typed(noteThresholdFrom);
    const saveGraceDays = typed(graceDays);
    const savePot = typed(pot);
    const saveAlertThreshold = typed(alertThreshold);
    const saveFiscalStartMonth = typed(fiscalStartMonth);
    const saveParent = typed(parent);
    const gradeTermsToSave = GRADES
      .filter((g) => typed(this.gradeTermsForm.controls[g]) || typed(this.gradeTermsFromForm.controls[g]))
      .map((g) => ({
        grade: g, control: this.gradeTermsForm.controls[g], value: this.gradeTermsForm.controls[g].value.trim(),
        fromControl: this.gradeTermsFromForm.controls[g], from: this.gradeTermsFromForm.controls[g].value.trim(),
      }));
    const gradeGraceToSave = GRADES
      .filter((g) => typed(this.gradeGraceForm.controls[g]) || typed(this.gradeGraceFromForm.controls[g]))
      .map((g) => ({
        grade: g, control: this.gradeGraceForm.controls[g], value: this.gradeGraceForm.controls[g].value.trim(),
        fromControl: this.gradeGraceFromForm.controls[g], from: this.gradeGraceFromForm.controls[g].value.trim(),
      }));
    const gradeProofThresholdToSave = GRADES
      .filter((g) => typed(this.gradeProofThresholdForm.controls[g]) || typed(this.gradeProofThresholdFromForm.controls[g]))
      .map((g) => ({
        grade: g, control: this.gradeProofThresholdForm.controls[g], value: this.gradeProofThresholdForm.controls[g].value.trim(),
        fromControl: this.gradeProofThresholdFromForm.controls[g], from: this.gradeProofThresholdFromForm.controls[g].value.trim(),
      }));
    const permissionsToSave = PERMISSIONS
      .filter((p) => typed(this.permissionsForm.controls[p.action]))
      .map((p) => ({
        action: p.action, control: this.permissionsForm.controls[p.action],
        value: this.permissionsForm.controls[p.action].value.split(',').map((r) => r.trim()).filter((r) => r !== '').join(','),
        typed: this.permissionsForm.controls[p.action].value.trim(),
      }));
    const saveLimit = typed(clerkLimit) || typed(clerkLimitFrom)
      || !(saveFloor || saveTerms || gradeTermsToSave.length > 0 || gradeGraceToSave.length > 0 || gradeProofThresholdToSave.length > 0 || permissionsToSave.length > 0 || saveLatePct || savePolicyDate || savePeriodNumberingFrom || saveEntryDate || saveCap || saveProofThreshold || saveRounding || saveNoteThreshold || saveGraceDays || savePot || saveAlertThreshold || saveFiscalStartMonth || saveParent);
    const limitFrom = clerkLimitFrom.value.trim();
    const floorFromValue = floorFrom.value.trim();
    const termsFromValue = termsFrom.value.trim();
    const latePctFromValue = latePctFrom.value.trim();
    const capFromValue = capFrom.value.trim();
    const proofThresholdFromValue = proofThresholdFrom.value.trim();
    const roundingFromValue = roundingFrom.value.trim();
    const noteThresholdFromValue = noteThresholdFrom.value.trim();
    const badFrom = (save: boolean, from: string) => save && from !== '' && !POLICY_DATE_PATTERN.test(from);
    const limitValue = clerkLimit.value.trim();
    const floorValue = floor.value.trim();
    const termsValue = terms.value.trim();
    const latePctValue = latePct.value.trim();
    const policyDateValue = policyDate.value.trim();
    const periodNumberingFromValue = periodNumberingFrom.value.trim();
    const entryDateValue = entryDate.value.trim();
    const capValue = cap.value.trim();
    const proofThresholdValue = proofThreshold.value.trim();
    const roundingValue = rounding.value.trim();
    const noteThresholdValue = noteThreshold.value.trim();
    const graceDaysValue = graceDays.value.trim();
    const potValue = pot.value.trim();
    const alertThresholdValue = alertThreshold.value.trim();
    const fiscalStartMonthValue = fiscalStartMonth.value.trim();
    const parentValue = parent.value.trim();
    if ((saveLimit && limitValue !== '' && !LIMIT_PATTERN.test(limitValue))
        || (saveFloor && floorValue !== '' && !LIMIT_PATTERN.test(floorValue))
        || (saveTerms && termsValue !== '' && !TERMS_PATTERN.test(termsValue))
        || gradeTermsToSave.some((t) => t.value !== '' && !TERMS_PATTERN.test(t.value))
        || gradeGraceToSave.some((t) => t.value !== '' && !TERMS_PATTERN.test(t.value))
        || gradeProofThresholdToSave.some((t) => t.value !== '' && !LIMIT_PATTERN.test(t.value))
        || (saveLatePct && latePctValue !== '' && !LATE_PCT_PATTERN.test(latePctValue))
        || (savePolicyDate && policyDateValue !== '' && !POLICY_DATE_PATTERN.test(policyDateValue))
        || (savePeriodNumberingFrom && periodNumberingFromValue !== '' && !POLICY_DATE_PATTERN.test(periodNumberingFromValue))
        || (saveEntryDate && entryDateValue !== '' && !POLICY_DATE_PATTERN.test(entryDateValue))
        || (saveCap && capValue !== '' && !LIMIT_PATTERN.test(capValue))
        || (saveProofThreshold && proofThresholdValue !== '' && !LIMIT_PATTERN.test(proofThresholdValue))
        || (saveRounding && roundingValue !== '' && !ROUNDING_PATTERN.test(roundingValue))
        || (saveNoteThreshold && noteThresholdValue !== '' && !LIMIT_PATTERN.test(noteThresholdValue))
        || (saveGraceDays && graceDaysValue !== '' && !TERMS_PATTERN.test(graceDaysValue))
        || (savePot && potValue !== '' && !LIMIT_PATTERN.test(potValue))
        || (saveAlertThreshold && alertThresholdValue !== '' && !LIMIT_PATTERN.test(alertThresholdValue))
        || (saveFiscalStartMonth && !FISCAL_START_MONTH_PATTERN.test(fiscalStartMonthValue))
        || (saveParent && !/^\S*$/.test(parentValue))
        || badFrom(saveLimit, limitFrom) || badFrom(saveFloor, floorFromValue) || badFrom(saveTerms, termsFromValue)
        || badFrom(saveLatePct, latePctFromValue) || badFrom(saveCap, capFromValue)
        || badFrom(saveProofThreshold, proofThresholdFromValue) || badFrom(saveNoteThreshold, noteThresholdFromValue)
        || badFrom(saveRounding, roundingFromValue)
        || gradeTermsToSave.some((t) => badFrom(true, t.from))
        || gradeGraceToSave.some((t) => badFrom(true, t.from))
        || gradeProofThresholdToSave.some((t) => badFrom(true, t.from))
        || permissionsToSave.some((p) => p.typed !== '' && !PERMISSION_PATTERN.test(p.typed))) {
      this.settingsErr.set(SETTINGS_INVALID);
      return;
    }
    this.savingSettings = true;
    try {
      if (saveLimit) {
        const saved = await this.api.setClerkLimit(sheetId, limitValue, limitFrom);
        settled(clerkLimit, limitValue);
        settled(clerkLimitFrom, limitFrom);
        if (this.session.sheetId() === sheetId) this.session.dated.set(saved.dated);
      }
      if (saveFloor) {
        const saved = await this.api.setFloor(sheetId, floorValue, floorFromValue);
        settled(floor, floorValue);
        settled(floorFrom, floorFromValue);
        if (this.session.sheetId() === sheetId) this.session.dated.set(saved.dated);
      }
      if (saveTerms) {
        const saved = await this.api.setTerms(sheetId, termsValue, termsFromValue);
        settled(terms, termsValue);
        settled(termsFrom, termsFromValue);
        if (this.session.sheetId() === sheetId) this.session.dated.set(saved.dated);
      }
      for (const t of gradeTermsToSave) {
        const saved = await this.api.setGradeTerms(sheetId, t.grade, t.value, t.from);
        settled(t.control, t.value);
        settled(t.fromControl, t.from);
        if (this.session.sheetId() === sheetId) this.session.dated.set(saved.dated);
      }
      for (const t of gradeGraceToSave) {
        const saved = await this.api.setGradeGrace(sheetId, t.grade, t.value, t.from);
        settled(t.control, t.value);
        settled(t.fromControl, t.from);
        if (this.session.sheetId() === sheetId) this.session.dated.set(saved.dated);
      }
      if (saveLatePct) {
        const saved = await this.api.setLatePct(sheetId, latePctValue, latePctFromValue);
        settled(latePct, latePctValue);
        settled(latePctFrom, latePctFromValue);
        if (this.session.sheetId() === sheetId) this.session.dated.set(saved.dated);
      }
      if (savePolicyDate) {
        const saved = await this.api.setPolicyDate(sheetId, policyDateValue);
        if (policyDate.value.trim() === policyDateValue) { policyDate.markAsPristine(); policyDate.markAsUntouched(); }
        if (this.session.sheetId() === sheetId) this.session.policyDate.set(saved.policyDate);
      }
      if (savePeriodNumberingFrom) {
        const saved = await this.api.setPeriodNumberingFrom(sheetId, periodNumberingFromValue);
        if (periodNumberingFrom.value.trim() === periodNumberingFromValue) { periodNumberingFrom.markAsPristine(); periodNumberingFrom.markAsUntouched(); }
        if (this.session.sheetId() === sheetId) this.periodNumberingFrom.set(saved.periodNumberingFrom);
      }
      if (saveEntryDate) {
        const saved = await this.api.setEntryDate(sheetId, entryDateValue);
        if (entryDate.value.trim() === entryDateValue) { entryDate.markAsPristine(); entryDate.markAsUntouched(); }
        if (this.session.sheetId() === sheetId) this.entryDate.set(saved.entryDate);
      }
      if (saveCap) {
        const saved = await this.api.setCap(sheetId, capValue, capFromValue);
        settled(cap, capValue);
        settled(capFrom, capFromValue);
        if (this.session.sheetId() === sheetId) this.session.dated.set(saved.dated);
      }
      if (saveProofThreshold) {
        const saved = await this.api.setProofThreshold(sheetId, proofThresholdValue, proofThresholdFromValue);
        settled(proofThreshold, proofThresholdValue);
        settled(proofThresholdFrom, proofThresholdFromValue);
        if (this.session.sheetId() === sheetId) this.session.dated.set(saved.dated);
      }
      for (const t of gradeProofThresholdToSave) {
        const saved = await this.api.setGradeProofThreshold(sheetId, t.grade, t.value, t.from);
        settled(t.control, t.value);
        settled(t.fromControl, t.from);
        if (this.session.sheetId() === sheetId) this.session.dated.set(saved.dated);
      }
      if (saveRounding) {
        const saved = await this.api.setRounding(sheetId, roundingValue, roundingFromValue);
        settled(rounding, roundingValue);
        settled(roundingFrom, roundingFromValue);
        if (this.session.sheetId() === sheetId) this.session.dated.set(saved.dated);
      }
      if (saveNoteThreshold) {
        const saved = await this.api.setNoteThreshold(sheetId, noteThresholdValue, noteThresholdFromValue);
        settled(noteThreshold, noteThresholdValue);
        settled(noteThresholdFrom, noteThresholdFromValue);
        if (this.session.sheetId() === sheetId) this.session.dated.set(saved.dated);
      }
      if (saveGraceDays) {
        const saved = await this.api.setGraceDays(sheetId, graceDaysValue);
        settled(graceDays, graceDaysValue);
        if (this.session.sheetId() === sheetId) this.graceDays.set(saved.graceDays);
      }
      if (savePot) {
        const saved = await this.api.setPot(sheetId, potValue);
        if (pot.value.trim() === potValue) { pot.markAsPristine(); pot.markAsUntouched(); }
        if (this.session.sheetId() === sheetId) this.session.pot.set(saved.pot);
      }
      if (saveAlertThreshold) {
        const saved = await this.api.setAlertThreshold(sheetId, alertThresholdValue);
        if (alertThreshold.value.trim() === alertThresholdValue) { alertThreshold.markAsPristine(); alertThreshold.markAsUntouched(); }
        if (this.session.sheetId() === sheetId) this.alertThreshold.set(saved.alertThreshold);
      }
      if (saveFiscalStartMonth) {
        const saved = await this.api.setFiscalStartMonth(sheetId, fiscalStartMonthValue);
        settled(fiscalStartMonth, fiscalStartMonthValue);
        if (this.session.sheetId() === sheetId) this.session.fiscalStartMonth.set(saved.fiscalStartMonth);
      }
      if (saveParent) {
        const saved = await this.api.setParent(sheetId, parentValue);
        settled(parent, parentValue);
        if (this.session.sheetId() === sheetId) this.session.parent.set(saved.parent);
      }
      for (const p of permissionsToSave) {
        const saved = await this.api.setPermission(sheetId, p.action, p.value);
        if (p.control.value.trim() === p.typed) { p.control.markAsPristine(); p.control.markAsUntouched(); }
        if (this.session.sheetId() === sheetId) this.permissions.set(saved.permissions);
      }
    } catch (e) {
      this.settingsErr.set(settingsError(e));
    } finally {
      this.savingSettings = false;
    }
    if (this.resaveSettings) {
      this.resaveSettings = false;
      if (this.settingsForm.dirty || this.gradeTermsForm.dirty || this.gradeTermsFromForm.dirty || this.gradeGraceForm.dirty || this.gradeGraceFromForm.dirty || this.gradeProofThresholdForm.dirty || this.gradeProofThresholdFromForm.dirty || this.permissionsForm.dirty) await this.saveSettings();
    }
  }

  protected async saveRole(): Promise<void> {
    if (this.savingRole) return;
    this.roleErr.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const user = this.roleForm.controls.user.value.trim();
    const role = this.roleForm.controls.role.value.trim();
    if (!/^\S+$/.test(user)) {
      this.roleErr.set('Enter a user name without spaces.');
      return;
    }
    if (!(ROLES as readonly string[]).includes(role)) {
      this.roleErr.set(`Choose a role: ${this.roleNames}.`);
      return;
    }
    this.savingRole = true;
    try {
      const grant = await this.api.setRole(sheetId, user, role);
      if (this.session.sheetId() === sheetId) {
        this.grants.update((gs) => [...gs.filter((g) => g.user !== grant.user), grant]);
      }
      this.roleForm.reset({ user: '', role: '' }, { emitEvent: false });
    } catch (e) {
      this.roleErr.set(roleError(e));
    } finally {
      this.savingRole = false;
    }
  }

  protected async delegate(): Promise<void> {
    if (this.delegating) return;
    this.delegateErr.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const { user, from, to } = this.delegateForm.getRawValue();
    if (!/^\S+$/.test(user.trim())) {
      this.delegateErr.set('Enter a user name without spaces.');
      return;
    }
    this.delegating = true;
    try {
      const made = await this.api.delegate(sheetId, user.trim(), from.trim(), to.trim());
      if (this.session.sheetId() === sheetId) {
        this.delegations.update((ds) => [...ds.filter((d) => d.id !== made.id), made]);
      }
      this.delegateForm.reset({ user: '', from: '', to: '' }, { emitEvent: false });
    } catch (e) {
      this.delegateErr.set(delegateError(e));
    } finally {
      this.delegating = false;
    }
  }

  protected async addHoliday(): Promise<void> {
    if (this.addingHoliday) return;
    this.holidayErr.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const date = this.holidayForm.getRawValue().date.trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) {
      this.holidayErr.set('Enter a holiday as yyyy-mm-dd.');
      return;
    }
    this.addingHoliday = true;
    try {
      const added = await this.api.addHoliday(sheetId, date);
      if (this.session.sheetId() === sheetId) {
        this.holidays.update((hs) => [...hs.filter((h) => h !== added), added].sort());
      }
      this.holidayForm.reset({ date: '' }, { emitEvent: false });
    } catch (e) {
      this.holidayErr.set(holidayError(e));
    } finally {
      this.addingHoliday = false;
    }
  }

  protected async addVoidReason(): Promise<void> {
    if (this.addingVoidReason) return;
    this.voidReasonErr.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const reason = this.voidReasonForm.getRawValue().reason.trim();
    if (reason === '') {
      this.voidReasonErr.set('Enter a void reason.');
      return;
    }
    if (this.session.voidReasons().includes(reason)) {
      this.voidReasonErr.set('That reason is on the list already.');
      return;
    }
    this.addingVoidReason = true;
    try {
      const reasons = await this.api.addVoidReason(sheetId, reason);
      if (this.session.sheetId() === sheetId) this.session.voidReasons.set(reasons);
      this.voidReasonForm.reset({ reason: '' }, { emitEvent: false });
    } catch (e) {
      this.voidReasonErr.set(voidReasonError(e));
    } finally {
      this.addingVoidReason = false;
    }
  }

  private async read(sheetId: string): Promise<void> {
    const seq = ++this.readSeq;
    try {
      const [settings, grants, delegations, holidays, voidReasons] = await Promise.all([
        this.api.get(sheetId), this.api.roles(sheetId), this.api.delegations(sheetId), this.api.holidays(sheetId),
        this.api.voidReasons(sheetId),
      ]);
      if (seq === this.readSeq) {
        this.session.dated.set(settings.dated);
        this.session.policyDate.set(settings.policyDate);
        this.periodNumberingFrom.set(settings.periodNumberingFrom);
        this.entryDate.set(settings.entryDate);
        this.graceDays.set(settings.graceDays);
        this.session.pot.set(settings.pot);
        this.alertThreshold.set(settings.alertThreshold);
        this.session.fiscalStartMonth.set(settings.fiscalStartMonth);
        // the fiscal start month's field starts at the month in force
        const fiscalStartMonth = this.settingsForm.controls.fiscalStartMonth;
        if (!fiscalStartMonth.dirty) fiscalStartMonth.setValue(String(settings.fiscalStartMonth), { emitEvent: false });
        // the pot's field starts at the pot, so clearing it is a change that saves "none"
        const pot = this.settingsForm.controls.pot;
        if (!pot.dirty) pot.setValue(settings.pot ?? '', { emitEvent: false });
        // and the alert threshold's
        const alertThreshold = this.settingsForm.controls.alertThreshold;
        if (!alertThreshold.dirty) alertThreshold.setValue(settings.alertThreshold ?? '', { emitEvent: false });
        // the cap's field starts at the cap in force, so clearing it is a change that saves "none"
        const cap = this.settingsForm.controls.cap;
        if (!cap.dirty) cap.setValue(settings.cap ?? '', { emitEvent: false });
        // so does the proof threshold's
        const proofThreshold = this.settingsForm.controls.proofThreshold;
        if (!proofThreshold.dirty) proofThreshold.setValue(settings.proofThreshold ?? '', { emitEvent: false });
        // and the rounding mode's
        const rounding = this.settingsForm.controls.rounding;
        if (!rounding.dirty) rounding.setValue(this.session.rounding() ?? '', { emitEvent: false });
        // and the note threshold's
        const noteThreshold = this.settingsForm.controls.noteThreshold;
        if (!noteThreshold.dirty) noteThreshold.setValue(this.session.noteThreshold() ?? '', { emitEvent: false });
        this.permissions.set(settings.permissions);
        // each action's roles start at those set, so clearing them is a change that saves the standing rule
        for (const p of PERMISSIONS) {
          const control = this.permissionsForm.controls[p.action];
          if (!control.dirty) control.setValue(settings.permissions[p.action] ?? '', { emitEvent: false });
        }
        this.grants.set(grants);
        this.delegations.set(delegations);
        this.holidays.set(holidays);
        this.session.voidReasons.set(voidReasons);
      }
    } catch {
      if (seq === this.readSeq) this.settingsErr.set('Something went wrong loading settings; try again.');
    }
  }
}

/** Mark a field saved, unless it was typed into again while the save was waiting. */
function settled(control: AbstractControl<string>, saved: string): void {
  if (control.value.trim() === saved) {
    control.markAsPristine();
    control.markAsUntouched();
  }
}
