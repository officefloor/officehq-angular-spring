import { Component, effect, inject, signal } from '@angular/core';
import { AbstractControl, FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Pools } from '../../core/pools';
import { Session } from '../../core/session';
import { Grade } from '../../core/people';
import {
  LATE_PCT, LIMIT, PERM_ACTIONS, PERM_ROLES, POLICY_DATE, PermAction, ROLES, ROUNDING, STANDING_PERMISSIONS, TERMS, FISCAL_MONTH, Role, RoleName,
  SheetSettings,
} from '../../core/settings';

// The sheet's settings (the clerk limit, the cap, the proof threshold, the proof threshold per grade, the note threshold, the floor, the rounding mode, the terms, the grace days per grade, the late percent, the policy date, period numbering from, the grace days, the entry date, the pot, the alert threshold, the parent sheet and the permission matrix) and the roles it gives user names: clerk, the default for
// any name, steward, or viewer (who may change nothing). The settings that carry a figure are dated: each is saved from a from-date
// (blank: the sheet's today), and what shows in force is the value on the sheet's today.
@Component({
  selector: 'app-settings-form',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="settings-heading">
      <h2 id="settings-heading">Settings</h2>
      <form data-testid="settings-form" [formGroup]="settingsForm" (ngSubmit)="saveSettings()">
        <label for="setting-clerk-limit">Clerk limit</label>
        <input id="setting-clerk-limit" data-testid="setting-clerk-limit" formControlName="clerkLimit"
               inputmode="decimal" />
        <label for="setting-clerk-limit-from">Clerk limit from (blank: today)</label>
        <input id="setting-clerk-limit-from" data-testid="setting-clerk-limit-from" type="date" formControlName="clerkLimitFrom" />
        <label for="setting-cap">Cap on a person's due-total (blank: none)</label>
        <input id="setting-cap" data-testid="setting-cap" formControlName="cap" inputmode="decimal" />
        <label for="setting-cap-from">Cap from (blank: today)</label>
        <input id="setting-cap-from" data-testid="setting-cap-from" type="date" formControlName="capFrom" />
        <label for="setting-proof-threshold">Proof threshold (a claim due above it needs a proof; blank: none)</label>
        <input id="setting-proof-threshold" data-testid="setting-proof-threshold" formControlName="proofThreshold"
               inputmode="decimal" />
        <label for="setting-proof-threshold-from">Proof threshold from (blank: today)</label>
        <input id="setting-proof-threshold-from" data-testid="setting-proof-threshold-from" type="date"
               formControlName="proofThresholdFrom" />
        <fieldset formGroupName="gradeProofThresholds">
          <legend>Proof threshold per grade (blank: the sheet's proof threshold)</legend>
          @for (g of grades; track g) {
            <label [for]="'setting-proof-threshold-' + g">Grade {{ g }} proof threshold</label>
            <input [id]="'setting-proof-threshold-' + g" [attr.data-testid]="'setting-proof-threshold-' + g"
                   [formControlName]="g" inputmode="decimal" />
            <label [for]="'setting-proof-threshold-' + g + '-from'">Grade {{ g }} proof threshold from (blank: today)</label>
            <input [id]="'setting-proof-threshold-' + g + '-from'" [attr.data-testid]="'setting-proof-threshold-' + g + '-from'"
                   type="date" [formControlName]="g + 'From'" />
          }
        </fieldset>
        <label for="setting-note-threshold">Note threshold (a claim due above it needs a note to be opened; blank: none)</label>
        <input id="setting-note-threshold" data-testid="setting-note-threshold" formControlName="noteThreshold"
               inputmode="decimal" />
        <label for="setting-note-threshold-from">Note threshold from (blank: today)</label>
        <input id="setting-note-threshold-from" data-testid="setting-note-threshold-from" type="date"
               formControlName="noteThresholdFrom" />
        <label for="setting-floor">Floor</label>
        <input id="setting-floor" data-testid="setting-floor" formControlName="floor" inputmode="decimal" />
        <label for="setting-floor-from">Floor from (blank: today)</label>
        <input id="setting-floor-from" data-testid="setting-floor-from" type="date" formControlName="floorFrom" />
        <label for="setting-rounding">Rounding mode (half-up or half-even; blank: none, the policy date decides)</label>
        <input id="setting-rounding" data-testid="setting-rounding" formControlName="rounding" />
        <label for="setting-rounding-from">Rounding mode from (blank: today)</label>
        <input id="setting-rounding-from" data-testid="setting-rounding-from" type="date" formControlName="roundingFrom" />
        <label for="setting-terms">Terms (business days)</label>
        <input id="setting-terms" data-testid="setting-terms" formControlName="terms" inputmode="numeric" />
        <label for="setting-terms-from">Terms from (blank: today)</label>
        <input id="setting-terms-from" data-testid="setting-terms-from" type="date" formControlName="termsFrom" />
        <fieldset formGroupName="gradeTerms">
          <legend>Terms per grade (days; blank: the sheet's terms)</legend>
          @for (g of grades; track g) {
            <label [for]="'setting-terms-' + g">Grade {{ g }}</label>
            <input [id]="'setting-terms-' + g" [attr.data-testid]="'setting-terms-' + g" [formControlName]="g"
                   inputmode="numeric" />
            <label [for]="'setting-terms-' + g + '-from'">Grade {{ g }} terms from (blank: today)</label>
            <input [id]="'setting-terms-' + g + '-from'" [attr.data-testid]="'setting-terms-' + g + '-from'"
                   type="date" [formControlName]="g + 'From'" />
          }
        </fieldset>
        <fieldset formGroupName="gradeGraces">
          <legend>Grace days per grade (a claim is overdue only once its due-by plus them is before today; blank: none)</legend>
          @for (g of grades; track g) {
            <label [for]="'setting-grace-' + g">Grade {{ g }} grace days</label>
            <input [id]="'setting-grace-' + g" [attr.data-testid]="'setting-grace-' + g" [formControlName]="g"
                   inputmode="numeric" />
            <label [for]="'setting-grace-' + g + '-from'">Grade {{ g }} grace days from (blank: today)</label>
            <input [id]="'setting-grace-' + g + '-from'" [attr.data-testid]="'setting-grace-' + g + '-from'"
                   type="date" [formControlName]="g + 'From'" />
          }
        </fieldset>
        <label for="setting-late-pct">Late percent (per 30 days overdue)</label>
        <input id="setting-late-pct" data-testid="setting-late-pct" formControlName="latePct" inputmode="decimal" />
        <label for="setting-late-pct-from">Late percent from (blank: today)</label>
        <input id="setting-late-pct-from" data-testid="setting-late-pct-from" type="date" formControlName="latePctFrom" />
        <label for="setting-policy-date">Policy date (claims dated before it round half up; blank: today)</label>
        <input id="setting-policy-date" data-testid="setting-policy-date" type="date" formControlName="policyDate" />
        <label for="setting-period-numbering-from">Period numbering from (claims opened from it are numbered within their period; blank: off)</label>
        <input id="setting-period-numbering-from" data-testid="setting-period-numbering-from" type="date"
               formControlName="periodNumberingFrom" />
        <label for="setting-grace-days">Grace days (a period still open this many days after its end closes when today is changed; blank: off)</label>
        <input id="setting-grace-days" data-testid="setting-grace-days" formControlName="graceDays" inputmode="numeric" />
        <label for="setting-entry-date">Entry date (a new claim's date when none is given; blank: off, the sheet's today)</label>
        <input id="setting-entry-date" data-testid="setting-entry-date" type="date" formControlName="entryDate" />
        <label for="setting-pot">Pot (a balanced pool's members cut it by their shares; blank: none)</label>
        <input id="setting-pot" data-testid="setting-pot" formControlName="pot" inputmode="decimal" />
        <label for="setting-alert-threshold">Alert threshold (a person whose due-total crosses above it is alerted; blank: off)</label>
        <input id="setting-alert-threshold" data-testid="setting-alert-threshold" formControlName="alertThreshold"
               inputmode="decimal" />
        <label for="setting-fiscal-start-month">Fiscal year start month (1 to 12; a fiscal year runs from the first day of it)</label>
        <input id="setting-fiscal-start-month" data-testid="setting-fiscal-start-month" formControlName="fiscalStartMonth"
               inputmode="numeric" />
        <label for="setting-parent">Parent sheet (a grade with no rate on a date here takes the parent's; blank: none)</label>
        <input id="setting-parent" data-testid="setting-parent" formControlName="parent" />
        <fieldset formGroupName="permissions">
          <legend>Permissions (roles that may take each action, comma separated; blank: the standing rule)</legend>
          @for (a of permActions; track a) {
            <label [for]="'setting-perm-' + a">{{ a }} (blank: {{ standing[a] }})</label>
            <input [id]="'setting-perm-' + a" [attr.data-testid]="'setting-perm-' + a" [formControlName]="a" />
          }
        </fieldset>
        <button type="submit" data-testid="settings-save">Save settings</button>
        @if (settingsError()) {
          <p role="alert" data-testid="settings-error">{{ settingsError() }}</p>
        }
      </form>
      <p>Clerk limit in force: <span data-testid="setting-clerk-limit-current">{{ clerkLimit() ?? '' }}</span></p>
      <p>Cap in force: <span data-testid="setting-cap-current">{{ cap() ?? '' }}</span></p>
      <p>Proof threshold in force: <span data-testid="setting-proof-threshold-current">{{ proofThreshold() ?? '' }}</span></p>
      @for (g of grades; track g) {
        <p>Proof threshold in force for grade {{ g }}:
          <span [attr.data-testid]="'setting-proof-threshold-' + g + '-current'">{{ gradeProofThresholds()[g] ?? '' }}</span></p>
      }
      <p>Note threshold in force: <span data-testid="setting-note-threshold-current">{{ noteThreshold() ?? '' }}</span></p>
      <p>Floor in force: <span data-testid="setting-floor-current">{{ floor() ?? '' }}</span></p>
      <p>Rounding mode in force: <span data-testid="setting-rounding-current">{{ rounding() ?? '' }}</span></p>
      <p>Terms in force: <span data-testid="setting-terms-current">{{ terms() ?? '' }}</span></p>
      @for (g of grades; track g) {
        <p>Terms in force for grade {{ g }}:
          <span [attr.data-testid]="'setting-terms-' + g + '-current'">{{ termsOf(g) ?? '' }}</span></p>
      }
      @for (g of grades; track g) {
        <p>Grace days in force for grade {{ g }}:
          <span [attr.data-testid]="'setting-grace-' + g + '-current'">{{ gradeGraces()[g] ?? '' }}</span></p>
      }
      <p>Late percent in force: <span data-testid="setting-late-pct-current">{{ latePct() ?? '' }}</span></p>
      <p>Policy date in force: <span data-testid="setting-policy-date-current">{{ policyDate() ?? '' }}</span></p>
      <p>Period numbering from: <span data-testid="setting-period-numbering-from-current">{{ periodNumberingFrom() ?? '' }}</span></p>
      <p>Grace days: <span data-testid="setting-grace-days-current">{{ graceDays() ?? '' }}</span></p>
      <p>Entry date: <span data-testid="setting-entry-date-current">{{ entryDate() ?? '' }}</span></p>
      <p>Pot: <span data-testid="setting-pot-current">{{ pot() ?? '' }}</span></p>
      <p>Alert threshold: <span data-testid="setting-alert-threshold-current">{{ alertThreshold() ?? '' }}</span></p>
      <p>Fiscal year start month: <span data-testid="setting-fiscal-start-month-current">{{ fiscalStartMonth() ?? '' }}</span></p>
      <p>Parent sheet: <span data-testid="setting-parent-current">{{ parent() ?? '' }}</span></p>
      @for (a of permActions; track a) {
        <p>Roles that may {{ a }}:
          <span [attr.data-testid]="'setting-perm-' + a + '-current'">{{ permissions()[a] ?? '' }}</span></p>
      }
      <h3>Roles</h3>
      <form data-testid="role-form" [formGroup]="roleForm" (ngSubmit)="saveRole()">
        <label for="role-form-user">User</label>
        <input id="role-form-user" data-testid="role-form-user" formControlName="user" />
        <label for="role-form-role">Role</label>
        <input id="role-form-role" data-testid="role-form-role" formControlName="role" list="role-names" />
        <datalist id="role-names">
          @for (r of roleNames; track r) {
            <option [value]="r"></option>
          }
        </datalist>
        <button type="submit" data-testid="role-form-submit">Set role</button>
        @if (roleError()) {
          <p role="alert" data-testid="role-form-error">{{ roleError() }}</p>
        }
      </form>
      <table data-testid="roles">
        <caption>Roles given (any other name is a clerk)</caption>
        <thead>
          <tr><th scope="col">User</th><th scope="col">Role</th></tr>
        </thead>
        <tbody>
          @for (r of roles(); track r.user) {
            <tr [attr.data-testid]="'role-row-' + r.user">
              <th scope="row" data-testid="role-user">{{ r.user }}</th>
              <td data-testid="role-value">{{ r.role }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class SettingsForm {
  private readonly api = inject(SheetSettings);
  private readonly session = inject(Session);
  private readonly pools = inject(Pools);

  protected readonly roleNames = ROLES;
  protected readonly clerkLimit = signal<string | null>(null);
  protected readonly cap = signal<string | null>(null);
  protected readonly proofThreshold = signal<string | null>(null);
  protected readonly noteThreshold = signal<string | null>(null);
  protected readonly floor = signal<string | null>(null);
  protected readonly terms = signal<number | null>(null);
  protected readonly rounding = signal<string | null>(null);
  protected readonly grades: Grade[] = ['A', 'B', 'C'];
  /** The terms each grade has of its own; a grade not in it takes the sheet's terms. */
  protected readonly gradeTerms = signal<Partial<Record<Grade, number>>>({});
  /** The grace days each grade has in force; a grade not in it has none. */
  protected readonly gradeGraces = signal<Partial<Record<Grade, number>>>({});
  /** The proof threshold each grade has of its own in force; a grade not in it takes the sheet's. */
  protected readonly gradeProofThresholds = signal<Partial<Record<Grade, string>>>({});
  protected readonly latePct = signal<string | null>(null);
  protected readonly policyDate = signal<string | null>(null);
  protected readonly periodNumberingFrom = signal<string | null>(null);
  protected readonly graceDays = signal<number | null>(null);
  protected readonly entryDate = signal<string | null>(null);
  protected readonly pot = signal<string | null>(null);
  protected readonly alertThreshold = signal<string | null>(null);
  protected readonly parent = signal<string | null>(null);
  protected readonly fiscalStartMonth = signal<number | null>(null);
  protected readonly permActions = PERM_ACTIONS;
  protected readonly standing = STANDING_PERMISSIONS;
  /** The permission matrix as set; an action's entry is blank when it keeps the standing rule. */
  protected readonly permissions = signal<Partial<Record<PermAction, string>>>({});
  protected readonly roles = signal<Role[]>([]);
  protected readonly settingsError = signal('');
  protected readonly roleError = signal('');
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private submitting = false;
  /** Only the newest read of the sheet may show: a load overtaken by another is dropped. */
  private reads = 0;
  protected readonly settingsForm = new FormGroup({
    clerkLimit: new FormControl('', { nonNullable: true }),
    clerkLimitFrom: new FormControl('', { nonNullable: true }),
    cap: new FormControl('', { nonNullable: true }),
    capFrom: new FormControl('', { nonNullable: true }),
    proofThreshold: new FormControl('', { nonNullable: true }),
    proofThresholdFrom: new FormControl('', { nonNullable: true }),
    gradeProofThresholds: new FormGroup({
      A: new FormControl('', { nonNullable: true }),
      AFrom: new FormControl('', { nonNullable: true }),
      B: new FormControl('', { nonNullable: true }),
      BFrom: new FormControl('', { nonNullable: true }),
      C: new FormControl('', { nonNullable: true }),
      CFrom: new FormControl('', { nonNullable: true }),
    }),
    noteThreshold: new FormControl('', { nonNullable: true }),
    noteThresholdFrom: new FormControl('', { nonNullable: true }),
    floor: new FormControl('', { nonNullable: true }),
    floorFrom: new FormControl('', { nonNullable: true }),
    rounding: new FormControl('', { nonNullable: true }),
    roundingFrom: new FormControl('', { nonNullable: true }),
    terms: new FormControl('', { nonNullable: true }),
    termsFrom: new FormControl('', { nonNullable: true }),
    gradeTerms: new FormGroup({
      A: new FormControl('', { nonNullable: true }),
      AFrom: new FormControl('', { nonNullable: true }),
      B: new FormControl('', { nonNullable: true }),
      BFrom: new FormControl('', { nonNullable: true }),
      C: new FormControl('', { nonNullable: true }),
      CFrom: new FormControl('', { nonNullable: true }),
    }),
    gradeGraces: new FormGroup({
      A: new FormControl('', { nonNullable: true }),
      AFrom: new FormControl('', { nonNullable: true }),
      B: new FormControl('', { nonNullable: true }),
      BFrom: new FormControl('', { nonNullable: true }),
      C: new FormControl('', { nonNullable: true }),
      CFrom: new FormControl('', { nonNullable: true }),
    }),
    latePct: new FormControl('', { nonNullable: true }),
    latePctFrom: new FormControl('', { nonNullable: true }),
    policyDate: new FormControl('', { nonNullable: true }),
    periodNumberingFrom: new FormControl('', { nonNullable: true }),
    graceDays: new FormControl('', { nonNullable: true }),
    entryDate: new FormControl('', { nonNullable: true }),
    pot: new FormControl('', { nonNullable: true }),
    alertThreshold: new FormControl('', { nonNullable: true }),
    parent: new FormControl('', { nonNullable: true }),
    fiscalStartMonth: new FormControl('', { nonNullable: true }),
    permissions: new FormGroup({
      open: new FormControl('', { nonNullable: true }),
      settle: new FormControl('', { nonNullable: true }),
      void: new FormControl('', { nonNullable: true }),
      reassign: new FormControl('', { nonNullable: true }),
      close: new FormControl('', { nonNullable: true }),
      reopen: new FormControl('', { nonNullable: true }),
    }),
  });
  protected readonly roleForm = new FormGroup({
    user: new FormControl('', { nonNullable: true }),
    role: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.settingsForm.valueChanges.subscribe(() => this.settingsError.set(''));
    this.roleForm.valueChanges.subscribe(() => this.roleError.set(''));
    effect(() => {
      this.session.loads();
      this.session.today(); // what is in force follows the sheet's today
      const sheetId = this.session.sheetId();
      this.clerkLimit.set(null);
      this.cap.set(null);
      this.proofThreshold.set(null);
      this.noteThreshold.set(null);
      this.floor.set(null);
      this.rounding.set(null);
      this.terms.set(null);
      this.gradeTerms.set({});
      this.gradeGraces.set({});
      this.gradeProofThresholds.set({});
      this.latePct.set(null);
      this.policyDate.set(null);
      this.periodNumberingFrom.set(null);
      this.graceDays.set(null);
      this.entryDate.set(null);
      this.pot.set(null);
      this.alertThreshold.set(null);
      this.parent.set(null);
      this.fiscalStartMonth.set(null);
      this.permissions.set({});
      this.roles.set([]);
      if (sheetId) void this.reload(sheetId);
    });
  }

  /** Save the settings changed since the last save (typed into, or entered and left, which an
   *  unchanged blank field may be); all of them when none was. */
  protected async saveSettings(): Promise<void> {
    if (this.submitting) return;
    this.settingsError.set('');
    const { clerkLimit, clerkLimitFrom, cap, capFrom, proofThreshold, proofThresholdFrom, gradeProofThresholds, noteThreshold, noteThresholdFrom, floor, floorFrom, rounding, roundingFrom, terms, termsFrom, gradeTerms, gradeGraces, latePct,
      latePctFrom, policyDate, periodNumberingFrom, graceDays, entryDate, pot, alertThreshold, parent, fiscalStartMonth, permissions } = this.settingsForm.controls;
    const changed = (...cs: AbstractControl[]) => cs.some((c) => c.dirty || c.touched);
    const none = !changed(clerkLimit, clerkLimitFrom) && !changed(cap, capFrom)
      && !changed(proofThreshold, proofThresholdFrom) && !changed(gradeProofThresholds) && !changed(noteThreshold, noteThresholdFrom)
      && !changed(floor, floorFrom) && !changed(rounding, roundingFrom)
      && !changed(terms, termsFrom) && !changed(gradeTerms) && !changed(gradeGraces) && !changed(latePct, latePctFrom) && !changed(policyDate)
      && !changed(periodNumberingFrom) && !changed(graceDays) && !changed(entryDate) && !changed(pot) && !changed(alertThreshold) && !changed(parent) && !changed(fiscalStartMonth) && !changed(permissions);
    /** A from-date typed, or null when blank (the sheet's today). */
    const from = (c: AbstractControl<string>) => c.value.trim() || null;
    const fromsToSave: AbstractControl<string>[] = [];
    const limitValue = clerkLimit.value.trim();
    const capValue = cap.value.trim();
    const saveCap = changed(cap, capFrom) || (none && capValue !== '');
    const thresholdValue = proofThreshold.value.trim();
    const saveThreshold = changed(proofThreshold, proofThresholdFrom) || (none && thresholdValue !== '');
    const gradeThresholdsToSave = this.grades
      .map((g) => ({
        grade: g, control: gradeProofThresholds.controls[g], fromControl: gradeProofThresholds.controls[`${g}From`],
        value: gradeProofThresholds.controls[g].value.trim(),
      }))
      .filter((t) => changed(t.control, t.fromControl) || (none && t.value !== ''));
    const noteThresholdValue = noteThreshold.value.trim();
    const saveNoteThreshold = changed(noteThreshold, noteThresholdFrom) || (none && noteThresholdValue !== '');
    const floorValue = floor.value.trim();
    const saveLimit = none || changed(clerkLimit, clerkLimitFrom);
    const saveFloor = none || changed(floor, floorFrom);
    const roundingValue = rounding.value.trim();
    const saveRounding = changed(rounding, roundingFrom) || (none && roundingValue !== '');
    const termsValue = terms.value.trim();
    const saveTerms = none || changed(terms, termsFrom);
    const gradeTermsToSave = this.grades
      .map((g) => ({
        grade: g, control: gradeTerms.controls[g], fromControl: gradeTerms.controls[`${g}From`],
        value: gradeTerms.controls[g].value.trim(),
      }))
      .filter((t) => (none || changed(t.control, t.fromControl)) && t.value !== '');
    const gracesToSave = this.grades
      .map((g) => ({
        grade: g, control: gradeGraces.controls[g], fromControl: gradeGraces.controls[`${g}From`],
        value: gradeGraces.controls[g].value.trim(),
      }))
      .filter((t) => changed(t.control, t.fromControl) || (none && t.value !== ''));
    const latePctValue = latePct.value.trim();
    const saveLatePct = none || changed(latePct, latePctFrom);
    const policyDateValue = policyDate.value.trim();
    const savePolicyDate = none || changed(policyDate);
    const numberingValue = periodNumberingFrom.value.trim();
    const saveNumbering = none || changed(periodNumberingFrom);
    const graceValue = graceDays.value.trim();
    const saveGrace = changed(graceDays) || (none && graceValue !== '');
    const entryDateValue = entryDate.value.trim();
    const saveEntryDate = changed(entryDate) || (none && entryDateValue !== '');
    const potValue = pot.value.trim();
    const savePot = changed(pot) || (none && potValue !== '');
    const alertValue = alertThreshold.value.trim();
    const saveAlert = changed(alertThreshold) || (none && alertValue !== '');
    const parentValue = parent.value.trim();
    const fiscalValue = fiscalStartMonth.value.trim();
    const saveFiscal = changed(fiscalStartMonth) || (none && fiscalValue !== '');
    const saveParent = changed(parent) || (none && parentValue !== '');
    const permsToSave = this.permActions
      .map((a) => ({ action: a, control: permissions.controls[a], value: permissions.controls[a].value.trim() }))
      .filter((p) => changed(p.control) || (none && p.value !== ''));
    if (saveLimit) fromsToSave.push(clerkLimitFrom);
    if (saveCap) fromsToSave.push(capFrom);
    if (saveThreshold) fromsToSave.push(proofThresholdFrom);
    gradeThresholdsToSave.forEach((t) => fromsToSave.push(t.fromControl));
    if (saveNoteThreshold) fromsToSave.push(noteThresholdFrom);
    if (saveFloor && floorValue !== '') fromsToSave.push(floorFrom);
    if (saveRounding) fromsToSave.push(roundingFrom);
    if (saveTerms && termsValue !== '') fromsToSave.push(termsFrom);
    gradeTermsToSave.forEach((t) => fromsToSave.push(t.fromControl));
    gracesToSave.forEach((t) => fromsToSave.push(t.fromControl));
    if (saveLatePct && latePctValue !== '') fromsToSave.push(latePctFrom);
    if (fromsToSave.some((c) => c.value.trim() !== '' && !POLICY_DATE.test(c.value.trim()))) {
      this.settingsError.set('Enter a from-date, or leave it blank for the sheet\'s today.');
      return;
    }
    if (saveLimit && limitValue !== '' && !LIMIT.test(limitValue)) {
      this.settingsError.set('Enter a clerk limit of 0.00 or more with up to two decimals, or leave it blank for no limit.');
      return;
    }
    if (saveCap && capValue !== '' && !LIMIT.test(capValue)) {
      this.settingsError.set('Enter a cap of 0.00 or more with up to two decimals, or leave it blank for no cap.');
      return;
    }
    if (saveThreshold && thresholdValue !== '' && !LIMIT.test(thresholdValue)) {
      this.settingsError.set('Enter a proof threshold of 0.00 or more with up to two decimals, or leave it blank for none.');
      return;
    }
    if (gradeThresholdsToSave.some((t) => t.value !== '' && !LIMIT.test(t.value))) {
      this.settingsError.set('Enter a proof threshold for a grade of 0.00 or more with up to two decimals, or leave it blank for the sheet\'s.');
      return;
    }
    if (saveNoteThreshold && noteThresholdValue !== '' && !LIMIT.test(noteThresholdValue)) {
      this.settingsError.set('Enter a note threshold of 0.00 or more with up to two decimals, or leave it blank for none.');
      return;
    }
    if (saveFloor && !(none && floorValue === '') && !LIMIT.test(floorValue)) {
      this.settingsError.set('Enter a floor of 0.00 or more with up to two decimals.');
      return;
    }
    if (saveRounding && roundingValue !== '' && !ROUNDING.test(roundingValue)) {
      this.settingsError.set('Enter a rounding mode of half-up or half-even, or leave it blank for none.');
      return;
    }
    if (saveTerms && !(none && termsValue === '') && !TERMS.test(termsValue)) {
      this.settingsError.set('Enter terms of 0 or more whole days.');
      return;
    }
    if (gradeTermsToSave.some((t) => !TERMS.test(t.value))) {
      this.settingsError.set('Enter terms for a grade of 0 or more whole days, or leave them blank.');
      return;
    }
    if (gracesToSave.some((t) => t.value !== '' && !TERMS.test(t.value))) {
      this.settingsError.set('Enter grace days for a grade of 0 or more whole days, or leave them blank for none.');
      return;
    }
    if (saveLatePct && !(none && latePctValue === '') && !LATE_PCT.test(latePctValue)) {
      this.settingsError.set('Enter a late percent of 0.00 or more with up to two decimals.');
      return;
    }
    if (savePolicyDate && policyDateValue !== '' && !POLICY_DATE.test(policyDateValue)) {
      this.settingsError.set('Enter a policy date, or leave it blank for the sheet\'s today.');
      return;
    }
    if (saveNumbering && numberingValue !== '' && !POLICY_DATE.test(numberingValue)) {
      this.settingsError.set('Enter a period numbering from date, or leave it blank for off.');
      return;
    }
    if (saveGrace && graceValue !== '' && !TERMS.test(graceValue)) {
      this.settingsError.set('Enter grace days of 0 or more whole days, or leave them blank for off.');
      return;
    }
    if (saveEntryDate && entryDateValue !== '' && !POLICY_DATE.test(entryDateValue)) {
      this.settingsError.set('Enter an entry date, or leave it blank for off.');
      return;
    }
    if (savePot && potValue !== '' && !LIMIT.test(potValue)) {
      this.settingsError.set('Enter a pot of 0.00 or more with up to two decimals, or leave it blank for none.');
      return;
    }
    if (saveAlert && alertValue !== '' && !LIMIT.test(alertValue)) {
      this.settingsError.set('Enter an alert threshold of 0.00 or more with up to two decimals, or leave it blank for off.');
      return;
    }
    if (saveFiscal && !FISCAL_MONTH.test(fiscalValue)) {
      this.settingsError.set('Enter a fiscal year start month of 1 to 12.');
      return;
    }
    if (saveParent && parentValue.length > 100) {
      this.settingsError.set('Enter a parent sheet id of up to 100 characters, or leave it blank for none.');
      return;
    }
    if (permsToSave.some((p) => p.value !== '' && !PERM_ROLES.test(p.value))) {
      this.settingsError.set('Enter the roles that may take an action as clerk, steward or both, comma separated, or leave it blank for the standing rule.');
      return;
    }
    this.submitting = true;
    try {
      if (saveLimit) {
        const saved = await this.api.setClerkLimit(limitValue === '' ? null : limitValue, from(clerkLimitFrom));
        this.clerkLimit.set(saved.clerkLimit);
        for (const c of [clerkLimit, clerkLimitFrom]) {
          c.markAsPristine();
          c.markAsUntouched();
        }
      }
      if (saveCap) {
        const saved = await this.api.setCap(capValue === '' ? null : capValue, from(capFrom));
        this.cap.set(saved.cap);
        for (const c of [cap, capFrom]) {
          c.markAsPristine();
          c.markAsUntouched();
        }
      }
      if (saveThreshold) {
        const saved = await this.api.setProofThreshold(thresholdValue === '' ? null : thresholdValue,
          from(proofThresholdFrom));
        this.proofThreshold.set(saved.proofThreshold);
        for (const c of [proofThreshold, proofThresholdFrom]) {
          c.markAsPristine();
          c.markAsUntouched();
        }
      }
      for (const t of gradeThresholdsToSave) {
        const saved = await this.api.setGradeProofThreshold(t.grade, t.value === '' ? null : t.value,
          from(t.fromControl));
        this.gradeProofThresholds.set(saved.gradeProofThresholds);
        for (const c of [t.control, t.fromControl]) {
          c.markAsPristine();
          c.markAsUntouched();
        }
      }
      if (saveNoteThreshold) {
        const saved = await this.api.setNoteThreshold(noteThresholdValue === '' ? null : noteThresholdValue,
          from(noteThresholdFrom));
        this.noteThreshold.set(saved.noteThreshold);
        for (const c of [noteThreshold, noteThresholdFrom]) {
          c.markAsPristine();
          c.markAsUntouched();
        }
      }
      if (saveFloor && floorValue !== '') {
        const saved = await this.api.setFloor(floorValue, from(floorFrom));
        this.floor.set(saved.floor);
        for (const c of [floor, floorFrom]) {
          c.markAsPristine();
          c.markAsUntouched();
        }
      }
      if (saveRounding) {
        const saved = await this.api.setRounding(roundingValue === '' ? null : roundingValue, from(roundingFrom));
        this.rounding.set(saved.rounding);
        for (const c of [rounding, roundingFrom]) {
          c.markAsPristine();
          c.markAsUntouched();
        }
      }
      if (saveTerms && termsValue !== '') {
        const saved = await this.api.setTerms(termsValue, from(termsFrom));
        this.terms.set(saved.terms);
        for (const c of [terms, termsFrom]) {
          c.markAsPristine();
          c.markAsUntouched();
        }
      }
      for (const t of gradeTermsToSave) {
        const saved = await this.api.setGradeTerms(t.grade, t.value, from(t.fromControl));
        this.gradeTerms.set(saved.gradeTerms);
        for (const c of [t.control, t.fromControl]) {
          c.markAsPristine();
          c.markAsUntouched();
        }
      }
      for (const t of gracesToSave) {
        const saved = await this.api.setGrace(t.grade, t.value === '' ? null : t.value, from(t.fromControl));
        this.gradeGraces.set(saved.gradeGraces);
        for (const c of [t.control, t.fromControl]) {
          c.markAsPristine();
          c.markAsUntouched();
        }
      }
      if (saveLatePct && latePctValue !== '') {
        const saved = await this.api.setLatePct(latePctValue, from(latePctFrom));
        this.latePct.set(saved.latePct);
        for (const c of [latePct, latePctFrom]) {
          c.markAsPristine();
          c.markAsUntouched();
        }
      }
      if (savePolicyDate) {
        const saved = await this.api.setPolicyDate(policyDateValue === '' ? null : policyDateValue);
        this.policyDate.set(saved.policyDate);
        policyDate.markAsPristine();
        policyDate.markAsUntouched();
      }
      if (saveNumbering) {
        const saved = await this.api.setPeriodNumberingFrom(numberingValue === '' ? null : numberingValue);
        this.periodNumberingFrom.set(saved.periodNumberingFrom);
        periodNumberingFrom.markAsPristine();
        periodNumberingFrom.markAsUntouched();
      }
      if (saveGrace) {
        const saved = await this.api.setGraceDays(graceValue === '' ? null : graceValue);
        this.graceDays.set(saved.graceDays);
        graceDays.markAsPristine();
        graceDays.markAsUntouched();
      }
      if (saveEntryDate) {
        const saved = await this.api.setEntryDate(entryDateValue === '' ? null : entryDateValue);
        this.entryDate.set(saved.entryDate);
        entryDate.markAsPristine();
        entryDate.markAsUntouched();
      }
      if (savePot) {
        const saved = await this.api.setPot(potValue === '' ? null : potValue);
        this.pot.set(saved.pot);
        pot.markAsPristine();
        pot.markAsUntouched();
        void this.pools.refresh();
      }
      if (saveAlert) {
        const saved = await this.api.setAlertThreshold(alertValue === '' ? null : alertValue);
        this.alertThreshold.set(saved.alertThreshold);
        alertThreshold.markAsPristine();
        alertThreshold.markAsUntouched();
      }
      if (saveFiscal) {
        const saved = await this.api.setFiscalStartMonth(fiscalValue);
        this.fiscalStartMonth.set(saved.fiscalStartMonth);
        fiscalStartMonth.markAsPristine();
        fiscalStartMonth.markAsUntouched();
      }
      if (saveParent) {
        const saved = await this.api.setParent(parentValue === '' ? null : parentValue);
        this.parent.set(saved.parent);
        this.session.parent.set(saved.parent);
        parent.markAsPristine();
        parent.markAsUntouched();
      }
      for (const p of permsToSave) {
        const saved = await this.api.setPermission(p.action, p.value === '' ? null : p.value);
        this.permissions.set(saved.permissions);
        p.control.markAsPristine();
        p.control.markAsUntouched();
      }
      // a blank field entered and left, and not saved, is not taken as changed by the next save
      this.settingsForm.markAsUntouched();
    } catch {
      this.settingsError.set('The settings could not be saved.');
    } finally {
      this.submitting = false;
    }
  }

  /** The terms in force for a grade: its own, or else the sheet's. */
  protected termsOf(g: Grade): number | null {
    return this.gradeTerms()[g] ?? this.terms();
  }

  protected async saveRole(): Promise<void> {
    if (this.submitting) return;
    this.roleError.set('');
    const user = this.roleForm.getRawValue().user.trim();
    const role = this.roleForm.getRawValue().role.trim();
    if (user === '') {
      this.roleError.set('Enter a user name.');
      return;
    }
    if (!(ROLES as string[]).includes(role)) {
      this.roleError.set('Choose a role of clerk, steward or viewer.');
      return;
    }
    this.submitting = true;
    try {
      const saved = await this.api.setRole(user, role as RoleName);
      this.roles.update((rs) =>
        [...rs.filter((r) => r.user !== saved.user), saved].sort((a, b) => a.user.localeCompare(b.user)),
      );
      this.roleForm.reset({ user: '', role: '' }, { emitEvent: false });
    } catch {
      this.roleError.set('The role could not be set.');
    } finally {
      this.submitting = false;
    }
  }

  private async reload(sheetId: string): Promise<void> {
    const read = ++this.reads;
    try {
      const [settings, roles] = await Promise.all([this.api.get(sheetId), this.api.roles(sheetId)]);
      if (read === this.reads) {
        this.clerkLimit.set(settings.clerkLimit);
        this.cap.set(settings.cap);
        this.proofThreshold.set(settings.proofThreshold);
        this.noteThreshold.set(settings.noteThreshold);
        this.floor.set(settings.floor);
        this.rounding.set(settings.rounding);
        this.terms.set(settings.terms);
        this.gradeTerms.set(settings.gradeTerms);
        this.gradeGraces.set(settings.gradeGraces);
        this.gradeProofThresholds.set(settings.gradeProofThresholds);
        this.latePct.set(settings.latePct);
        this.policyDate.set(settings.policyDate);
        this.periodNumberingFrom.set(settings.periodNumberingFrom);
        this.graceDays.set(settings.graceDays);
        this.entryDate.set(settings.entryDate);
        this.pot.set(settings.pot);
        this.alertThreshold.set(settings.alertThreshold);
        this.parent.set(settings.parent);
        this.fiscalStartMonth.set(settings.fiscalStartMonth);
        this.permissions.set(settings.permissions);
        this.roles.set(roles);
      }
    } catch {
      // the settings stay blank; the next load reads them again
    }
  }
}
