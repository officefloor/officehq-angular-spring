import { Component, inject, signal } from '@angular/core';
import { ROLES, Roles } from '../../core/roles';
import { Session } from '../../core/session';

// The sheet's settings: the clerk limit (the largest due a clerk may settle; blank for no limit), the
// floor (the least a claim's live due can be), the terms (the days after a claim's date it is due by), the
// late percent (what an overdue claim's due gains of its figure for every full 30 days), the policy
// date (a claim dated before it rounds half up, on or after it half to even; blank for the sheet's today), the
// period numbering from-date (while the sheet's today is on or after it, a claim opened is numbered within its
// period; blank for off), and
// the role each user name has on the sheet (clerk unless given another).
@Component({
  selector: 'app-settings',
  template: `
    <section aria-label="Settings" data-testid="settings">
      <h2>Settings</h2>
      <form (submit)="save($event)" data-testid="settings-form">
        <label for="setting-clerk-limit">Clerk limit (blank for none)</label>
        <input
          id="setting-clerk-limit"
          data-testid="setting-clerk-limit"
          inputmode="decimal"
          [value]="limit()"
          (input)="limit.set(value($event)); limitTouched.set(true); error.set(null)"
        />
        <p>In force: <strong data-testid="setting-clerk-limit-current">{{ session.clerkLimit() ?? '' }}</strong></p>
        <label for="setting-floor">Floor (the least a live due can be)</label>
        <input
          id="setting-floor"
          data-testid="setting-floor"
          inputmode="decimal"
          [value]="floor()"
          (input)="floor.set(value($event)); floorTouched.set(true); error.set(null)"
        />
        <p>In force: <strong data-testid="setting-floor-current">{{ session.floor() }}</strong></p>
        <label for="setting-terms">Terms (days after a claim's date it is due by)</label>
        <input
          id="setting-terms"
          data-testid="setting-terms"
          inputmode="numeric"
          [value]="terms()"
          (input)="terms.set(value($event)); termsTouched.set(true); error.set(null)"
        />
        <p>In force: <strong data-testid="setting-terms-current">{{ session.terms() }}</strong></p>
        <label for="setting-late-pct">Late percent (of an overdue claim's figure, per full 30 days)</label>
        <input
          id="setting-late-pct"
          data-testid="setting-late-pct"
          inputmode="decimal"
          [value]="latePct()"
          (input)="latePct.set(value($event)); latePctTouched.set(true); error.set(null)"
        />
        <p>In force: <strong data-testid="setting-late-pct-current">{{ session.latePct() }}</strong></p>
        <label for="setting-policy-date">Policy date (YYYY-MM-DD; blank for the sheet's today)</label>
        <input
          id="setting-policy-date"
          data-testid="setting-policy-date"
          [value]="policyDate()"
          (input)="policyDate.set(value($event)); policyDateTouched.set(true); error.set(null)"
        />
        <p>In force: <strong data-testid="setting-policy-date-current">{{ session.policyDate() ?? '' }}</strong></p>
        <label for="setting-period-numbering-from">Period numbering from (YYYY-MM-DD; blank for off)</label>
        <input
          id="setting-period-numbering-from"
          data-testid="setting-period-numbering-from"
          [value]="numberingFrom()"
          (input)="numberingFrom.set(value($event)); numberingFromTouched.set(true); error.set(null)"
        />
        <p>In force: <strong data-testid="setting-period-numbering-from-current">{{ session.periodNumberingFrom() ?? '' }}</strong></p>
        <button type="submit" data-testid="settings-save" [disabled]="saving()">Save settings</button>
        @if (error(); as err) {
          <p role="alert" data-testid="settings-error">{{ err }}</p>
        }
      </form>
      <h3>Roles</h3>
      <form (submit)="setRole($event)" data-testid="role-form">
        <label for="role-form-user">User name</label>
        <input
          id="role-form-user"
          data-testid="role-form-user"
          [value]="user()"
          (input)="user.set(value($event)); roleError.set(null)"
        />
        <label for="role-form-role">Role ({{ roleNames }})</label>
        <input
          id="role-form-role"
          data-testid="role-form-role"
          [value]="role()"
          (input)="role.set(value($event)); roleError.set(null)"
        />
        <button type="submit" data-testid="role-form-submit" [disabled]="settingRole()">Set role</button>
        @if (roleError(); as err) {
          <p role="alert" data-testid="role-form-error">{{ err }}</p>
        }
      </form>
      <table data-testid="role-list">
        <caption>Roles (anyone else is a clerk)</caption>
        <thead>
          <tr><th scope="col">User</th><th scope="col">Role</th></tr>
        </thead>
        <tbody>
          @for (r of roles.roles(); track r.user) {
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
export class SettingsPanel {
  protected readonly session = inject(Session);
  protected readonly roles = inject(Roles);
  protected readonly roleNames = ROLES.join(' or ');
  protected readonly limit = signal('');
  protected readonly limitTouched = signal(false);
  protected readonly floor = signal('');
  protected readonly floorTouched = signal(false);
  protected readonly terms = signal('');
  protected readonly termsTouched = signal(false);
  protected readonly latePct = signal('');
  protected readonly latePctTouched = signal(false);
  protected readonly policyDate = signal('');
  protected readonly policyDateTouched = signal(false);
  protected readonly numberingFrom = signal('');
  protected readonly numberingFromTouched = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly user = signal('');
  protected readonly role = signal('');
  protected readonly roleError = signal<string | null>(null);
  protected readonly settingRole = signal(false);

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async save(e: Event): Promise<void> {
    e.preventDefault();
    if (this.saving()) return; // a second submit before the first has answered
    this.error.set(null);
    this.saving.set(true);
    try {
      // Only the settings typed into since the last save change. With none, the settings that may be blank
      // are saved where they differ from the value in force (a blank field clears one), else the clerk limit.
      const untouched = !this.limitTouched() && !this.floorTouched() && !this.termsTouched()
        && !this.latePctTouched() && !this.policyDateTouched() && !this.numberingFromTouched();
      const floor = this.floorTouched();
      const terms = this.termsTouched();
      const latePct = this.latePctTouched();
      const policyDate = this.policyDateTouched()
        || (untouched && this.policyDate().trim() !== (this.session.policyDate() ?? ''));
      const numberingFrom = this.numberingFromTouched()
        || (untouched && this.numberingFrom().trim() !== (this.session.periodNumberingFrom() ?? ''));
      const limit = this.limitTouched() || (untouched && !policyDate && !numberingFrom)
        || (untouched && this.limit().trim() !== (this.session.clerkLimit() ?? ''));
      const err = (limit ? await this.session.setClerkLimit(this.limit()) : null)
        ?? (floor ? await this.session.setFloor(this.floor()) : null)
        ?? (terms ? await this.session.setTerms(this.terms()) : null)
        ?? (latePct ? await this.session.setLatePct(this.latePct()) : null)
        ?? (policyDate ? await this.session.setPolicyDate(this.policyDate()) : null)
        ?? (numberingFrom ? await this.session.setPeriodNumberingFrom(this.numberingFrom()) : null);
      if (err) this.error.set(err);
      else {
        this.limit.set('');
        this.limitTouched.set(false);
        this.floor.set('');
        this.floorTouched.set(false);
        this.terms.set('');
        this.termsTouched.set(false);
        this.latePct.set('');
        this.latePctTouched.set(false);
        this.policyDate.set('');
        this.policyDateTouched.set(false);
        this.numberingFrom.set('');
        this.numberingFromTouched.set(false);
      }
    } finally {
      this.saving.set(false);
    }
  }

  protected async setRole(e: Event): Promise<void> {
    e.preventDefault();
    if (this.settingRole()) return; // a second submit before the first has answered
    this.roleError.set(null);
    this.settingRole.set(true);
    try {
      const err = await this.roles.set(this.user(), this.role());
      if (err) this.roleError.set(err);
      else {
        this.user.set('');
        this.role.set('');
      }
    } finally {
      this.settingRole.set(false);
    }
  }
}
