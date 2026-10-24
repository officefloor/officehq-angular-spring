import { Component, effect, inject, output, signal } from '@angular/core';
import { cents } from './claims.service';
import { Session } from '../../core/session';
import { ROLES, Role, RoleGiven, SettingsService, SheetSettings } from './settings.service';

const LIMIT = /^\d{1,10}(\.\d{1,2})?$/;
const TERMS = /^\d{1,4}$/;

// The sheet's settings: its clerk limit (the most a clerk may settle a claim for; blank for no
// limit), its floor (the least a claim's live due can be), its terms (how many days after its date
// a claim is due by), its late percent (the percent of its figure an overdue claim's due gains for
// every full 30 days it is overdue) and the role each user name has been given (any other name is a clerk). Read from the
// store every time a sheet is loaded. Saving the settings saves only the fields changed since
// they were last saved.
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
          (input)="limit.set(value($event)); limitChanged = true; settingsError.set(null)"
          (keydown)="limitChanged = true"
        />
        <span>
          In force: <span data-testid="setting-clerk-limit-current">{{ clerkLimit() ?? '' }}</span>
          @if (clerkLimit() === null) {
            (no limit)
          }
        </span>
        <label for="setting-floor">Floor</label>
        <input
          id="setting-floor"
          data-testid="setting-floor"
          inputmode="decimal"
          [value]="floorInput()"
          (input)="floorInput.set(value($event)); floorChanged = true; settingsError.set(null)"
          (keydown)="floorChanged = true"
        />
        <span>In force: <span data-testid="setting-floor-current">{{ floor() }}</span></span>
        <label for="setting-terms">Terms (days)</label>
        <input
          id="setting-terms"
          data-testid="setting-terms"
          inputmode="numeric"
          [value]="termsInput()"
          (input)="termsInput.set(value($event)); termsChanged = true; settingsError.set(null)"
          (keydown)="termsChanged = true"
        />
        <span>In force: <span data-testid="setting-terms-current">{{ terms() }}</span></span>
        <label for="setting-late-pct">Late percent</label>
        <input
          id="setting-late-pct"
          data-testid="setting-late-pct"
          inputmode="decimal"
          [value]="latePctInput()"
          (input)="latePctInput.set(value($event)); latePctChanged = true; settingsError.set(null)"
          (keydown)="latePctChanged = true"
        />
        <span>In force: <span data-testid="setting-late-pct-current">{{ latePct() }}</span></span>
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
  /** The floor in force, with two decimals; empty until read. */
  protected readonly floor = signal('');
  protected readonly given = signal<RoleGiven[]>([]);
  /** The terms in force, in days, as text; empty until read. */
  protected readonly terms = signal('');
  /** The late percent in force, with two decimals; empty until read. */
  protected readonly latePct = signal('');
  /** The floor saved, in cents. */
  readonly floorSaved = output<number>();
  /** The terms saved, in days. */
  readonly termsSaved = output<number>();
  /** The late percent saved, with two decimals. */
  readonly latePctSaved = output<string>();

  protected readonly limit = signal('');
  protected readonly floorInput = signal('');
  protected readonly termsInput = signal('');
  protected readonly latePctInput = signal('');
  /** Whether each field was typed in (a key pressed in it, even one that left its value as it was)
   *  since the settings were last loaded or saved; a field is saved when it was, or when it differs
   *  from the value in force. */
  protected limitChanged = false;
  protected floorChanged = false;
  protected termsChanged = false;
  protected latePctChanged = false;
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
      this.floor.set('');
      this.given.set([]);
      this.limit.set('');
      this.floorInput.set('');
      this.terms.set('');
      this.termsInput.set('');
      this.limitChanged = false;
      this.floorChanged = false;
      this.termsChanged = false;
      this.latePct.set('');
      this.latePctInput.set('');
      this.latePctChanged = false;
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
    const floor = this.floorInput().trim();
    const saveLimit = this.limitChanged || limit !== (this.clerkLimit() ?? '');
    const saveFloor = this.floorChanged || floor !== this.floor();
    const terms = this.termsInput().trim();
    const saveTerms = this.termsChanged || terms !== this.terms();
    const latePct = this.latePctInput().trim();
    const saveLatePct = this.latePctChanged || latePct !== this.latePct();
    if (saveLimit && limit !== '' && !LIMIT.test(limit)) {
      this.settingsError.set('Type a clerk limit of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    if (saveFloor && !LIMIT.test(floor)) {
      this.settingsError.set('Type a floor of 0.00 or more, with up to two decimals.');
      return;
    }
    if (saveTerms && !TERMS.test(terms)) {
      this.settingsError.set('Type terms as a whole number of days, 0 or more.');
      return;
    }
    if (saveLatePct && !LIMIT.test(latePct)) {
      this.settingsError.set('Type a late percent of 0.00 or more, with up to two decimals.');
      return;
    }
    this.savingSettings = true;
    try {
      if (saveLimit) {
        const settings = await this.service.setClerkLimit(sheet, limit || null);
        if (this.session.sheetId() !== sheet) return;
        this.limitChanged = false;
        this.show(settings);
      }
      if (saveFloor) {
        const settings = await this.service.setFloor(sheet, floor);
        if (this.session.sheetId() !== sheet) return;
        this.floorChanged = false;
        this.show(settings);
        this.floorSaved.emit(cents(settings.floor));
      }
      if (saveTerms) {
        const settings = await this.service.setTerms(sheet, terms);
        if (this.session.sheetId() !== sheet) return;
        this.termsChanged = false;
        this.show(settings);
        this.termsSaved.emit(settings.terms);
      }
      if (saveLatePct) {
        const settings = await this.service.setLatePct(sheet, latePct);
        if (this.session.sheetId() !== sheet) return;
        this.latePctChanged = false;
        this.show(settings);
        this.latePctSaved.emit(settings.latePct);
      }
      this.limit.set(this.clerkLimit() ?? '');
      this.floorInput.set(this.floor());
      this.termsInput.set(this.terms());
      this.latePctInput.set(this.latePct());
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
        this.show(settings);
        if (!this.limitChanged) this.limit.set(settings.clerkLimit ?? '');
        if (!this.floorChanged) this.floorInput.set(settings.floor);
        if (!this.termsChanged) this.termsInput.set(String(settings.terms));
        if (!this.latePctChanged) this.latePctInput.set(settings.latePct);
      }
    } catch {
      // The settings could not be read; they show as none.
    }
  }

  private show(settings: SheetSettings): void {
    this.clerkLimit.set(settings.clerkLimit);
    this.floor.set(settings.floor);
    this.terms.set(String(settings.terms));
    this.latePct.set(settings.latePct);
    this.given.set(settings.roles);
  }
}
