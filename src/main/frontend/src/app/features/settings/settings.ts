import { Component, effect, inject, signal, untracked } from '@angular/core';
import { FLOOR, LATE_PCT, LIMIT, PERIOD_NUMBERING_FROM, POLICY_DATE, ROLES, TERMS, RoleName, RoleView, SettingsApi } from '../../core/settings';
import { Session } from '../../core/session';

// The sheet's settings (the clerk limit, the floor, the terms, the late percent, the policy date and period numbering from) and the roles user names have on it (clerk by default,
// or steward), each changed through its own form.
@Component({
  selector: 'app-settings',
  template: `
    <section aria-labelledby="settings-heading">
      <h2 id="settings-heading">Settings</h2>
      <form (submit)="saveSettings($event)" aria-label="Change settings">
        <label for="setting-clerk-limit">Clerk limit (empty: no limit)</label>
        <input
          id="setting-clerk-limit"
          data-testid="setting-clerk-limit"
          inputmode="decimal"
          [value]="limit()"
          (input)="limit.set(value($event)); limitEdited = true; settingsError.set(null)"
        />
        <label for="setting-floor">Floor</label>
        <input
          id="setting-floor"
          data-testid="setting-floor"
          inputmode="decimal"
          [value]="floorInput()"
          (input)="floorInput.set(value($event)); floorEdited = true; settingsError.set(null)"
        />
        <label for="setting-terms">Terms (days)</label>
        <input
          id="setting-terms"
          data-testid="setting-terms"
          inputmode="numeric"
          [value]="termsInput()"
          (input)="termsInput.set(value($event)); termsEdited = true; settingsError.set(null)"
        />
        <label for="setting-late-pct">Late percent (per full 30 days overdue)</label>
        <input
          id="setting-late-pct"
          data-testid="setting-late-pct"
          inputmode="decimal"
          [value]="latePctInput()"
          (input)="latePctInput.set(value($event)); latePctEdited = true; settingsError.set(null)"
        />
        <label for="setting-policy-date">Policy date (yyyy-mm-dd; empty: the sheet's today)</label>
        <input
          id="setting-policy-date"
          data-testid="setting-policy-date"
          [value]="policyDateInput()"
          (input)="policyDateInput.set(value($event)); policyDateEdited = true; settingsError.set(null)"
        />
        <label for="setting-period-numbering-from">Period numbering from (yyyy-mm-dd; empty: off)</label>
        <input
          id="setting-period-numbering-from"
          data-testid="setting-period-numbering-from"
          [value]="periodNumberingFromInput()"
          (input)="periodNumberingFromInput.set(value($event)); periodNumberingFromEdited = true; settingsError.set(null)"
        />
        <button type="submit" data-testid="settings-save">Save settings</button>
        @if (settingsError(); as message) {
          <p role="alert" data-testid="settings-error">{{ message }}</p>
        }
      </form>
      <p>Clerk limit in force: <span data-testid="setting-clerk-limit-current">{{ clerkLimit() ?? '' }}</span></p>
      <p>Floor in force: <span data-testid="setting-floor-current">{{ session.floor() }}</span></p>
      <p>Terms in force: <span data-testid="setting-terms-current">{{ session.terms() }}</span></p>
      <p>Late percent in force: <span data-testid="setting-late-pct-current">{{ session.latePct() }}</span></p>
      <p>Policy date in force: <span data-testid="setting-policy-date-current">{{ session.policyDate() }}</span></p>
      <p>Period numbering from in force: <span data-testid="setting-period-numbering-from-current">{{ periodNumberingFrom() }}</span></p>
      <h3>Roles</h3>
      <form (submit)="saveRole($event)" aria-label="Set a role">
        <label for="role-form-user">User</label>
        <input
          id="role-form-user"
          data-testid="role-form-user"
          [value]="roleUser()"
          (input)="roleUser.set(value($event)); roleError.set(null)"
        />
        <label for="role-form-role">Role ({{ roleNames }})</label>
        <input
          id="role-form-role"
          data-testid="role-form-role"
          [value]="roleName()"
          (input)="roleName.set(value($event)); roleError.set(null)"
        />
        <button type="submit" data-testid="role-form-submit">Set role</button>
        @if (roleError(); as message) {
          <p role="alert" data-testid="role-form-error">{{ message }}</p>
        }
      </form>
      <table>
        <caption>Roles set (any other name is a clerk)</caption>
        <thead>
          <tr>
            <th scope="col">User</th>
            <th scope="col">Role</th>
          </tr>
        </thead>
        <tbody>
          @for (r of roles(); track r.user) {
            <tr [attr.data-testid]="'role-row-' + r.user">
              <th scope="row">{{ r.user }}</th>
              <td data-testid="role-value">{{ r.role }}</td>
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

  protected readonly roleNames = ROLES.join(' or ');
  protected readonly clerkLimit = signal<string | null>(null);
  protected readonly roles = signal<RoleView[]>([]);
  protected readonly limit = signal('');
  protected readonly floorInput = signal('');
  protected readonly termsInput = signal('');
  protected readonly latePctInput = signal('');
  protected readonly policyDateInput = signal('');
  protected readonly periodNumberingFrom = signal('');
  protected readonly periodNumberingFromInput = signal('');
  /** Which settings were typed into since the last save: a save changes only those. */
  protected limitEdited = false;
  protected floorEdited = false;
  protected termsEdited = false;
  protected latePctEdited = false;
  protected policyDateEdited = false;
  protected periodNumberingFromEdited = false;
  protected readonly roleUser = signal('');
  protected readonly roleName = signal('');
  protected readonly settingsError = signal<string | null>(null);
  protected readonly roleError = signal<string | null>(null);
  /** A submit is waiting for its answer: a second submit meanwhile is ignored. */
  private busy = false;
  /** The settings saves in flight: a save submitted meanwhile is written after them, in turn. */
  private saving: Promise<void> = Promise.resolve();

  constructor() {
    // Every sheet load re-reads the sheet's settings and roles from the store.
    effect(() => {
      const sheet = this.session.sheet();
      const loads = this.session.loads();
      untracked(() => {
        this.clerkLimit.set(null);
        this.periodNumberingFrom.set('');
        this.roles.set([]);
        this.settingsError.set(null);
        this.roleError.set(null);
        if (sheet) void this.reload(sheet, loads);
      });
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async saveSettings(event: Event): Promise<void> {
    event.preventDefault();
    this.settingsError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const limit = this.limit().trim();
    const floor = this.floorInput().trim();
    // With nothing typed since the last save, saving sets the clerk limit again as it stands.
    const terms = this.termsInput().trim();
    const latePct = this.latePctInput().trim();
    const policyDate = this.policyDateInput().trim();
    const periodNumberingFrom = this.periodNumberingFromInput().trim();
    const saveLimit =
      this.limitEdited ||
      (!this.floorEdited && !this.termsEdited && !this.latePctEdited && !this.policyDateEdited &&
        !this.periodNumberingFromEdited);
    const saveFloor = this.floorEdited;
    const saveTerms = this.termsEdited;
    const saveLatePct = this.latePctEdited;
    const savePolicyDate = this.policyDateEdited;
    const savePeriodNumberingFrom = this.periodNumberingFromEdited;
    if (saveLimit && !LIMIT.test(limit)) {
      this.settingsError.set('Type a clerk limit of 0.00 or more, with up to two decimals, or leave it empty for no limit.');
      return;
    }
    if (saveFloor && !FLOOR.test(floor)) {
      this.settingsError.set('Type a floor of 0.00 or more, with up to two decimals.');
      return;
    }
    if (saveTerms && !TERMS.test(terms)) {
      this.settingsError.set('Type terms as a whole number of days.');
      return;
    }
    if (saveLatePct && !LATE_PCT.test(latePct)) {
      this.settingsError.set('Type a late percent of 0.00 or more, with up to two decimals.');
      return;
    }
    if (savePolicyDate && !POLICY_DATE.test(policyDate)) {
      this.settingsError.set('Type a policy date as yyyy-mm-dd, or leave it empty for the sheet\'s today.');
      return;
    }
    if (savePeriodNumberingFrom && !PERIOD_NUMBERING_FROM.test(periodNumberingFrom)) {
      this.settingsError.set('Type the date period numbering is from as yyyy-mm-dd, or leave it empty for off.');
      return;
    }
    // What was typed is taken now: anything typed meanwhile is saved by the next submit.
    if (saveLimit) this.limitEdited = false;
    if (saveFloor) this.floorEdited = false;
    if (saveTerms) this.termsEdited = false;
    if (saveLatePct) this.latePctEdited = false;
    if (savePolicyDate) this.policyDateEdited = false;
    if (savePeriodNumberingFrom) this.periodNumberingFromEdited = false;
    const write = async () => {
      try {
        if (saveLimit) {
          const view = await this.api.setClerkLimit(sheet, limit);
          this.clerkLimit.set(view.clerkLimit);
        }
        if (saveFloor) {
          const view = await this.api.setFloor(sheet, floor);
          this.session.floor.set(view.floor);
        }
        if (saveTerms) {
          const view = await this.api.setTerms(sheet, terms);
          this.session.terms.set(view.terms);
        }
        if (saveLatePct) {
          const view = await this.api.setLatePct(sheet, latePct);
          this.session.latePct.set(view.latePct);
        }
        if (savePolicyDate) {
          const view = await this.api.setPolicyDate(sheet, policyDate);
          this.session.policyDate.set(view.policyDate ?? '');
          if (!this.policyDateEdited) this.policyDateInput.set(view.policyDate ?? '');
        }
        if (savePeriodNumberingFrom) {
          const view = await this.api.setPeriodNumberingFrom(sheet, periodNumberingFrom);
          this.periodNumberingFrom.set(view.periodNumberingFrom ?? '');
          if (!this.periodNumberingFromEdited) this.periodNumberingFromInput.set(view.periodNumberingFrom ?? '');
        }
      } catch {
        this.settingsError.set('The settings could not be saved.');
      }
    };
    this.saving = this.saving.then(write);
    await this.saving;
  }

  protected async saveRole(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.roleError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const user = this.roleUser().trim();
    const role = this.roleName().trim();
    if (!user) {
      this.roleError.set('Type the user name to set a role for.');
      return;
    }
    if (!(ROLES as readonly string[]).includes(role)) {
      this.roleError.set(`A role is ${this.roleNames}.`);
      return;
    }
    this.busy = true;
    try {
      const saved = await this.api.setRole(sheet, { user, role: role as RoleName });
      this.roles.update((all) =>
        [...all.filter((r) => r.user !== saved.user), saved].sort((a, b) => a.user.localeCompare(b.user)),
      );
      this.roleUser.set('');
      this.roleName.set('');
    } catch {
      this.roleError.set(`The role of ${user} could not be set.`);
    } finally {
      this.busy = false;
    }
  }

  private async reload(sheet: string, loads: number): Promise<void> {
    const [settings, roles] = await Promise.all([this.api.get(sheet), this.api.roles(sheet)]);
    // A later load may have started while this one was in flight: only the latest is shown.
    if (this.session.sheet() === sheet && this.session.loads() === loads) {
      this.clerkLimit.set(settings.clerkLimit);
      this.session.floor.set(settings.floor);
      this.session.terms.set(settings.terms);
      this.session.latePct.set(settings.latePct);
      this.session.policyDate.set(settings.policyDate ?? '');
      // The policy date's input starts at the date in force, so clearing it is a change.
      this.policyDateInput.set(settings.policyDate ?? '');
      this.policyDateEdited = false;
      // Period numbering from starts at the date in force too, so clearing it is a change.
      this.periodNumberingFrom.set(settings.periodNumberingFrom ?? '');
      this.periodNumberingFromInput.set(settings.periodNumberingFrom ?? '');
      this.periodNumberingFromEdited = false;
      this.roles.set(roles);
    }
  }
}
