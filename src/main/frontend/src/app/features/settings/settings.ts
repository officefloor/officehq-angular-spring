import { Component, effect, inject, signal, untracked } from '@angular/core';
import { LIMIT, ROLES, RoleName, RoleView, SettingsApi } from '../../core/settings';
import { Session } from '../../core/session';

// The sheet's settings (the clerk limit) and the roles user names have on it (clerk by default,
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
          (input)="limit.set(value($event)); settingsError.set(null)"
        />
        <button type="submit" data-testid="settings-save">Save settings</button>
        @if (settingsError(); as message) {
          <p role="alert" data-testid="settings-error">{{ message }}</p>
        }
      </form>
      <p>Clerk limit in force: <span data-testid="setting-clerk-limit-current">{{ clerkLimit() ?? '' }}</span></p>
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
  private readonly session = inject(Session);
  private readonly api = inject(SettingsApi);

  protected readonly roleNames = ROLES.join(' or ');
  protected readonly clerkLimit = signal<string | null>(null);
  protected readonly roles = signal<RoleView[]>([]);
  protected readonly limit = signal('');
  protected readonly roleUser = signal('');
  protected readonly roleName = signal('');
  protected readonly settingsError = signal<string | null>(null);
  protected readonly roleError = signal<string | null>(null);
  /** A submit is waiting for its answer: a second submit meanwhile is ignored. */
  private busy = false;

  constructor() {
    // Every sheet load re-reads the sheet's settings and roles from the store.
    effect(() => {
      const sheet = this.session.sheet();
      const loads = this.session.loads();
      untracked(() => {
        this.clerkLimit.set(null);
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
    if (this.busy) return;
    this.settingsError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const limit = this.limit().trim();
    if (!LIMIT.test(limit)) {
      this.settingsError.set('Type a clerk limit of 0.00 or more, with up to two decimals, or leave it empty for no limit.');
      return;
    }
    this.busy = true;
    try {
      const view = await this.api.setClerkLimit(sheet, limit);
      this.clerkLimit.set(view.clerkLimit);
    } catch {
      this.settingsError.set('The settings could not be saved.');
    } finally {
      this.busy = false;
    }
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
      this.roles.set(roles);
    }
  }
}
