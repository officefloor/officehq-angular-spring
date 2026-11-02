import { Component, effect, inject, signal } from '@angular/core';
import { AbstractControl, FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { Grade } from '../../core/people';
import { LATE_PCT, LIMIT, POLICY_DATE, ROLES, TERMS, Role, RoleName, SheetSettings } from '../../core/settings';

// The sheet's settings (the clerk limit, the cap, the floor, the terms, the late percent, the policy date and period numbering from) and the roles it gives user names: clerk, the default for
// any name, or steward.
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
        <label for="setting-cap">Cap on a person's due-total (blank: none)</label>
        <input id="setting-cap" data-testid="setting-cap" formControlName="cap" inputmode="decimal" />
        <label for="setting-floor">Floor</label>
        <input id="setting-floor" data-testid="setting-floor" formControlName="floor" inputmode="decimal" />
        <label for="setting-terms">Terms (days)</label>
        <input id="setting-terms" data-testid="setting-terms" formControlName="terms" inputmode="numeric" />
        <fieldset formGroupName="gradeTerms">
          <legend>Terms per grade (days; blank: the sheet's terms)</legend>
          @for (g of grades; track g) {
            <label [for]="'setting-terms-' + g">Grade {{ g }}</label>
            <input [id]="'setting-terms-' + g" [attr.data-testid]="'setting-terms-' + g" [formControlName]="g"
                   inputmode="numeric" />
          }
        </fieldset>
        <label for="setting-late-pct">Late percent (per 30 days overdue)</label>
        <input id="setting-late-pct" data-testid="setting-late-pct" formControlName="latePct" inputmode="decimal" />
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
    cap: new FormControl('', { nonNullable: true }),
    floor: new FormControl('', { nonNullable: true }),
    terms: new FormControl('', { nonNullable: true }),
    gradeTerms: new FormGroup({
      A: new FormControl('', { nonNullable: true }),
      B: new FormControl('', { nonNullable: true }),
      C: new FormControl('', { nonNullable: true }),
    }),
    latePct: new FormControl('', { nonNullable: true }),
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
    const { clerkLimit, cap, floor, terms, gradeTerms, latePct, policyDate, periodNumberingFrom } = this.settingsForm.controls;
    const changed = (c: AbstractControl) => c.dirty || c.touched;
    const none = !changed(clerkLimit) && !changed(cap) && !changed(floor) && !changed(terms) && !changed(gradeTerms)
      && !changed(latePct) && !changed(policyDate) && !changed(periodNumberingFrom);
    const limitValue = clerkLimit.value.trim();
    const capValue = cap.value.trim();
    const saveCap = changed(cap) || (none && capValue !== '');
    const floorValue = floor.value.trim();
    const saveLimit = none || changed(clerkLimit);
    const saveFloor = none || changed(floor);
    const termsValue = terms.value.trim();
    const saveTerms = none || changed(terms);
    const gradeTermsToSave = this.grades
      .map((g) => ({ grade: g, control: gradeTerms.controls[g], value: gradeTerms.controls[g].value.trim() }))
      .filter((t) => (none || changed(t.control)) && t.value !== '');
    const latePctValue = latePct.value.trim();
    const saveLatePct = none || changed(latePct);
    const policyDateValue = policyDate.value.trim();
    const savePolicyDate = none || changed(policyDate);
    const numberingValue = periodNumberingFrom.value.trim();
    const saveNumbering = none || changed(periodNumberingFrom);
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
        const saved = await this.api.setClerkLimit(limitValue === '' ? null : limitValue);
        this.clerkLimit.set(saved.clerkLimit);
        clerkLimit.markAsPristine();
        clerkLimit.markAsUntouched();
      }
      if (saveCap) {
        const saved = await this.api.setCap(capValue === '' ? null : capValue);
        this.cap.set(saved.cap);
        cap.markAsPristine();
        cap.markAsUntouched();
      }
      if (saveFloor && floorValue !== '') {
        const saved = await this.api.setFloor(floorValue);
        this.floor.set(saved.floor);
        floor.markAsPristine();
        floor.markAsUntouched();
      }
      if (saveTerms && termsValue !== '') {
        const saved = await this.api.setTerms(termsValue);
        this.terms.set(saved.terms);
        terms.markAsPristine();
        terms.markAsUntouched();
      }
      for (const t of gradeTermsToSave) {
        const saved = await this.api.setGradeTerms(t.grade, t.value);
        this.gradeTerms.set(saved.gradeTerms);
        t.control.markAsPristine();
        t.control.markAsUntouched();
      }
      if (saveLatePct && latePctValue !== '') {
        const saved = await this.api.setLatePct(latePctValue);
        this.latePct.set(saved.latePct);
        latePct.markAsPristine();
        latePct.markAsUntouched();
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
