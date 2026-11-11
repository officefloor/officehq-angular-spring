import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

interface SettingsView {
  /** The highest due a clerk may settle, two decimals; null when there is no limit. */
  clerkLimit: string | null;
  /** The least a claim's live due can be, two decimals. */
  floor: string;
  /** The days after its date a claim is due by. */
  terms: number;
}

interface RoleView {
  user: string;
  /** clerk or steward; a user name not listed is a clerk. */
  role: string;
}

const ROLES = ['clerk', 'steward'];

// The sheet's settings (its clerk limit, its floor and its terms) and the roles of its user names, each with the form that sets it.
@Component({
  selector: 'app-settings',
  template: `
    <section aria-labelledby="settings-heading">
      <h2 id="settings-heading">Settings</h2>
      <form (submit)="saveSettings($event)">
        <label for="setting-clerk-limit">Clerk limit (blank: no limit)</label>
        <input
          id="setting-clerk-limit"
          data-testid="setting-clerk-limit"
          inputmode="decimal"
          [value]="clerkLimit()"
          (input)="clerkLimit.set(value($event)); clerkLimitEdited = true; settingsError.set(null)"
        />
        <label for="setting-floor">Floor (the least a live due can be)</label>
        <input
          id="setting-floor"
          data-testid="setting-floor"
          inputmode="decimal"
          [value]="floor()"
          (input)="floor.set(value($event)); floorEdited = true; settingsError.set(null)"
        />
        <label for="setting-terms">Terms (days after its date a claim is due by)</label>
        <input
          id="setting-terms"
          data-testid="setting-terms"
          inputmode="numeric"
          [value]="terms()"
          (input)="terms.set(value($event)); termsEdited = true; settingsError.set(null)"
        />
        <button type="submit" data-testid="settings-save">Save settings</button>
        @if (settingsError(); as message) {
          <p role="alert" data-testid="settings-error">{{ message }}</p>
        }
      </form>
      <p>Clerk limit in force: <strong data-testid="setting-clerk-limit-current">{{ current() ?? '' }}</strong></p>
      <p>Floor in force: <strong data-testid="setting-floor-current">{{ session.floor() ?? '' }}</strong></p>
      <p>Terms in force: <strong data-testid="setting-terms-current">{{ session.terms() ?? '' }}</strong></p>
    </section>
    <section aria-labelledby="roles-heading">
      <h2 id="roles-heading">Roles</h2>
      <form (submit)="saveRole($event)">
        <label for="role-form-user">User name</label>
        <input
          id="role-form-user"
          data-testid="role-form-user"
          [value]="roleUser()"
          (input)="roleUser.set(value($event)); roleError.set(null)"
        />
        <label for="role-form-role">Role (clerk or steward)</label>
        <input
          id="role-form-role"
          data-testid="role-form-role"
          list="role-form-roles"
          [value]="roleName()"
          (input)="roleName.set(value($event)); roleError.set(null)"
        />
        <datalist id="role-form-roles">
          @for (r of roles; track r) {
            <option [value]="r"></option>
          }
        </datalist>
        <button type="submit" data-testid="role-form-submit">Set role</button>
        @if (roleError(); as message) {
          <p role="alert" data-testid="role-form-error">{{ message }}</p>
        }
      </form>
      <table>
        <caption>Roles set (anyone else is a clerk)</caption>
        <thead>
          <tr><th scope="col">User</th><th scope="col">Role</th></tr>
        </thead>
        <tbody>
          @for (r of roleList(); track r.user) {
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
export class Settings {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(Session);

  protected readonly roles = ROLES;
  protected readonly current = signal<string | null>(null);
  protected readonly clerkLimit = signal('');
  protected readonly floor = signal('');
  protected readonly terms = signal('');
  /** Which settings were typed in since the form was last saved or read: a save sends only those. */
  protected clerkLimitEdited = false;
  protected floorEdited = false;
  protected termsEdited = false;
  protected readonly settingsError = signal<string | null>(null);
  protected readonly roleList = signal<RoleView[]>([]);
  protected readonly roleUser = signal('');
  protected readonly roleName = signal('');
  protected readonly roleError = signal<string | null>(null);
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private savingSettings = false;
  private savingRole = false;
  private reads = 0;

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loaded();
      untracked(() => {
        this.current.set(null);
        this.session.floor.set(null);
        this.session.terms.set(null);
        this.roleList.set([]);
        this.settingsError.set(null);
        this.roleError.set(null);
        if (sheet) void this.read(sheet);
      });
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async saveSettings(event: Event): Promise<void> {
    event.preventDefault();
    if (this.savingSettings) return;
    this.settingsError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    // Saving with nothing typed saves the clerk limit as it stands.
    const saveLimit = this.clerkLimitEdited || (!this.floorEdited && !this.termsEdited);
    const saveFloor = this.floorEdited;
    const saveTerms = this.termsEdited;
    const limit = this.clerkLimit().trim();
    const floor = this.floor().trim();
    const terms = this.terms().trim();
    if (saveLimit && limit !== '' && !/^\d{1,10}(\.\d{1,2})?$/.test(limit)) {
      this.settingsError.set('Type a clerk limit of 0.00 or more with at most two decimals, or leave it blank for no limit.');
      return;
    }
    if (saveFloor && !/^\d{1,10}(\.\d{1,2})?$/.test(floor)) {
      this.settingsError.set('Type a floor of 0.00 or more with at most two decimals.');
      return;
    }
    if (saveTerms && !/^\d{1,5}$/.test(terms)) {
      this.settingsError.set('Type terms of 0 or more whole days.');
      return;
    }
    this.savingSettings = true;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/settings`;
    try {
      let saved: SettingsView | null = null;
      if (saveLimit) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(url, { clerkLimit: limit === '' ? null : limit }, this.session.headers()),
        );
        this.clerkLimitEdited = false;
      }
      if (saveFloor) {
        saved = await firstValueFrom(this.http.put<SettingsView>(`${url}/floor`, { floor }, this.session.headers()));
        this.floorEdited = false;
      }
      if (saveTerms) {
        saved = await firstValueFrom(this.http.put<SettingsView>(`${url}/terms`, { terms }, this.session.headers()));
        this.termsEdited = false;
      }
      if (saved && this.session.sheet() === sheet) this.show(saved);
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
    const sheet = this.session.sheet();
    if (!sheet) return;
    const user = this.roleUser().trim();
    const role = this.roleName().trim();
    if (!user) {
      this.roleError.set('Type the user name to give a role.');
      return;
    }
    if (!ROLES.includes(role)) {
      this.roleError.set('A role is clerk or steward.');
      return;
    }
    this.savingRole = true;
    try {
      const saved = await firstValueFrom(
        this.http.put<RoleView>(`/api/sheets/${encodeURIComponent(sheet)}/roles`, { user, role }, this.session.headers()),
      );
      if (this.session.sheet() === sheet) {
        this.roleList.update((list) =>
          [...list.filter((r) => r.user !== saved.user), saved].sort((a, b) => (a.user < b.user ? -1 : a.user > b.user ? 1 : 0)),
        );
        this.roleUser.set('');
        this.roleName.set('');
      }
    } catch {
      this.roleError.set('The role could not be set.');
    } finally {
      this.savingRole = false;
    }
  }

  private async read(sheet: string): Promise<void> {
    const read = ++this.reads;
    const url = `/api/sheets/${encodeURIComponent(sheet)}`;
    const [settings, roles] = await Promise.all([
      firstValueFrom(this.http.get<SettingsView>(`${url}/settings`, this.session.headers())),
      firstValueFrom(this.http.get<RoleView[]>(`${url}/roles`, this.session.headers())),
    ]).catch(() => [null, null]);
    // A later read wins over a slower earlier one.
    if (settings && roles && read === this.reads && this.session.sheet() === sheet) {
      this.show(settings);
      this.roleList.set(roles);
    }
  }

  /** Show the settings in force, and put them back in the form. */
  private show(settings: SettingsView): void {
    this.current.set(settings.clerkLimit);
    this.clerkLimit.set(settings.clerkLimit ?? '');
    this.session.floor.set(settings.floor);
    this.floor.set(settings.floor);
    this.session.terms.set(settings.terms);
    this.terms.set(String(settings.terms));
    this.clerkLimitEdited = false;
    this.floorEdited = false;
    this.termsEdited = false;
  }
}
