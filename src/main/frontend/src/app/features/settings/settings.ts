import { Component, inject, signal } from '@angular/core';
import { ROLES, Roles } from '../../core/roles';
import { Session } from '../../core/session';

// The sheet's settings: the clerk limit (the largest due a clerk may settle; blank for no limit), and
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
          (input)="limit.set(value($event)); error.set(null)"
        />
        <p>In force: <strong data-testid="setting-clerk-limit-current">{{ session.clerkLimit() ?? '' }}</strong></p>
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
      const err = await this.session.setClerkLimit(this.limit());
      if (err) this.error.set(err);
      else this.limit.set('');
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
