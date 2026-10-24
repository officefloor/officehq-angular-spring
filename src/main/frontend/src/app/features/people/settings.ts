import { Component, effect, inject, signal } from '@angular/core';
import { Session } from '../../core/session';
import { ROLES, Role, RoleGiven, SettingsService } from './settings.service';

const LIMIT = /^\d{1,10}(\.\d{1,2})?$/;

// The sheet's settings: its clerk limit (the most a clerk may settle a claim for; blank for no
// limit) and the role each user name has been given (any other name is a clerk). Read from the
// store every time a sheet is loaded.
@Component({
  selector: 'app-settings',
  template: `
    <section aria-labelledby="settings-heading">
      <h2 id="settings-heading">Settings</h2>
      <form (submit)="saveSettings($event)" aria-label="Settings" data-testid="settings-form">
        <label for="setting-clerk-limit">Clerk limit</label>
        <input
          id="setting-clerk-limit"
          data-testid="setting-clerk-limit"
          inputmode="decimal"
          [value]="limit()"
          (input)="limit.set(value($event)); settingsError.set(null)"
        />
        <span>
          In force: <span data-testid="setting-clerk-limit-current">{{ clerkLimit() ?? '' }}</span>
          @if (clerkLimit() === null) {
            (no limit)
          }
        </span>
        <button type="submit" data-testid="settings-save">Save settings</button>
        @if (settingsError(); as message) {
          <p role="alert" class="error" data-testid="settings-error">{{ message }}</p>
        }
      </form>

      <form (submit)="saveRole($event)" aria-label="Role" data-testid="role-form">
        <label for="role-form-user">User</label>
        <input
          id="role-form-user"
          data-testid="role-form-user"
          [value]="roleUser()"
          (input)="roleUser.set(value($event)); roleError.set(null)"
        />
        <label for="role-form-role">Role</label>
        <input
          id="role-form-role"
          data-testid="role-form-role"
          list="role-form-roles"
          [value]="role()"
          (input)="role.set(value($event)); roleError.set(null)"
        />
        <datalist id="role-form-roles">
          @for (r of roles; track r) {
            <option [value]="r"></option>
          }
        </datalist>
        <button type="submit" data-testid="role-form-submit">Set role</button>
        @if (roleError(); as message) {
          <p role="alert" class="error" data-testid="role-form-error">{{ message }}</p>
        }
      </form>
      @if (given().length > 0) {
        <table data-testid="role-list">
          <caption>Roles (anyone else is a clerk)</caption>
          <thead>
            <tr>
              <th scope="col">User</th>
              <th scope="col">Role</th>
            </tr>
          </thead>
          <tbody>
            @for (r of given(); track r.user) {
              <tr [attr.data-testid]="'role-row-' + r.user">
                <td data-testid="role-user">{{ r.user }}</td>
                <td data-testid="role-value">{{ r.role }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
    </section>
  `,
  styles: `
    form {
      display: flex;
      flex-wrap: wrap;
      gap: 0.5rem;
      align-items: center;
      margin-bottom: 0.5rem;
    }
    .error {
      color: #a40000;
      margin: 0;
    }
    table {
      border-collapse: collapse;
      margin-bottom: 1rem;
    }
    caption {
      text-align: left;
      font-weight: bold;
    }
    th,
    td {
      text-align: left;
      padding: 0.25rem 0.75rem 0.25rem 0;
    }
  `,
})
export class Settings {
  private readonly session = inject(Session);
  private readonly service = inject(SettingsService);

  protected readonly roles = ROLES;
  /** The clerk limit in force; null when there is none. */
  protected readonly clerkLimit = signal<string | null>(null);
  protected readonly given = signal<RoleGiven[]>([]);

  protected readonly limit = signal('');
  protected readonly settingsError = signal<string | null>(null);
  protected readonly roleUser = signal('');
  protected readonly role = signal('');
  protected readonly roleError = signal<string | null>(null);
  /** A submit is waiting for its answer; another submit of the same form meanwhile is ignored. */
  private savingSettings = false;
  private savingRole = false;

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      this.session.loads();
      this.clerkLimit.set(null);
      this.given.set([]);
      this.limit.set('');
      this.settingsError.set(null);
      this.roleUser.set('');
      this.role.set('');
      this.roleError.set(null);
      if (sheet) void this.read(sheet);
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async saveSettings(event: Event): Promise<void> {
    event.preventDefault();
    if (this.savingSettings) return;
    this.settingsError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const limit = this.limit().trim();
    if (limit !== '' && !LIMIT.test(limit)) {
      this.settingsError.set('Type a clerk limit of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    this.savingSettings = true;
    try {
      const settings = await this.service.setClerkLimit(sheet, limit || null);
      if (this.session.sheetId() !== sheet) return;
      this.clerkLimit.set(settings.clerkLimit);
      this.given.set(settings.roles);
    } catch {
      this.settingsError.set('Those settings could not be saved.');
    } finally {
      this.savingSettings = false;
    }
  }

  protected async saveRole(event: Event): Promise<void> {
    event.preventDefault();
    if (this.savingRole) return;
    this.roleError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const user = this.roleUser().trim();
    const role = this.role().trim();
    if (user === '') {
      this.roleError.set('Type a user name.');
      return;
    }
    if (!ROLES.includes(role as Role)) {
      this.roleError.set(`Choose a role: ${ROLES.join(' or ')}.`);
      return;
    }
    this.savingRole = true;
    try {
      const saved = await this.service.setRole(sheet, user, role as Role);
      if (this.session.sheetId() !== sheet) return;
      this.given.update((list) =>
        [...list.filter((r) => r.user !== saved.user), saved].sort((a, b) => a.user.localeCompare(b.user)),
      );
      this.roleUser.set('');
      this.role.set('');
    } catch {
      this.roleError.set('That role could not be set.');
    } finally {
      this.savingRole = false;
    }
  }

  private async read(sheet: string): Promise<void> {
    try {
      const settings = await this.service.get(sheet);
      if (this.session.sheetId() === sheet) {
        this.clerkLimit.set(settings.clerkLimit);
        this.given.set(settings.roles);
      }
    } catch {
      // The settings could not be read; they show as none.
    }
  }
}
