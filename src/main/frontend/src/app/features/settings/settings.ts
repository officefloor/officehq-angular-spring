import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { LATE_PCT_PATTERN, LIMIT_PATTERN, POLICY_DATE_PATTERN, ROLES, TERMS_PATTERN, RoleGrant, SETTINGS_INVALID, SettingsApi, roleError, settingsError } from './settings-api';

// The sheet's settings (its clerk limit: a clerk settles only claims whose due is at or below it;
// its floor: a claim's live due is at least it; its terms: a claim is due by its date plus them; its late percent: an overdue claim's due gains it for every full 30 days overdue; its policy date: a claim dated before it rounds half up, one on or after it half to even; its period numbering from-date: from it a claim opened is numbered within its period) with the form that saves the ones changed in it, and the role each user name holds with the form that sets one.
@Component({
  selector: 'app-settings',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="settings-heading">
      <h2 id="settings-heading">Settings</h2>
      <p>Clerk limit in force <strong data-testid="setting-clerk-limit-current">{{ clerkLimit() ?? '' }}</strong></p>
      <p>Floor in force <strong data-testid="setting-floor-current">{{ session.floor() }}</strong></p>
      <p>Terms in force (days) <strong data-testid="setting-terms-current">{{ session.terms() }}</strong></p>
      <p>Late percent in force <strong data-testid="setting-late-pct-current">{{ session.latePct() }}</strong></p>
      <p>Policy date in force (blank: the sheet's today) <strong data-testid="setting-policy-date-current">{{ session.policyDate() ?? '' }}</strong></p>
      <p>Period numbering from (blank: off) <strong data-testid="setting-period-numbering-from-current">{{ periodNumberingFrom() ?? '' }}</strong></p>
      <form [formGroup]="settingsForm" (ngSubmit)="saveSettings()" aria-label="Settings">
        <label for="setting-clerk-limit">Clerk limit (blank: no limit)</label>
        <input id="setting-clerk-limit" data-testid="setting-clerk-limit" formControlName="clerkLimit" inputmode="decimal" autocomplete="off" />
        <label for="setting-floor">Floor (blank: 1.00)</label>
        <input id="setting-floor" data-testid="setting-floor" formControlName="floor" inputmode="decimal" autocomplete="off" />
        <label for="setting-terms">Terms in days (blank: 14)</label>
        <input id="setting-terms" data-testid="setting-terms" formControlName="terms" inputmode="numeric" autocomplete="off" />
        <label for="setting-late-pct">Late percent per 30 days overdue (blank: 0.00)</label>
        <input id="setting-late-pct" data-testid="setting-late-pct" formControlName="latePct" inputmode="decimal" autocomplete="off" />
        <label for="setting-policy-date">Policy date: claims dated before it round half up, others half to even (blank: the sheet's today)</label>
        <input id="setting-policy-date" type="date" data-testid="setting-policy-date" formControlName="policyDate" />
        <label for="setting-period-numbering-from">Period numbering from: claims opened from it are numbered within their period (blank: off)</label>
        <input id="setting-period-numbering-from" type="date" data-testid="setting-period-numbering-from" formControlName="periodNumberingFrom" />
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
    </section>
  `,
})
export class Settings {
  protected readonly session = inject(Session);
  private readonly api = inject(SettingsApi);

  protected readonly roles = ROLES;
  protected readonly roleNames = ROLES.join(' or ');
  /** The sheet's clerk limit in force (two decimals); null when there is none. */
  protected readonly clerkLimit = signal<string | null>(null);
  /** The sheet's period numbering from-date (yyyy-mm-dd); null when blank (off). */
  protected readonly periodNumberingFrom = signal<string | null>(null);
  /** The roles set on the sheet. */
  protected readonly grants = signal<RoleGrant[]>([]);
  protected readonly sortedGrants = computed(() => [...this.grants()].sort((a, b) => a.user.localeCompare(b.user)));
  protected readonly settingsErr = signal<string | null>(null);
  protected readonly roleErr = signal<string | null>(null);
  /** Whether a save is waiting for its answer; a second one meanwhile runs once it is answered. */
  private savingSettings = false;
  /** Whether a save was submitted while another was waiting; it runs once that one is answered. */
  private resaveSettings = false;
  private savingRole = false;
  /** Guards against a slower, older read landing over a newer one. */
  private readSeq = 0;

  protected readonly settingsForm = new FormGroup({
    clerkLimit: new FormControl('', { nonNullable: true }),
    floor: new FormControl('', { nonNullable: true }),
    terms: new FormControl('', { nonNullable: true }),
    latePct: new FormControl('', { nonNullable: true }),
    policyDate: new FormControl('', { nonNullable: true }),
    periodNumberingFrom: new FormControl('', { nonNullable: true }),
  });
  protected readonly roleForm = new FormGroup({
    user: new FormControl('', { nonNullable: true }),
    role: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.settingsForm.valueChanges.subscribe(() => this.settingsErr.set(null));
    this.roleForm.valueChanges.subscribe(() => this.roleErr.set(null));
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.clerkLimit.set(null);
        this.periodNumberingFrom.set(null);
        this.grants.set([]);
        this.settingsErr.set(null);
        this.roleErr.set(null);
        this.settingsForm.reset({ clerkLimit: '', floor: '', terms: '', latePct: '', policyDate: '', periodNumberingFrom: '' }, { emitEvent: false });
        this.roleForm.reset({ user: '', role: '' }, { emitEvent: false });
        if (id) void this.read(id);
      });
    });
  }

  protected async saveSettings(): Promise<void> {
    if (this.savingSettings) {
      this.resaveSettings = true;
      return;
    }
    this.settingsErr.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) return;
    const { clerkLimit, floor, terms, latePct, policyDate, periodNumberingFrom } = this.settingsForm.controls;
    // only the settings typed into since the last save are saved (the clerk limit when none is)
    const saveFloor = floor.dirty;
    const saveTerms = terms.dirty;
    const saveLatePct = latePct.dirty;
    const savePolicyDate = policyDate.dirty;
    const savePeriodNumberingFrom = periodNumberingFrom.dirty;
    const saveLimit = clerkLimit.dirty || !(saveFloor || saveTerms || saveLatePct || savePolicyDate || savePeriodNumberingFrom);
    const limitValue = clerkLimit.value.trim();
    const floorValue = floor.value.trim();
    const termsValue = terms.value.trim();
    const latePctValue = latePct.value.trim();
    const policyDateValue = policyDate.value.trim();
    const periodNumberingFromValue = periodNumberingFrom.value.trim();
    if ((saveLimit && limitValue !== '' && !LIMIT_PATTERN.test(limitValue))
        || (saveFloor && floorValue !== '' && !LIMIT_PATTERN.test(floorValue))
        || (saveTerms && termsValue !== '' && !TERMS_PATTERN.test(termsValue))
        || (saveLatePct && latePctValue !== '' && !LATE_PCT_PATTERN.test(latePctValue))
        || (savePolicyDate && policyDateValue !== '' && !POLICY_DATE_PATTERN.test(policyDateValue))
        || (savePeriodNumberingFrom && periodNumberingFromValue !== '' && !POLICY_DATE_PATTERN.test(periodNumberingFromValue))) {
      this.settingsErr.set(SETTINGS_INVALID);
      return;
    }
    this.savingSettings = true;
    try {
      if (saveLimit) {
        const saved = await this.api.setClerkLimit(sheetId, limitValue);
        if (clerkLimit.value.trim() === limitValue) clerkLimit.markAsPristine();
        if (this.session.sheetId() === sheetId) this.clerkLimit.set(saved.clerkLimit);
      }
      if (saveFloor) {
        const saved = await this.api.setFloor(sheetId, floorValue);
        if (floor.value.trim() === floorValue) floor.markAsPristine();
        if (this.session.sheetId() === sheetId) this.session.floor.set(saved.floor);
      }
      if (saveTerms) {
        const saved = await this.api.setTerms(sheetId, termsValue);
        if (terms.value.trim() === termsValue) terms.markAsPristine();
        if (this.session.sheetId() === sheetId) this.session.terms.set(saved.terms);
      }
      if (saveLatePct) {
        const saved = await this.api.setLatePct(sheetId, latePctValue);
        if (latePct.value.trim() === latePctValue) latePct.markAsPristine();
        if (this.session.sheetId() === sheetId) this.session.latePct.set(saved.latePct);
      }
      if (savePolicyDate) {
        const saved = await this.api.setPolicyDate(sheetId, policyDateValue);
        if (policyDate.value.trim() === policyDateValue) policyDate.markAsPristine();
        if (this.session.sheetId() === sheetId) this.session.policyDate.set(saved.policyDate);
      }
      if (savePeriodNumberingFrom) {
        const saved = await this.api.setPeriodNumberingFrom(sheetId, periodNumberingFromValue);
        if (periodNumberingFrom.value.trim() === periodNumberingFromValue) periodNumberingFrom.markAsPristine();
        if (this.session.sheetId() === sheetId) this.periodNumberingFrom.set(saved.periodNumberingFrom);
      }
    } catch (e) {
      this.settingsErr.set(settingsError(e));
    } finally {
      this.savingSettings = false;
    }
    if (this.resaveSettings) {
      this.resaveSettings = false;
      if (this.settingsForm.dirty) await this.saveSettings();
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

  private async read(sheetId: string): Promise<void> {
    const seq = ++this.readSeq;
    try {
      const [settings, grants] = await Promise.all([this.api.get(sheetId), this.api.roles(sheetId)]);
      if (seq === this.readSeq) {
        this.clerkLimit.set(settings.clerkLimit);
        this.session.floor.set(settings.floor);
        this.session.terms.set(settings.terms);
        this.session.latePct.set(settings.latePct);
        this.session.policyDate.set(settings.policyDate);
        this.periodNumberingFrom.set(settings.periodNumberingFrom);
        this.grants.set(grants);
      }
    } catch {
      if (seq === this.readSeq) this.settingsErr.set('Something went wrong loading settings; try again.');
    }
  }
}
