import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

interface RoleRow {
  user: string;
  role: string;
}

interface Settings {
  /** The most due a clerk may settle, with two decimals; null when there is no limit. */
  clerkLimit: string | null;
}

interface AccessView {
  roles: RoleRow[];
  settings: Settings;
}

const ROLES = ['clerk', 'steward'];
const LIMIT = /^\d{1,12}(\.\d{1,2})?$/;

// The sheet's roles (any name without one is a clerk) with the form that sets one, and the
// sheet's settings: the clerk limit, the most due a clerk may settle (blank: no limit).
@Component({
  selector: 'app-access',
  template: `
    <section aria-label="Roles">
      <h2>Roles</h2>
      <p>Anyone without a role is a clerk.</p>
      <ul aria-label="Roles set">
        @for (r of roles(); track r.user) {
          <li [attr.data-testid]="'role-row-' + r.user">
            <span data-testid="role-user">{{ r.user }}</span>:
            <span data-testid="role-value">{{ r.role }}</span>
          </li>
        }
      </ul>
      <form (submit)="saveRole($event)" aria-label="Set role">
        <label for="role-form-user">User name</label>
        <input
          id="role-form-user"
          data-testid="role-form-user"
          [value]="roleUser()"
          (input)="roleUser.set(value($event)); roleError.set('')"
        />
        <label for="role-form-role">Role (clerk or steward)</label>
        <input
          id="role-form-role"
          data-testid="role-form-role"
          list="role-form-roles"
          [value]="role()"
          (input)="role.set(value($event)); roleError.set('')"
        />
        <datalist id="role-form-roles">
          @for (r of roleNames; track r) {
            <option [value]="r"></option>
          }
        </datalist>
        <button type="submit" data-testid="role-form-submit">Set role</button>
        @if (roleError()) {
          <p role="alert" data-testid="role-form-error">{{ roleError() }}</p>
        }
      </form>
    </section>
    <section aria-label="Settings">
      <h2>Settings</h2>
      <p>Clerk limit <strong data-testid="setting-clerk-limit-current">{{ clerkLimit() ?? '' }}</strong></p>
      <form (submit)="saveSettings($event)" aria-label="Save settings">
        <label for="setting-clerk-limit">Clerk limit (blank: no limit)</label>
        <input
          id="setting-clerk-limit"
          inputmode="decimal"
          data-testid="setting-clerk-limit"
          [value]="limitInput()"
          (input)="limitInput.set(value($event)); settingsError.set('')"
        />
        <button type="submit" data-testid="settings-save">Save settings</button>
        @if (settingsError()) {
          <p role="alert" data-testid="settings-error">{{ settingsError() }}</p>
        }
      </form>
    </section>
  `,
})
export class Access {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  protected readonly roleNames = ROLES;
  protected readonly roles = signal<RoleRow[]>([]);
  protected readonly clerkLimit = signal<string | null>(null);
  protected readonly roleUser = signal('');
  protected readonly role = signal('');
  protected readonly roleError = signal('');
  protected readonly limitInput = signal('');
  protected readonly settingsError = signal('');
  /** A submit still waiting for its answer: another press meanwhile is ignored. */
  private busy = false;

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.roles.set([]);
      this.clerkLimit.set(null);
      this.roleUser.set('');
      this.role.set('');
      this.roleError.set('');
      this.limitInput.set('');
      this.settingsError.set('');
      if (sheet) void this.reload(sheet);
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async saveRole(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.roleError.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const user = this.roleUser().trim();
    const role = this.role().trim();
    if (!user) {
      this.roleError.set('Enter a user name.');
      return;
    }
    if (!ROLES.includes(role)) {
      this.roleError.set('Enter a role: clerk or steward.');
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const saved = await firstValueFrom(
        this.http.put<RoleRow>(`/api/sheets/${encodeURIComponent(sheet)}/roles`, { user, role }, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.roles.update((rs) => [...rs.filter((r) => r.user !== saved.user), saved].sort((a, b) => a.user.localeCompare(b.user)));
      this.roleUser.set('');
      this.role.set('');
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.roleError.set('Could not set the role.');
    } finally {
      this.busy = false;
    }
  }

  protected async saveSettings(e: Event): Promise<void> {
    e.preventDefault();
    if (this.busy) return;
    this.settingsError.set('');
    const sheet = this.session.sheet();
    if (!sheet) return;
    const clerkLimit = this.limitInput().trim();
    if (clerkLimit !== '' && !LIMIT.test(clerkLimit)) {
      this.settingsError.set('Enter a clerk limit of 0.00 or more, with up to two decimals, or leave it blank for no limit.');
      return;
    }
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const saved = await firstValueFrom(
        this.http.put<Settings>(`/api/sheets/${encodeURIComponent(sheet)}/settings`, { clerkLimit }, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.clerkLimit.set(saved.clerkLimit);
      this.limitInput.set('');
    } catch {
      if (this.session.sheet() !== sheet) return;
      this.settingsError.set('Could not save the settings.');
    } finally {
      this.busy = false;
    }
  }

  private async reload(sheet: string): Promise<void> {
    const loads = this.session.loads();
    const view = await firstValueFrom(this.http.get<AccessView>(`/api/sheets/${encodeURIComponent(sheet)}/access`));
    if (this.session.sheet() === sheet && this.session.loads() === loads) {
      this.roles.set(view.roles);
      this.clerkLimit.set(view.settings.clerkLimit);
    }
  }
}
