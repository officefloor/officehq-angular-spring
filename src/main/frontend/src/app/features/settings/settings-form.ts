import { Component, effect, inject, signal } from '@angular/core';
import { AbstractControl, FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { Grade } from '../../core/people';
import { LATE_PCT, LIMIT, POLICY_DATE, ROLES, TERMS, Role, RoleName, SheetSettings } from '../../core/settings';

// The sheet's settings (the clerk limit, the cap, the floor, the terms, the late percent, the policy date and period numbering from) and the roles it gives user names: clerk, the default for
// any name, or steward. The settings that carry a figure are dated: each is saved from a from-date
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
        <label for="setting-floor">Floor</label>
        <input id="setting-floor" data-testid="setting-floor" formControlName="floor" inputmode="decimal" />
        <label for="setting-floor-from">Floor from (blank: today)</label>
        <input id="setting-floor-from" data-testid="setting-floor-from" type="date" formControlName="floorFrom" />
        <label for="setting-terms">Terms (days)</label>
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
        <label for="setting-late-pct">Late percent (per 30 days overdue)</label>
        <input id="setting-late-pct" data-testid="setting-late-pct" formControlName="latePct" inputmode="decimal" />
        <label for="setting-late-pct-from">Late percent from (blank: today)</label>
        <input id="setting-late-pct-from" data-testid="setting-late-pct-from" type="date" formControlName="latePctFrom" />
        <label for="setting-policy-date">Policy date (claims dated before it round half up; blank: today)</label>
        <input id="setting-policy-date" data-testid="setting-policy-date" type="date" formControlName="policyDate" />
        <label for="setting-period-numbering-from">Period numbering from (claims opened from it are numbered within their period; blank: off)</label>
        <input id="setting-period-numbering-from" data-testid="setting-period-numbering-from" type="date"
               formControlName="periodNumberingFrom" />
        <button type="submit" data-testid="settings-save">Save settings</button>
        @if (settingsError()) {
          <p role="alert" data-testid="settings-error">{{ settingsError() }}</p>
        }
      </form>
      <p>Clerk limit in force: <span data-testid="setting-clerk-limit-current">{{ clerkLimit() ?? '' }}</span></p>
      <p>Cap in force: <span data-testid="setting-cap-current">{{ cap() ?? '' }}</span></p>
      <p>Floor in force: <span data-testid="setting-floor-current">{{ floor() ?? '' }}</span></p>
      <p>Terms in force: <span data-testid="setting-terms-current">{{ terms() ?? '' }}</span></p>
      @for (g of grades; track g) {
        <p>Terms in force for grade {{ g }}:
          <span [attr.data-testid]="'setting-terms-' + g + '-current'">{{ termsOf(g) ?? '' }}</span></p>
      }
      <p>Late percent in force: <span data-testid="setting-late-pct-current">{{ latePct() ?? '' }}</span></p>
      <p>Policy date in force: <span data-testid="setting-policy-date-current">{{ policyDate() ?? '' }}</span></p>
      <p>Period numbering from: <span data-testid="setting-period-numbering-from-current">{{ periodNumberingFrom() ?? '' }}</span></p>
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

  protected readonly roleNames = ROLES;
  protected readonly clerkLimit = signal<string | null>(null);
  protected readonly cap = signal<string | null>(null);
  protected readonly floor = signal<string | null>(null);
  protected readonly terms = signal<number | null>(null);
  protected readonly grades: Grade[] = ['A', 'B', 'C'];
  /** The terms each grade has of its own; a grade not in it takes the sheet's terms. */
  protected readonly gradeTerms = signal<Partial<Record<Grade, number>>>({});
  protected readonly latePct = signal<string | null>(null);
  protected readonly policyDate = signal<string | null>(null);
  protected readonly periodNumberingFrom = signal<string | null>(null);
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
    floor: new FormControl('', { nonNullable: true }),
    floorFrom: new FormControl('', { nonNullable: true }),
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
    latePct: new FormControl('', { nonNullable: true }),
    latePctFrom: new FormControl('', { nonNullable: true }),
    policyDate: new FormControl('', { nonNullable: true }),
    periodNumberingFrom: new FormControl('', { nonNullable: true }),
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
      this.floor.set(null);
      this.terms.set(null);
      this.gradeTerms.set({});
      this.latePct.set(null);
      this.policyDate.set(null);
      this.periodNumberingFrom.set(null);
      this.roles.set([]);
      if (sheetId) void this.reload(sheetId);
    });
  }

  /** Save the settings changed since the last save (typed into, or entered and left, which an
   *  unchanged blank field may be); all of them when none was. */
  protected async saveSettings(): Promise<void> {
    if (this.submitting) return;
    this.settingsError.set('');
    const { clerkLimit, clerkLimitFrom, cap, capFrom, floor, floorFrom, terms, termsFrom, gradeTerms, latePct,
      latePctFrom, policyDate, periodNumberingFrom } = this.settingsForm.controls;
    const changed = (...cs: AbstractControl[]) => cs.some((c) => c.dirty || c.touched);
    const none = !changed(clerkLimit, clerkLimitFrom) && !changed(cap, capFrom) && !changed(floor, floorFrom)
      && !changed(terms, termsFrom) && !changed(gradeTerms) && !changed(latePct, latePctFrom) && !changed(policyDate)
      && !changed(periodNumberingFrom);
    /** A from-date typed, or null when blank (the sheet's today). */
    const from = (c: AbstractControl<string>) => c.value.trim() || null;
    const fromsToSave: AbstractControl<string>[] = [];
    const limitValue = clerkLimit.value.trim();
    const capValue = cap.value.trim();
    const saveCap = changed(cap, capFrom) || (none && capValue !== '');
    const floorValue = floor.value.trim();
    const saveLimit = none || changed(clerkLimit, clerkLimitFrom);
    const saveFloor = none || changed(floor, floorFrom);
    const termsValue = terms.value.trim();
    const saveTerms = none || changed(terms, termsFrom);
    const gradeTermsToSave = this.grades
      .map((g) => ({
        grade: g, control: gradeTerms.controls[g], fromControl: gradeTerms.controls[`${g}From`],
        value: gradeTerms.controls[g].value.trim(),
      }))
      .filter((t) => (none || changed(t.control, t.fromControl)) && t.value !== '');
    const latePctValue = latePct.value.trim();
    const saveLatePct = none || changed(latePct, latePctFrom);
    const policyDateValue = policyDate.value.trim();
    const savePolicyDate = none || changed(policyDate);
    const numberingValue = periodNumberingFrom.value.trim();
    const saveNumbering = none || changed(periodNumberingFrom);
    if (saveLimit) fromsToSave.push(clerkLimitFrom);
    if (saveCap) fromsToSave.push(capFrom);
    if (saveFloor && floorValue !== '') fromsToSave.push(floorFrom);
    if (saveTerms && termsValue !== '') fromsToSave.push(termsFrom);
    gradeTermsToSave.forEach((t) => fromsToSave.push(t.fromControl));
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
    if (saveFloor && !(none && floorValue === '') && !LIMIT.test(floorValue)) {
      this.settingsError.set('Enter a floor of 0.00 or more with up to two decimals.');
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
      if (saveFloor && floorValue !== '') {
        const saved = await this.api.setFloor(floorValue, from(floorFrom));
        this.floor.set(saved.floor);
        for (const c of [floor, floorFrom]) {
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
      this.roleError.set('Choose a role of clerk or steward.');
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
        this.floor.set(settings.floor);
        this.terms.set(settings.terms);
        this.gradeTerms.set(settings.gradeTerms);
        this.latePct.set(settings.latePct);
        this.policyDate.set(settings.policyDate);
        this.periodNumberingFrom.set(settings.periodNumberingFrom);
        this.roles.set(roles);
      }
    } catch {
      // the settings stay blank; the next load reads them again
    }
  }
}
