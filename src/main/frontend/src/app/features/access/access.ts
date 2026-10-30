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
  /** The least live due a claim has, with two decimals. */
  floor: string;
  /** The days a claim has to be paid by, from its date. */
  terms: string;
}

interface AccessView {
  roles: RoleRow[];
  settings: Settings;
}

const ROLES = ['clerk', 'steward'];
const LIMIT = /^\d{1,12}(\.\d{1,2})?$/;
const DAYS = /^\d{1,5}$/;

// The sheet's roles (any name without one is a clerk) with the form that sets one, and the
// sheet's settings: the clerk limit, the most due a clerk may settle (blank: no limit), and the floor,
// the least live due a claim has (blank: 1.00), and the terms, the days a claim has to be paid by
// from its date (blank: 14). Each input shows the value saved; a save sends only the
// settings whose input was changed.
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
      <p>Floor <strong data-testid="setting-floor-current">{{ floor() }}</strong></p>
      <p>Terms (days) <strong data-testid="setting-terms-current">{{ terms() }}</strong></p>
      <form (submit)="saveSettings($event)" aria-label="Save settings">
        <label for="setting-clerk-limit">Clerk limit (blank: no limit)</label>
        <input
          id="setting-clerk-limit"
          inputmode="decimal"
          data-testid="setting-clerk-limit"
          [value]="limitInput()"
          (input)="limitInput.set(value($event)); limitTouched = true; settingsError.set('')"
        />
        <label for="setting-floor">Floor (blank: 1.00)</label>
        <input
          id="setting-floor"
          inputmode="decimal"
          data-testid="setting-floor"
          [value]="floorInput()"
          (input)="floorInput.set(value($event)); floorTouched = true; settingsError.set('')"
        />
        <label for="setting-terms">Terms in days (blank: 14)</label>
        <input
          id="setting-terms"
          inputmode="numeric"
          data-testid="setting-terms"
          [value]="termsInput()"
          (input)="termsInput.set(value($event)); termsTouched = true; settingsError.set('')"
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
  protected readonly floor = signal('');
  protected readonly floorInput = signal('');
  protected readonly terms = signal('');
  protected readonly termsInput = signal('');
  /** Whether each settings input was changed since the settings were last shown. */
  protected limitTouched = false;
  protected floorTouched = false;
  protected termsTouched = false;
  protected readonly settingsError = signal('');
  /** A submit still waiting for its answer: another press meanwhile is ignored. */
  private busy = false;

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.roles.set([]);
      this.roleUser.set('');
      this.role.set('');
      this.roleError.set('');
      this.showSettings({ clerkLimit: null, floor: '', terms: '' });
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
    if (this.limitTouched && clerkLimit !== '' && !LIMIT.test(clerkLimit)) {
      this.settingsError.set('Enter a clerk limit of 0.00 or more, with up to two decimals, or leave it blank for no limit.');
      return;
    }
    const floor = this.floorInput().trim();
    if (this.floorTouched && floor !== '' && !LIMIT.test(floor)) {
      this.settingsError.set('Enter a floor of 0.00 or more, with up to two decimals, or leave it blank for 1.00.');
      return;
    }
    const terms = this.termsInput().trim();
    if (this.termsTouched && terms !== '' && !DAYS.test(terms)) {
      this.settingsError.set('Enter terms of 0 or more whole days, or leave them blank for 14.');
      return;
    }
    const body: Partial<Settings> = {};
    if (this.limitTouched) body.clerkLimit = clerkLimit;
    if (this.floorTouched) body.floor = floor;
    if (this.termsTouched) body.terms = terms;
    const headers = { 'X-User': this.session.user() ?? '' };
    this.busy = true;
    try {
      const saved = await firstValueFrom(
        this.http.put<Settings>(`/api/sheets/${encodeURIComponent(sheet)}/settings`, body, { headers }),
      );
      if (this.session.sheet() !== sheet) return;
      this.showSettings(saved);
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
      this.showSettings(view.settings);
    }
  }

  /** Show the settings saved, each input holding its value, none of them changed yet. */
  private showSettings(settings: Settings): void {
    this.clerkLimit.set(settings.clerkLimit);
    this.floor.set(settings.floor);
    this.limitInput.set(settings.clerkLimit ?? '');
    this.floorInput.set(settings.floor);
    this.terms.set(settings.terms);
    this.termsInput.set(settings.terms);
    this.limitTouched = false;
    this.floorTouched = false;
    this.termsTouched = false;
  }
}
