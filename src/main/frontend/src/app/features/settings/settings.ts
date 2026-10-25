import { Component, computed, effect, inject, signal } from '@angular/core';
import { AbstractControl, FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { GRADES, Grade } from '../people/people-api';
import { DatedValue, valueOn } from './dated';
import { DEFAULT_TERMS, PERMISSION_ACTIONS, PermissionAction, ROLES, Role, RoleView, SettingsApi, SettingsView } from './settings-api';
import { SheetCap } from './sheet-cap';
import { DEFAULT_FLOOR_CENTS, SheetFloor } from './sheet-floor';
import { DEFAULT_LATE_PCT, SheetLatePct } from './sheet-late-pct';
import { SheetPolicyDate } from './sheet-policy-date';

const MONEY = /^\d{1,10}(\.\d{1,2})?$/;
const DAYS = /^\d{1,5}$/;
const PCT = /^\d{1,10}(\.\d{1,2})?$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

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

// The sheet's settings, with the clerk limit (the highest due a clerk may settle) and the floor (the
// least a claim's live due can be) and the terms (the days after a claim's date it is due by, fixed when the
// claim is added) and each grade's own terms (blank: the sheet's terms) and the late
// percent (what an overdue claim gains of its figure for every full 30 days overdue) and the policy date
// (a claim dated before it rounds half up, on or after it half to even; blank: the sheet's today) and period
// numbering from (once the sheet's today reaches it, a claim opened is numbered within its period; blank: off) and the cap
// (the most a person's due-total can be; blank: no cap) and the proof threshold (a claim whose due is above
// the one in force on its date needs a proof to be settled or approved; blank: none), and the roles it assigns user names, with one form for each.
// The settings that carry a figure (the clerk limit, the floor, the terms, each grade's terms, the late
// percent, the cap and the proof threshold) are dated: each has a from-date field (blank: the sheet's today), and saving records
// its value from that date, keeping the values recorded from other dates. Each shows the value in force on
// the sheet's today.
// The permission matrix names, for each of open, settle, void, reassign, close and reopen, the roles that
// may take it, comma separated (blank: the standing rule); it is not dated.
// Saving the settings stores each setting whose field (or from-date field) was changed or visited since
// the last save, so a value typed again as it was is still stored.
@Component({
  selector: 'app-settings',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="settings-heading">
      <h2 id="settings-heading">Settings</h2>
      <form [formGroup]="settingsForm" (ngSubmit)="saveSettings()" aria-label="Change the settings">
        <label for="setting-clerk-limit">Clerk limit (blank: no limit)</label>
        <input id="setting-clerk-limit" data-testid="setting-clerk-limit" formControlName="clerkLimit" inputmode="decimal" />
        <label for="setting-clerk-limit-from">Clerk limit from (yyyy-mm-dd; blank: the sheet's today)</label>
        <input id="setting-clerk-limit-from" data-testid="setting-clerk-limit-from" formControlName="clerkLimitFrom" />
        <p>Clerk limit now: <span data-testid="setting-clerk-limit-current">{{ clerkLimit() ?? '' }}</span></p>
        <label for="setting-floor">Floor (the least a claim's live due can be)</label>
        <input id="setting-floor" data-testid="setting-floor" formControlName="floor" inputmode="decimal" />
        <label for="setting-floor-from">Floor from (yyyy-mm-dd; blank: the sheet's today)</label>
        <input id="setting-floor-from" data-testid="setting-floor-from" formControlName="floorFrom" />
        <p>Floor now: <span data-testid="setting-floor-current">{{ floor() }}</span></p>
        <label for="setting-terms">Terms (days after a claim's date it is due by)</label>
        <input id="setting-terms" data-testid="setting-terms" formControlName="terms" inputmode="numeric" />
        <label for="setting-terms-from">Terms from (yyyy-mm-dd; blank: the sheet's today)</label>
        <input id="setting-terms-from" data-testid="setting-terms-from" formControlName="termsFrom" />
        <p>Terms now: <span data-testid="setting-terms-current">{{ terms() }}</span></p>
        @for (g of grades; track g) {
          <label [for]="'setting-terms-' + g">Terms of grade {{ g }} (days; blank: the sheet's terms)</label>
          <input [id]="'setting-terms-' + g" [attr.data-testid]="'setting-terms-' + g" [formControl]="gradeTermsForm.controls[g]" inputmode="numeric" />
          <label [for]="'setting-terms-' + g + '-from'">Terms of grade {{ g }} from (yyyy-mm-dd; blank: the sheet's today)</label>
          <input [id]="'setting-terms-' + g + '-from'" [attr.data-testid]="'setting-terms-' + g + '-from'" [formControl]="gradeTermsFromForm.controls[g]" />
          <p>Terms of grade {{ g }} now: <span [attr.data-testid]="'setting-terms-' + g + '-current'">{{ gradeTermsNow(g) }}</span></p>
        }
        <label for="setting-late-pct">Late percent (gained for every full 30 days overdue)</label>
        <input id="setting-late-pct" data-testid="setting-late-pct" formControlName="latePct" inputmode="decimal" />
        <label for="setting-late-pct-from">Late percent from (yyyy-mm-dd; blank: the sheet's today)</label>
        <input id="setting-late-pct-from" data-testid="setting-late-pct-from" formControlName="latePctFrom" />
        <p>Late percent now: <span data-testid="setting-late-pct-current">{{ latePct() }}</span></p>
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
        <p>Cap now: <span data-testid="setting-cap-current">{{ cap() ?? '' }}</span></p>
        <label for="setting-proof-threshold">Proof threshold (a claim due above it needs a proof to be settled; blank: none)</label>
        <input id="setting-proof-threshold" data-testid="setting-proof-threshold" formControlName="proofThreshold" inputmode="decimal" />
        <label for="setting-proof-threshold-from">Proof threshold from (yyyy-mm-dd; blank: the sheet's today)</label>
        <input id="setting-proof-threshold-from" data-testid="setting-proof-threshold-from" formControlName="proofThresholdFrom" />
        <p>Proof threshold now: <span data-testid="setting-proof-threshold-current">{{ proofThreshold() ?? '' }}</span></p>
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
        <label for="role-form-role">Role (clerk or steward)</label>
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
    </section>
  `,
})
export class Settings {
  private readonly session = inject(Session);
  private readonly api = inject(SettingsApi);
  private readonly sheetFloor = inject(SheetFloor);
  private readonly sheetLatePct = inject(SheetLatePct);
  private readonly sheetPolicyDate = inject(SheetPolicyDate);
  private readonly sheetCap = inject(SheetCap);

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
  private now(key: string): string | null {
    return valueOn(this.dated()[key] ?? [], this.session.sheet()?.today);
  }
  protected readonly clerkLimit = computed(() => this.now('clerk-limit'));
  protected readonly floor = computed(() => this.now('floor') ?? (DEFAULT_FLOOR_CENTS / 100).toFixed(2));
  protected readonly terms = computed(() => Number(this.now('terms') ?? DEFAULT_TERMS));
  protected readonly latePct = computed(() => this.now('late-pct') ?? DEFAULT_LATE_PCT);
  protected readonly policyDate = signal<string | null>(null);
  protected readonly periodNumberingFrom = signal<string | null>(null);
  protected readonly cap = computed(() => this.now('cap'));
  protected readonly proofThreshold = computed(() => this.now('proof-threshold'));
  protected readonly assigned = signal<RoleView[]>([]);
  protected readonly permissions = signal<Partial<Record<PermissionAction, string>>>({});

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
    cap: new FormControl('', { nonNullable: true }),
    capFrom: new FormControl('', { nonNullable: true }),
    proofThreshold: new FormControl('', { nonNullable: true }),
    proofThresholdFrom: new FormControl('', { nonNullable: true }),
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
  protected readonly savingSettings = signal(false);
  protected readonly savingRole = signal(false);
  protected readonly settingsError = signal('');
  protected readonly roleError = signal('');

  constructor() {
    this.settingsForm.valueChanges.subscribe(() => this.settingsError.set(''));
    this.gradeTermsForm.valueChanges.subscribe(() => this.settingsError.set(''));
    this.gradeTermsFromForm.valueChanges.subscribe(() => this.settingsError.set(''));
    this.permissionsForm.valueChanges.subscribe(() => this.settingsError.set(''));
    this.roleForm.valueChanges.subscribe(() => this.roleError.set(''));
    // Each load of a sheet re-reads its settings from the store and starts the forms afresh.
    effect(() => {
      const sheet = this.session.loaded();
      this.settingsError.set('');
      this.roleError.set('');
      this.show({ clerkLimit: null, floor: (DEFAULT_FLOOR_CENTS / 100).toFixed(2), terms: DEFAULT_TERMS, latePct: DEFAULT_LATE_PCT, policyDate: null, roles: [], periodNumberingFrom: null, gradeTerms: {}, cap: null, proofThreshold: null, dated: {}, permissions: {} });
      this.settingsForm.markAsPristine();
      this.settingsForm.markAsUntouched();
      this.gradeTermsForm.markAsPristine();
      this.gradeTermsForm.markAsUntouched();
      this.gradeTermsFromForm.markAsPristine();
      this.gradeTermsFromForm.markAsUntouched();
      this.permissionsForm.markAsPristine();
      this.permissionsForm.markAsUntouched();
      if (sheet) void this.reload(sheet.id);
    });
  }

  private async reload(sheetId: string): Promise<void> {
    try {
      const settings = await this.api.get(sheetId);
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
    const { clerkLimit, clerkLimitFrom, floor, floorFrom, terms, termsFrom, latePct, latePctFrom, policyDate,
      periodNumberingFrom, cap, capFrom, proofThreshold, proofThresholdFrom } = this.settingsForm.controls;
    // A dated setting is saved when its value or its from-date was changed; the from-date checked first.
    const froms = [
      { label: 'clerk limit', control: clerkLimitFrom }, { label: 'floor', control: floorFrom },
      { label: 'terms', control: termsFrom }, { label: 'late percent', control: latePctFrom },
      { label: 'cap', control: capFrom }, { label: 'proof threshold', control: proofThresholdFrom },
      ...GRADES.map((g) => ({ label: `terms of grade ${g}`, control: this.gradeTermsFromForm.controls[g] })),
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
    const termsValue = terms.value.trim();
    const latePctValue = latePct.value.trim();
    const policyDateValue = policyDate.value.trim();
    const periodNumberingFromValue = periodNumberingFrom.value.trim();
    const capValue = cap.value.trim();
    const proofThresholdValue = proofThreshold.value.trim();
    if (dirty(clerkLimit, clerkLimitFrom) && limit !== '' && !MONEY.test(limit)) {
      this.settingsError.set('Type a clerk limit of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    if (dirty(floor, floorFrom) && !MONEY.test(floorValue)) {
      this.settingsError.set('Type a floor of 0.00 or more, with up to two decimals.');
      return;
    }
    if (dirty(terms, termsFrom) && (!DAYS.test(termsValue) || Number(termsValue) > 36500)) {
      this.settingsError.set('Type terms as a whole number of days, from 0 to 36500.');
      return;
    }
    const gradeTermsValues = GRADES.map((g) => ({ grade: g, control: this.gradeTermsForm.controls[g],
      from: this.gradeTermsFromForm.controls[g], value: this.gradeTermsForm.controls[g].value.trim() }));
    for (const { grade, control, from, value } of gradeTermsValues) {
      if (dirty(control, from) && value !== '' && (!DAYS.test(value) || Number(value) > 36500)) {
        this.settingsError.set(`Type the terms of grade ${grade} as a whole number of days, from 0 to 36500, or leave them blank for the sheet's terms.`);
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
    const permissionValues = PERMISSION_ACTIONS.map((a) => ({ action: a, control: this.permissionsForm.controls[a],
      value: this.permissionsForm.controls[a].value.split(',').map((r) => r.trim()).join(',') }));
    for (const { action, control, value } of permissionValues) {
      if (changed(control) && value !== '' && !value.split(',').every((r) => ROLES.includes(r as Role))) {
        this.settingsError.set(`Name who may ${action} as a comma separated list of clerk and steward, or leave it blank for the standing rule.`);
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
      if (dirty(floor, floorFrom)) {
        const settings = await this.api.setFloor(sheet.id, floorValue, fromOf(floorFrom));
        settled(floor);
        settled(floorFrom);
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

  /** Show the settings as the store answered them, and share the dated floor, late percent and cap, and
   *  the policy date, with the screens showing them. */
  private show(settings: SettingsView): void {
    this.dated.set(settings.dated);
    this.sheetFloor.history.set(settings.dated['floor'] ?? []);
    this.sheetLatePct.history.set(settings.dated['late-pct'] ?? []);
    this.sheetCap.history.set(settings.dated['cap'] ?? []);
    this.policyDate.set(settings.policyDate);
    this.sheetPolicyDate.date.set(settings.policyDate);
    this.periodNumberingFrom.set(settings.periodNumberingFrom);
    this.assigned.set(settings.roles);
    this.permissions.set(settings.permissions);
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
      this.roleError.set('Choose a role of clerk or steward.');
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
}
