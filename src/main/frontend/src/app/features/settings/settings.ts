import { Component, computed, effect, inject, linkedSignal, signal, untracked } from '@angular/core';
import { AMOUNT } from '../../core/claims';
import { ROLES, RoleName, Roles } from '../../core/roles';
import { Session } from '../../core/session';

// The sheet's settings (the clerk limit and the floor) and the roles given to user names.
@Component({
  selector: 'app-settings',
  template: `
    <section aria-labelledby="settings-heading">
      <h2 id="settings-heading">Settings</h2>

      <p>Clerk limit <strong data-testid="setting-clerk-limit-current">{{ session.clerkLimit() ?? '' }}</strong></p>
      <form (submit)="saveSettings($event)">
        <label for="setting-clerk-limit">Clerk limit (blank for no limit)</label>
        <input
          id="setting-clerk-limit"
          inputmode="decimal"
          data-testid="setting-clerk-limit"
          [value]="clerkLimit()"
          (input)="clerkLimit.set($any($event.target).value); settingsError.set(null)"
        />
        <p>Floor <strong data-testid="setting-floor-current">{{ session.floor() ?? '' }}</strong></p>
        <label for="setting-floor">Floor (the least any live due can be)</label>
        <input
          id="setting-floor"
          inputmode="decimal"
          data-testid="setting-floor"
          [value]="floor()"
          (input)="floor.set($any($event.target).value); settingsError.set(null)"
        />
        <button type="submit" data-testid="settings-save">Save settings</button>
        @if (settingsError(); as err) {
          <p role="alert" data-testid="settings-error">{{ err }}</p>
        }
      </form>

      <h3>Roles</h3>
      <form (submit)="saveRole($event)">
        <label for="role-user">User name</label>
        <input
          id="role-user"
          data-testid="role-form-user"
          [value]="user()"
          (input)="user.set($any($event.target).value); roleError.set(null)"
        />
        <label for="role-role">Role (clerk or steward)</label>
        <input
          id="role-role"
          data-testid="role-form-role"
          [value]="role()"
          (input)="role.set($any($event.target).value); roleError.set(null)"
        />
        <button type="submit" data-testid="role-form-submit">Set role</button>
        @if (roleError(); as err) {
          <p role="alert" data-testid="role-form-error">{{ err }}</p>
        }
      </form>

      <table>
        <caption>Roles (any other name is a clerk; the name being entered is shown with its role)</caption>
        <thead>
          <tr><th scope="col">User</th><th scope="col">Role</th></tr>
        </thead>
        <tbody>
          @for (r of roles.all(); track r.user) {
            <tr [attr.data-testid]="'role-row-' + r.user">
              <td data-testid="role-user">{{ r.user }}</td>
              <td data-testid="role-value">{{ r.role }}</td>
            </tr>
          }
          @if (typedDefault(); as name) {
            <tr [attr.data-testid]="'role-row-' + name">
              <td data-testid="role-user">{{ name }}</td>
              <td data-testid="role-value">clerk</td>
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

  protected readonly clerkLimit = linkedSignal(() => this.session.clerkLimit() ?? '');
  protected readonly floor = linkedSignal(() => this.session.floor() ?? '');
  protected readonly settingsError = signal<string | null>(null);
  protected readonly user = signal('');
  protected readonly role = signal('');
  protected readonly roleError = signal<string | null>(null);
  /** The name being entered in the role form when it has no role given, so it shows as a clerk. */
  protected readonly typedDefault = computed(() => {
    const name = this.user().trim();
    return name && !this.roles.all().some((r) => r.user === name) ? name : null;
  });
  /** Set while a submit awaits the store, so a second submit does not store it twice. */
  private savingSettings = false;
  private savingRole = false;

  constructor() {
    // Re-read the store each time the sheet is (re-)loaded.
    effect(() => {
      this.session.loads();
      untracked(() => {
        this.settingsError.set(null);
        this.roleError.set(null);
        void this.roles.reload();
      });
    });
  }

  protected async saveSettings(event: Event): Promise<void> {
    event.preventDefault();
    if (this.savingSettings) return;
    this.settingsError.set(null);
    const limit = this.clerkLimit().trim();
    if (limit && !AMOUNT.test(limit)) {
      this.settingsError.set('Enter a clerk limit of 0.00 or more, with at most two decimals, or leave it blank.');
      return;
    }
    const floor = this.floor().trim();
    if (!AMOUNT.test(floor)) {
      this.settingsError.set('Enter a floor of 0.00 or more, with at most two decimals.');
      return;
    }
    this.savingSettings = true;
    try {
      await this.session.setSettings(limit || null, floor);
    } catch {
      this.settingsError.set('The settings could not be saved.');
    } finally {
      this.savingSettings = false;
    }
  }

  protected async saveRole(event: Event): Promise<void> {
    event.preventDefault();
    if (this.savingRole) return;
    this.roleError.set(null);
    const user = this.user().trim();
    const role = this.role().trim();
    if (!user) {
      this.roleError.set('Enter a user name.');
      return;
    }
    if (!ROLES.includes(role as RoleName)) {
      this.roleError.set('Enter a role: clerk or steward.');
      return;
    }
    this.savingRole = true;
    try {
      await this.roles.set(user, role as RoleName);
      this.user.set('');
      this.role.set('');
    } catch {
      this.roleError.set('The role could not be set.');
    } finally {
      this.savingRole = false;
    }
  }
}
