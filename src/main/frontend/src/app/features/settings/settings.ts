import { Component, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Session } from '../../core/session';
import { ROLES, Role, RoleView, SettingsApi, SettingsView } from './settings-api';
import { DEFAULT_FLOOR_CENTS, SheetFloor } from './sheet-floor';
import { DEFAULT_TERMS, SheetTerms } from './sheet-terms';

const MONEY = /^\d{1,10}(\.\d{1,2})?$/;
const DAYS = /^\d{1,5}$/;

// The sheet's settings, with the clerk limit (the highest due a clerk may settle) and the floor (the
// least a claim's live due can be) and the terms (the days after a claim's date it is due by), and the roles it assigns user names, with one form for each.
// Saving the settings stores each setting whose field was changed since the last save.
@Component({
  selector: 'app-settings',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="settings-heading">
      <h2 id="settings-heading">Settings</h2>
      <form [formGroup]="settingsForm" (ngSubmit)="saveSettings()" aria-label="Change the settings">
        <label for="setting-clerk-limit">Clerk limit (blank: no limit)</label>
        <input id="setting-clerk-limit" data-testid="setting-clerk-limit" formControlName="clerkLimit" inputmode="decimal" />
        <p>Clerk limit now: <span data-testid="setting-clerk-limit-current">{{ clerkLimit() ?? '' }}</span></p>
        <label for="setting-floor">Floor (the least a claim's live due can be)</label>
        <input id="setting-floor" data-testid="setting-floor" formControlName="floor" inputmode="decimal" />
        <p>Floor now: <span data-testid="setting-floor-current">{{ floor() }}</span></p>
        <label for="setting-terms">Terms (days after a claim's date it is due by)</label>
        <input id="setting-terms" data-testid="setting-terms" formControlName="terms" inputmode="numeric" />
        <p>Terms now: <span data-testid="setting-terms-current">{{ terms() }}</span></p>
        <button type="submit" data-testid="settings-save" [disabled]="savingSettings()">Save settings</button>
        @if (settingsError()) {
          <p role="alert" data-testid="settings-error">{{ settingsError() }}</p>
        }
      </form>
      <h3 id="roles-heading">Roles</h3>
      <form [formGroup]="roleForm" (ngSubmit)="saveRole()" aria-label="Assign a role">
        <label for="role-form-user">User</label>
        <input id="role-form-user" data-testid="role-form-user" formControlName="user" />
        <label for="role-form-role">Role (clerk or steward)</label>
        <input id="role-form-role" data-testid="role-form-role" formControlName="role" list="role-options" />
        <datalist id="role-options">
          @for (r of roles; track r) {
            <option [value]="r"></option>
          }
        </datalist>
        <button type="submit" data-testid="role-form-submit" [disabled]="savingRole()">Assign role</button>
        @if (roleError()) {
          <p role="alert" data-testid="role-form-error">{{ roleError() }}</p>
        }
      </form>
      <table data-testid="roles-list" aria-labelledby="roles-heading">
        <thead>
          <tr><th scope="col">User</th><th scope="col">Role</th></tr>
        </thead>
        <tbody>
          @for (r of assigned(); track r.user) {
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
  private readonly session = inject(Session);
  private readonly api = inject(SettingsApi);
  private readonly sheetFloor = inject(SheetFloor);
  private readonly sheetTerms = inject(SheetTerms);

  protected readonly roles = ROLES;
  protected readonly clerkLimit = signal<string | null>(null);
  protected readonly floor = signal((DEFAULT_FLOOR_CENTS / 100).toFixed(2));
  protected readonly terms = signal(DEFAULT_TERMS);
  protected readonly assigned = signal<RoleView[]>([]);

  protected readonly settingsForm = new FormGroup({
    clerkLimit: new FormControl('', { nonNullable: true }),
    floor: new FormControl('', { nonNullable: true }),
    terms: new FormControl('', { nonNullable: true }),
  });
  protected readonly roleForm = new FormGroup({
    user: new FormControl('', { nonNullable: true }),
    role: new FormControl('', { nonNullable: true }),
  });
  protected readonly savingSettings = signal(false);
  protected readonly savingRole = signal(false);
  protected readonly settingsError = signal('');
  protected readonly roleError = signal('');

  constructor() {
    this.settingsForm.valueChanges.subscribe(() => this.settingsError.set(''));
    this.roleForm.valueChanges.subscribe(() => this.roleError.set(''));
    // Each load of a sheet re-reads its settings from the store and starts the forms afresh.
    effect(() => {
      const sheet = this.session.loaded();
      this.settingsError.set('');
      this.roleError.set('');
      this.show({ clerkLimit: null, floor: (DEFAULT_FLOOR_CENTS / 100).toFixed(2), terms: DEFAULT_TERMS, roles: [] });
      this.settingsForm.markAsPristine();
      if (sheet) void this.reload(sheet.id);
    });
  }

  private async reload(sheetId: string): Promise<void> {
    try {
      const settings = await this.api.get(sheetId);
      if (this.session.sheet()?.id === sheetId) this.show(settings);
    } catch {
      this.settingsError.set('The settings could not be loaded.');
    }
  }

  protected async saveSettings(): Promise<void> {
    // A second submit before the first has answered is ignored, so it stores one change, not two.
    if (this.savingSettings()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.settingsError.set('');
    const { clerkLimit, floor, terms } = this.settingsForm.controls;
    const limit = clerkLimit.value.trim();
    const floorValue = floor.value.trim();
    const termsValue = terms.value.trim();
    if (clerkLimit.dirty && limit !== '' && !MONEY.test(limit)) {
      this.settingsError.set('Type a clerk limit of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    if (floor.dirty && !MONEY.test(floorValue)) {
      this.settingsError.set('Type a floor of 0.00 or more, with up to two decimals.');
      return;
    }
    if (terms.dirty && (!DAYS.test(termsValue) || Number(termsValue) > 36500)) {
      this.settingsError.set('Type terms as a whole number of days, from 0 to 36500.');
      return;
    }
    this.savingSettings.set(true);
    try {
      if (clerkLimit.dirty) {
        const settings = await this.api.setClerkLimit(sheet.id, limit === '' ? null : limit);
        clerkLimit.markAsPristine();
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      if (floor.dirty) {
        const settings = await this.api.setFloor(sheet.id, floorValue);
        floor.markAsPristine();
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
      if (terms.dirty) {
        const settings = await this.api.setTerms(sheet.id, Number(termsValue));
        terms.markAsPristine();
        if (this.session.sheet()?.id === sheet.id) this.show(settings);
      }
    } catch {
      this.settingsError.set('The settings could not be saved.');
    } finally {
      this.savingSettings.set(false);
    }
  }

  /** Show the settings as the store answered them, and share the floor and terms with the screens showing them. */
  private show(settings: SettingsView): void {
    this.clerkLimit.set(settings.clerkLimit);
    this.floor.set(settings.floor);
    this.sheetFloor.cents.set(Math.round(Number(settings.floor) * 100));
    this.terms.set(settings.terms);
    this.sheetTerms.days.set(settings.terms);
    this.assigned.set(settings.roles);
  }

  protected async saveRole(): Promise<void> {
    if (this.savingRole()) return;
    const sheet = this.session.sheet();
    if (!sheet) return;
    this.roleError.set('');
    const user = this.roleForm.getRawValue().user.trim();
    const role = this.roleForm.getRawValue().role.trim();
    if (user === '') {
      this.roleError.set('Type the user name to assign a role to.');
      return;
    }
    if (!ROLES.includes(role as Role)) {
      this.roleError.set('Choose a role of clerk or steward.');
      return;
    }
    this.savingRole.set(true);
    try {
      const saved = await this.api.setRole(sheet.id, user, role as Role);
      if (this.session.sheet()?.id === sheet.id) {
        this.assigned.update((list) =>
          [...list.filter((r) => r.user !== saved.user), saved].sort((a, b) => (a.user < b.user ? -1 : a.user > b.user ? 1 : 0)));
      }
    } catch {
      this.roleError.set('The role could not be assigned.');
    } finally {
      this.savingRole.set(false);
    }
  }
}
