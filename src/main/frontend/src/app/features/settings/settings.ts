import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A role a user name can be given; any name not given one is a clerk. */
export const ROLES = ['clerk', 'steward'];

interface RoleView {
  user: string;
  role: string;
}

interface SettingsView {
  /** The highest due a clerk can settle, two decimals; null when there is no limit. */
  clerkLimit: string | null;
  /** The least a claim's live due can be, two decimals. */
  floor: string;
  roles: RoleView[];
}

// The sheet's settings: the clerk limit (blank: no limit), the floor of a claim's live due, and the
// role given to each user name. Saving stores the settings whose field was changed since the last save.
@Component({
  selector: 'app-settings',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="settings-heading">
      <h2 id="settings-heading">Settings</h2>
      <form [formGroup]="form" (ngSubmit)="save()" aria-label="Settings">
        <label for="setting-clerk-limit">Clerk limit (blank: no limit)</label>
        <input id="setting-clerk-limit" data-testid="setting-clerk-limit" formControlName="clerkLimit" inputmode="decimal" />
        <label for="setting-floor">Floor (least live due)</label>
        <input id="setting-floor" data-testid="setting-floor" formControlName="floor" inputmode="decimal" />
        <button type="submit" data-testid="settings-save">Save settings</button>
        @if (error()) {
          <p role="alert" class="error" data-testid="settings-error">{{ error() }}</p>
        }
      </form>
      <p>Clerk limit now: <span data-testid="setting-clerk-limit-current">{{ clerkLimit() ?? '' }}</span></p>
      <p>Floor now: <span data-testid="setting-floor-current">{{ session.floor() }}</span></p>
      <form [formGroup]="roleForm" (ngSubmit)="setRole()" aria-label="Set role">
        <label for="role-form-user">User</label>
        <input id="role-form-user" data-testid="role-form-user" formControlName="user" />
        <label for="role-form-role">Role (clerk or steward)</label>
        <input id="role-form-role" data-testid="role-form-role" formControlName="role" list="role-options" />
        <datalist id="role-options">
          @for (r of roles; track r) {
            <option [value]="r"></option>
          }
        </datalist>
        <button type="submit" data-testid="role-form-submit">Set role</button>
        @if (roleError()) {
          <p role="alert" class="error" data-testid="role-form-error">{{ roleError() }}</p>
        }
      </form>
      <table>
        <caption>Roles (anyone else is a clerk)</caption>
        <thead>
          <tr><th scope="col">User</th><th scope="col">Role</th></tr>
        </thead>
        <tbody>
          @for (r of userRoles(); track r.user) {
            <tr [attr.data-testid]="'role-row-' + r.user">
              <td data-testid="role-user">{{ r.user }}</td>
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
  protected readonly clerkLimit = signal<string | null>(null);
  protected readonly userRoles = signal<RoleView[]>([]);
  protected readonly error = signal('');
  protected readonly roleError = signal('');
  /** The forms with a submit under way; another submit of one waits for its answer. */
  private readonly submitting = new Set<string>();
  protected readonly form = new FormGroup({
    clerkLimit: new FormControl('', { nonNullable: true }),
    floor: new FormControl('', { nonNullable: true }),
  });
  protected readonly roleForm = new FormGroup({
    user: new FormControl('', { nonNullable: true }),
    role: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    this.roleForm.valueChanges.subscribe(() => this.roleError.set(''));
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      this.clerkLimit.set(null);
      this.userRoles.set([]);
      this.error.set('');
      this.roleError.set('');
      if (id) void this.load(id);
    });
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }

  private base(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}`;
  }

  private async load(sheet: string): Promise<void> {
    try {
      const view = await firstValueFrom(this.http.get<SettingsView>(`${this.base(sheet)}/settings`, { headers: this.headers() }));
      if (this.session.sheetId() !== sheet) return;
      this.show(view);
    } catch {
      if (this.session.sheetId() === sheet) this.error.set('Could not load the settings of this sheet.');
    }
  }

  private show(view: SettingsView): void {
    this.clerkLimit.set(view.clerkLimit);
    this.userRoles.set(view.roles);
    this.session.floor.set(view.floor);
  }

  protected async save(): Promise<void> {
    if (this.submitting.has('save')) return;
    this.error.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const { clerkLimit, floor } = this.form.controls;
    const value = clerkLimit.value.trim();
    const floorValue = floor.value.trim();
    if (clerkLimit.dirty && value && !/^\d{1,12}(\.\d{1,2})?$/.test(value)) {
      this.error.set('Enter a clerk limit of 0.00 or more, with at most two decimals, or leave it blank for none.');
      return;
    }
    if (floor.dirty && !/^\d{1,12}(\.\d{1,2})?$/.test(floorValue)) {
      this.error.set('Enter a floor of 0.00 or more, with at most two decimals.');
      return;
    }
    this.submitting.add('save');
    try {
      if (clerkLimit.dirty) {
        const view = await firstValueFrom(
          this.http.put<SettingsView>(`${this.base(sheet)}/settings/clerk-limit`, { value: value || null }, { headers: this.headers() }),
        );
        clerkLimit.markAsPristine();
        if (this.session.sheetId() === sheet) this.show(view);
      }
      if (floor.dirty) {
        const view = await firstValueFrom(
          this.http.put<SettingsView>(`${this.base(sheet)}/settings/floor`, { value: floorValue }, { headers: this.headers() }),
        );
        floor.markAsPristine();
        if (this.session.sheetId() === sheet) this.show(view);
      }
    } catch {
      if (this.session.sheetId() === sheet) this.error.set('Could not save the settings.');
    } finally {
      this.submitting.delete('save');
    }
  }

  protected async setRole(): Promise<void> {
    if (this.submitting.has('role')) return;
    this.roleError.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const raw = this.roleForm.getRawValue();
    const user = raw.user.trim();
    const role = raw.role.trim().toLowerCase();
    if (!user) {
      this.roleError.set('Enter a user name.');
      return;
    }
    if (!ROLES.includes(role)) {
      this.roleError.set('Choose a role: clerk or steward.');
      return;
    }
    this.submitting.add('role');
    try {
      const saved = await firstValueFrom(
        this.http.put<RoleView>(`${this.base(sheet)}/roles/${encodeURIComponent(user)}`, { role }, { headers: this.headers() }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.userRoles.update((list) =>
        [...list.filter((r) => r.user !== saved.user), saved].sort((a, b) => (a.user < b.user ? -1 : 1)),
      );
    } catch {
      if (this.session.sheetId() === sheet) this.roleError.set('Could not set that role.');
    } finally {
      this.submitting.delete('role');
    }
  }
}
