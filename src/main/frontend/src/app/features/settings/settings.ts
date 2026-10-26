import { HttpClient } from '@angular/common/http';
import { Component, Injectable, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

export interface RoleView {
  user: string;
  role: string;
}

interface SettingsView {
  // The most a clerk may settle a claim for; null for no limit.
  clerkLimit: string | null;
  // The least a claim's live due can be, with two decimals.
  floor: string;
  // The days a claim has to be settled in: its due-by is its date plus these.
  terms: number;
}

export const ROLES = ['clerk', 'steward'];

const LIMIT = /^\d{1,12}(\.\d{1,2})?$/;

/** The floor a sheet starts with. */
export const DEFAULT_FLOOR = '1.00';

/** The terms a sheet starts with, in days. */
export const DEFAULT_TERMS = 14;

const DAYS = /^\d{1,5}$/;

// The active sheet's settings: the role set for each user name (any other name is a clerk), the clerk limit and
// the floor and the terms.
@Injectable({ providedIn: 'root' })
export class SettingsStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly roles = signal<readonly RoleView[]>([]);
  readonly clerkLimit = signal<string | null>(null);
  readonly floor = signal<string>(DEFAULT_FLOOR);
  readonly terms = signal<number>(DEFAULT_TERMS);

  /** Re-read the sheet's settings from the store. */
  async load(sheet: string): Promise<void> {
    this.roles.set([]);
    this.clerkLimit.set(null);
    this.floor.set(DEFAULT_FLOOR);
    this.terms.set(DEFAULT_TERMS);
    const base = `/api/sheets/${encodeURIComponent(sheet)}`;
    const [roles, settings] = await Promise.all([
      firstValueFrom(this.http.get<RoleView[]>(`${base}/roles`)),
      firstValueFrom(this.http.get<SettingsView>(`${base}/settings`)),
    ]);
    if (this.session.sheetId() === sheet) {
      this.roles.set(roles);
      this.clerkLimit.set(settings.clerkLimit);
      this.floor.set(settings.floor);
      this.terms.set(settings.terms);
    }
  }

  async setRole(sheet: string, user: string, role: string): Promise<void> {
    const r = await firstValueFrom(
      this.http.put<RoleView>(`/api/sheets/${encodeURIComponent(sheet)}/roles/${encodeURIComponent(user)}`, { role }),
    );
    if (this.session.sheetId() === sheet) {
      this.roles.update((l) => [...l.filter((x) => x.user !== r.user), r].sort((a, b) => a.user.localeCompare(b.user)));
    }
  }

  async setClerkLimit(sheet: string, value: string | null): Promise<void> {
    const s = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/clerk-limit`, { value }),
    );
    if (this.session.sheetId() === sheet) this.clerkLimit.set(s.clerkLimit);
  }

  async setFloor(sheet: string, value: string): Promise<void> {
    const s = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/floor`, { value }),
    );
    if (this.session.sheetId() === sheet) this.floor.set(s.floor);
  }

  async setTerms(sheet: string, value: number): Promise<void> {
    const s = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/terms`, { value }),
    );
    if (this.session.sheetId() === sheet) this.terms.set(s.terms);
  }
}

// The sheet's settings: the clerk limit, the floor, the terms, and the role each user name has.
@Component({
  selector: 'app-settings',
  template: `
    <section aria-label="Settings">
      <h2>Settings</h2>
      <form (submit)="saveSettings($event)" aria-label="Save settings">
        <label for="setting-clerk-limit">Clerk limit (blank for none)</label>
        <input
          id="setting-clerk-limit"
          data-testid="setting-clerk-limit"
          inputmode="decimal"
          [value]="limit()"
          (input)="limit.set(value($event)); limitChanged = true; settingsError.set(null)"
        />
        <label for="setting-floor">Floor (the least a live due can be)</label>
        <input
          id="setting-floor"
          data-testid="setting-floor"
          inputmode="decimal"
          [value]="floor()"
          (input)="floor.set(value($event)); floorChanged = true; settingsError.set(null)"
        />
        <label for="setting-terms">Terms (days a claim is due by after its date)</label>
        <input
          id="setting-terms"
          data-testid="setting-terms"
          inputmode="numeric"
          [value]="terms()"
          (input)="terms.set(value($event)); termsChanged = true; settingsError.set(null)"
        />
        <button type="submit" data-testid="settings-save">Save settings</button>
        @if (settingsError(); as err) {
          <p role="alert" data-testid="settings-error">{{ err }}</p>
        }
      </form>
      <p>Clerk limit in force: <strong data-testid="setting-clerk-limit-current">{{ store.clerkLimit() ?? '' }}</strong></p>
      <p>Floor in force: <strong data-testid="setting-floor-current">{{ store.floor() }}</strong></p>
      <p>Terms in force: <strong data-testid="setting-terms-current">{{ store.terms() }}</strong> days</p>

      <form (submit)="saveRole($event)" aria-label="Set role">
        <label for="role-user">User name</label>
        <input
          id="role-user"
          data-testid="role-form-user"
          [value]="user()"
          (input)="user.set(value($event)); roleError.set(null)"
        />
        <label for="role-role">Role (clerk or steward)</label>
        <input
          id="role-role"
          data-testid="role-form-role"
          list="role-options"
          [value]="role()"
          (input)="role.set(value($event)); roleError.set(null)"
        />
        <datalist id="role-options">
          @for (r of roles; track r) {
            <option [value]="r"></option>
          }
        </datalist>
        <button type="submit" data-testid="role-form-submit">Set role</button>
        @if (roleError(); as err) {
          <p role="alert" data-testid="role-form-error">{{ err }}</p>
        }
      </form>

      <table>
        <caption>Roles (any other name is a clerk)</caption>
        <thead>
          <tr><th scope="col">User</th><th scope="col">Role</th></tr>
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
    </section>
  `,
})
export class Settings {
  protected readonly session = inject(Session);
  protected readonly store = inject(SettingsStore);

  protected readonly roles = ROLES;
  protected readonly limit = signal('');
  protected readonly floor = signal('');
  protected readonly terms = signal('');
  // Which settings were typed into since the last save; a save stores only those.
  protected limitChanged = false;
  protected floorChanged = false;
  protected termsChanged = false;
  protected readonly user = signal('');
  protected readonly role = signal('');
  protected readonly settingsError = signal<string | null>(null);
  protected readonly roleError = signal<string | null>(null);
  // A submit in flight: a second submit before it answers is ignored.
  private submitting = false;

  constructor() {
    // Re-read the settings from the store each time a sheet is loaded.
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.settingsError.set(null);
        this.roleError.set(null);
        if (id) this.store.load(id).catch(() => this.settingsError.set('Could not load the settings.'));
      });
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async saveSettings(e: Event): Promise<void> {
    e.preventDefault();
    if (this.submitting) return;
    this.settingsError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const limit = this.limit().trim();
    if (this.limitChanged && limit && !LIMIT.test(limit)) {
      this.settingsError.set('Enter a clerk limit of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    const floor = this.floor().trim();
    if (this.floorChanged && !LIMIT.test(floor)) {
      this.settingsError.set('Enter a floor of 0.00 or more, with up to two decimals.');
      return;
    }
    const terms = this.terms().trim();
    if (this.termsChanged && !DAYS.test(terms)) {
      this.settingsError.set('Enter terms as a whole number of days, 0 or more.');
      return;
    }
    this.submitting = true;
    try {
      if (this.limitChanged) {
        await this.store.setClerkLimit(sheet, limit || null);
        this.limitChanged = false;
      }
      if (this.floorChanged) {
        await this.store.setFloor(sheet, floor);
        this.floorChanged = false;
      }
      if (this.termsChanged) {
        await this.store.setTerms(sheet, Number(terms));
        this.termsChanged = false;
      }
    } catch {
      this.settingsError.set('Could not save the settings.');
    } finally {
      this.submitting = false;
    }
  }

  protected async saveRole(e: Event): Promise<void> {
    e.preventDefault();
    if (this.submitting) return;
    this.roleError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const user = this.user().trim();
    const role = this.role().trim();
    if (!user) {
      this.roleError.set('Enter a user name.');
      return;
    }
    if (!ROLES.includes(role)) {
      this.roleError.set('Choose a role of clerk or steward.');
      return;
    }
    this.submitting = true;
    try {
      await this.store.setRole(sheet, user, role);
    } catch {
      this.roleError.set('Could not set that role.');
    } finally {
      this.submitting = false;
    }
  }
}
