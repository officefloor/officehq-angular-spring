import { HttpClient } from '@angular/common/http';
import { Component, Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { AMOUNT } from '../claims/claims';

export interface RoleView {
  user: string;
  /** clerk or steward; a user name not listed is a clerk. */
  role: string;
}

interface SettingsView {
  roles: RoleView[];
  /** The clerk limit (two decimals); null when there is none. */
  clerkLimit: string | null;
  /** The floor (two decimals): the least a claim's live due can be. */
  floor: string;
}

/** The floor a sheet starts with. */
const FLOOR = '1.00';

const ROLES = ['clerk', 'steward'];

// The active sheet's settings, re-read whenever a sheet is loaded.
@Injectable({ providedIn: 'root' })
export class SettingStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly roles = signal<RoleView[]>([]);
  readonly clerkLimit = signal<string | null>(null);
  readonly floor = signal(FLOOR);

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      const load = this.session.loads();
      this.roles.set([]);
      this.clerkLimit.set(null);
      this.floor.set(FLOOR);
      if (sheet) void this.read(sheet, load);
    });
  }

  /** Assign a user name a role on the active sheet; the server refuses a role it does not know. */
  async setRole(user: string, role: string): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<RoleView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/roles`, { user, role }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.roles.update((list) =>
      [...list.filter((r) => r.user !== saved.user), saved].sort((a, b) => a.user.localeCompare(b.user)),
    );
  }

  /** Change the active sheet's clerk limit; null removes it. */
  async setClerkLimit(value: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/clerk-limit`, { value }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Change the active sheet's floor. */
  async setFloor(value: string): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/floor`, { value }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  private async read(sheet: string, load: number): Promise<void> {
    const settings = await firstValueFrom(
      this.http.get<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings`),
    );
    if (this.session.loads() !== load) return;
    this.show(settings);
  }

  private show(settings: SettingsView): void {
    this.roles.set(settings.roles);
    this.clerkLimit.set(settings.clerkLimit);
    this.floor.set(settings.floor);
  }
}

// The sheet's settings: the role given each user name (clerk, the default, or steward) and the
// clerk limit, the highest due a clerk can settle, and the floor, the least a claim's live due can be.
// Saving sends only the settings typed into since the last save.
@Component({
  selector: 'app-settings',
  template: `
    <section aria-labelledby="settings-heading">
      <h2 id="settings-heading">Settings</h2>
      <form (submit)="saveRole($event)">
        <label for="role-form-user">User</label>
        <input
          id="role-form-user"
          data-testid="role-form-user"
          [value]="user()"
          (input)="user.set(value($event)); roleError.set(null)"
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
      </form>
      @if (roleError(); as message) {
        <p role="alert" data-testid="role-form-error">{{ message }}</p>
      }
      <table>
        <thead>
          <tr>
            <th scope="col">User</th>
            <th scope="col">Role</th>
          </tr>
        </thead>
        <tbody>
          @for (r of store.roles(); track r.user) {
            <tr [attr.data-testid]="'role-row-' + r.user">
              <th scope="row" data-testid="role-user">{{ r.user }}</th>
              <td data-testid="role-value">{{ r.role }}</td>
            </tr>
          }
        </tbody>
      </table>

      <form (submit)="saveSettings($event)">
        <label for="setting-clerk-limit">Clerk limit</label>
        <input
          id="setting-clerk-limit"
          data-testid="setting-clerk-limit"
          inputmode="decimal"
          [value]="clerkLimit()"
          (input)="clerkLimit.set(value($event)); clerkLimitTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-floor">Floor</label>
        <input
          id="setting-floor"
          data-testid="setting-floor"
          inputmode="decimal"
          [value]="floor()"
          (input)="floor.set(value($event)); floorTyped.set(true); settingsError.set(null)"
        />
        <button type="submit" data-testid="settings-save">Save settings</button>
      </form>
      @if (settingsError(); as message) {
        <p role="alert" data-testid="settings-error">{{ message }}</p>
      }
      <p>
        Clerk limit in force:
        <strong data-testid="setting-clerk-limit-current">{{ store.clerkLimit() ?? '' }}</strong>
        @if (store.clerkLimit() === null) {
          <span>none</span>
        }
      </p>
      <p>
        Floor in force:
        <strong data-testid="setting-floor-current">{{ store.floor() }}</strong>
      </p>
    </section>
  `,
})
export class Settings {
  protected readonly store = inject(SettingStore);
  protected readonly roles = ROLES;

  protected readonly user = signal('');
  protected readonly role = signal('');
  protected readonly roleError = signal<string | null>(null);
  protected readonly clerkLimit = signal('');
  protected readonly clerkLimitTyped = signal(false);
  protected readonly floor = signal('');
  protected readonly floorTyped = signal(false);
  protected readonly settingsError = signal<string | null>(null);
  /** Whether a submit is waiting for its answer; a second submit meanwhile is dropped. */
  private readonly busy = signal(false);

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async saveRole(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy()) return;
    this.roleError.set(null);
    const user = this.user().trim();
    const role = this.role().trim();
    if (!user) {
      this.roleError.set('Type the user name to give a role.');
      return;
    }
    if (!ROLES.includes(role)) {
      this.roleError.set('Type a role: clerk or steward.');
      return;
    }
    this.busy.set(true);
    try {
      await this.store.setRole(user, role);
    } catch {
      this.roleError.set(`The role of ${user} could not be set.`);
    } finally {
      this.busy.set(false);
    }
  }

  protected async saveSettings(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy()) return;
    this.settingsError.set(null);
    const limit = this.clerkLimit().trim();
    const floor = this.floor().trim();
    const saveLimit = this.clerkLimitTyped();
    const saveFloor = this.floorTyped();
    if (saveLimit && limit !== '' && !AMOUNT.test(limit)) {
      this.settingsError.set('Type a clerk limit of 0.00 or more, with up to two decimals, or leave it blank for none.');
      return;
    }
    if (saveFloor && !AMOUNT.test(floor)) {
      this.settingsError.set('Type a floor of 0.00 or more, with up to two decimals.');
      return;
    }
    this.busy.set(true);
    try {
      if (saveLimit) {
        await this.store.setClerkLimit(limit === '' ? null : limit);
        this.clerkLimitTyped.set(false);
      }
      if (saveFloor) {
        await this.store.setFloor(floor);
        this.floorTyped.set(false);
      }
    } catch {
      this.settingsError.set('The settings could not be saved.');
    } finally {
      this.busy.set(false);
    }
  }
}
