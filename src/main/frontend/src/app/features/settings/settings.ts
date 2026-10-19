import { Component, inject, signal } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { forkJoin, of, switchMap } from 'rxjs';
import { Session } from '../../core/session';
import { ROLES, RoleGiven, Settings as SheetSettings, SettingsApi } from './settings-api';
import { DEFAULT_FLOOR, SheetFloor } from './sheet-floor';
import { DEFAULT_LATE_PCT, SheetLatePct } from './sheet-late-pct';
import { DEFAULT_TERMS, SheetTerms } from './sheet-terms';

const LIMIT = /^\d{1,10}(\.\d{1,2})?$/;
const DAYS = /^\d{1,5}$/;
const PCT = /^\d{1,5}(\.\d{1,2})?$/;
const NO_SETTINGS: SheetSettings = {
  clerkLimit: null,
  floor: DEFAULT_FLOOR,
  terms: DEFAULT_TERMS,
  latePct: DEFAULT_LATE_PCT,
};

// The sheet's settings: the role each user name has (clerk unless given another) with the form that
// gives one, the clerk limit, the highest due a clerk may settle (blank for no limit), and the floor, the
// least a claim's live due comes to, the terms, the days after its date a claim is due by, and the late
// percent, what an overdue claim gains of its figure for every full 30 days overdue. Saving sends
// only the settings changed in the form (the clerk limit when none was).
@Component({
  selector: 'app-settings',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="settings-heading" data-testid="settings">
      <h2 id="settings-heading">Settings</h2>
      <form [formGroup]="settingsForm" (ngSubmit)="save()" data-testid="settings-form">
        <label for="setting-clerk-limit">Clerk limit (blank for none)</label>
        <input
          id="setting-clerk-limit"
          data-testid="setting-clerk-limit"
          inputmode="decimal"
          formControlName="clerkLimit"
        />
        <label for="setting-floor">Floor</label>
        <input id="setting-floor" data-testid="setting-floor" inputmode="decimal" formControlName="floor" />
        <label for="setting-terms">Terms (days)</label>
        <input id="setting-terms" data-testid="setting-terms" inputmode="numeric" formControlName="terms" />
        <label for="setting-late-pct">Late percent (per full 30 days overdue)</label>
        <input id="setting-late-pct" data-testid="setting-late-pct" inputmode="decimal" formControlName="latePct" />
        <button type="submit" data-testid="settings-save">Save settings</button>
      </form>
      @if (settingsError(); as e) {
        <p role="alert" data-testid="settings-error">{{ e }}</p>
      }
      <p>
        Clerk limit in force:
        <span data-testid="setting-clerk-limit-current">{{ settings().clerkLimit ?? '' }}</span>
      </p>
      <p>
        Floor in force:
        <span data-testid="setting-floor-current">{{ settings().floor }}</span>
      </p>
      <p>
        Terms in force:
        <span data-testid="setting-terms-current">{{ settings().terms }}</span>
      </p>
      <p>
        Late percent in force:
        <span data-testid="setting-late-pct-current">{{ settings().latePct }}</span>
      </p>
      <form [formGroup]="roleForm" (ngSubmit)="setRole()" data-testid="role-form">
        <label for="role-form-user">User</label>
        <input id="role-form-user" data-testid="role-form-user" formControlName="user" />
        <label for="role-form-role">Role ({{ roleNames }})</label>
        <input id="role-form-role" data-testid="role-form-role" formControlName="role" />
        <button type="submit" data-testid="role-form-submit">Set role</button>
      </form>
      @if (roleError(); as e) {
        <p role="alert" data-testid="role-form-error">{{ e }}</p>
      }
      <table data-testid="role-list">
        <caption>Roles (anyone else is a clerk)</caption>
        <thead>
          <tr>
            <th scope="col">User</th>
            <th scope="col">Role</th>
          </tr>
        </thead>
        <tbody>
          @for (r of roles(); track r.user) {
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
  private readonly session = inject(Session);
  private readonly api = inject(SettingsApi);
  private readonly floor = inject(SheetFloor);
  private readonly terms = inject(SheetTerms);
  private readonly latePct = inject(SheetLatePct);

  protected readonly roleNames = ROLES.join(' or ');
  protected readonly roles = signal<RoleGiven[]>([]);
  protected readonly settings = signal<SheetSettings>(NO_SETTINGS);
  protected readonly roleError = signal<string | null>(null);
  protected readonly settingsError = signal<string | null>(null);
  private readonly busy = signal(false);
  protected readonly roleForm = new FormGroup({
    user: new FormControl('', { nonNullable: true }),
    role: new FormControl('', { nonNullable: true }),
  });
  protected readonly settingsForm = new FormGroup({
    clerkLimit: new FormControl('', { nonNullable: true }),
    floor: new FormControl(DEFAULT_FLOOR, { nonNullable: true }),
    terms: new FormControl(String(DEFAULT_TERMS), { nonNullable: true }),
    latePct: new FormControl(DEFAULT_LATE_PCT, { nonNullable: true }),
  });

  constructor() {
    this.roleForm.valueChanges.subscribe(() => this.roleError.set(null));
    this.settingsForm.valueChanges.subscribe(() => this.settingsError.set(null));
    // Re-read the sheet's settings from the store each time a sheet is loaded.
    toObservable(this.session.sheetId)
      .pipe(
        switchMap((id) =>
          id
            ? forkJoin({ roles: this.api.roles(id), settings: this.api.get(id) })
            : of({ roles: [] as RoleGiven[], settings: NO_SETTINGS }),
        ),
      )
      .subscribe(({ roles, settings }) => {
        this.roles.set(roles);
        this.showSettings(settings);
        this.roleError.set(null);
        this.settingsError.set(null);
      });
  }

  protected setRole(): void {
    if (this.busy()) return;
    this.roleError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) {
      this.roleError.set('Load a sheet first.');
      return;
    }
    const user = this.roleForm.getRawValue().user.trim();
    const role = this.roleForm.getRawValue().role.trim();
    if (!user) {
      this.roleError.set('Type a user name.');
      return;
    }
    if (!(ROLES as readonly string[]).includes(role)) {
      this.roleError.set(`A role is ${this.roleNames}.`);
      return;
    }
    this.busy.set(true);
    this.api.setRole(sheetId, user, role).subscribe({
      next: (given) => {
        this.busy.set(false);
        if (this.session.sheetId() !== sheetId) return;
        this.roles.update((rs) =>
          rs.some((r) => r.user === given.user)
            ? rs.map((r) => (r.user === given.user ? given : r))
            : [...rs, given],
        );
        this.roleForm.reset({ user: '', role: '' }, { emitEvent: false });
      },
      error: () => {
        this.busy.set(false);
        this.roleError.set('Could not set that role.');
      },
    });
  }

  protected save(): void {
    if (this.busy()) return;
    this.settingsError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) {
      this.settingsError.set('Load a sheet first.');
      return;
    }
    const controls = this.settingsForm.controls;
    const clerkLimit = controls.clerkLimit.value.trim();
    const floor = controls.floor.value.trim();
    const terms = controls.terms.value.trim();
    const sendFloor = controls.floor.dirty;
    const latePct = controls.latePct.value.trim();
    const sendTerms = controls.terms.dirty;
    const sendLatePct = controls.latePct.dirty;
    const sendLimit = controls.clerkLimit.dirty || (!sendFloor && !sendTerms && !sendLatePct);
    if (sendLimit && clerkLimit !== '' && !LIMIT.test(clerkLimit)) {
      this.settingsError.set('Type a clerk limit of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    if (sendFloor && !LIMIT.test(floor)) {
      this.settingsError.set('Type a floor of 0.00 or more, with up to two decimals.');
      return;
    }
    if (sendTerms && !DAYS.test(terms)) {
      this.settingsError.set('Type terms of 0 or more whole days.');
      return;
    }
    if (sendLatePct && !PCT.test(latePct)) {
      this.settingsError.set('Type a late percent of 0.00 or more, with up to two decimals.');
      return;
    }
    const changed = {
      ...(sendLimit ? { clerkLimit } : {}),
      ...(sendFloor ? { floor } : {}),
      ...(sendTerms ? { terms } : {}),
      ...(sendLatePct ? { latePct } : {}),
    };
    this.busy.set(true);
    this.api.save(sheetId, changed).subscribe({
      next: (s) => {
        this.busy.set(false);
        if (this.session.sheetId() !== sheetId) return;
        this.showSettings(s);
      },
      error: () => {
        this.busy.set(false);
        this.settingsError.set('Could not save the settings.');
      },
    });
  }

  /** Show the settings in force, and put them back in the form as unchanged. */
  private showSettings(s: SheetSettings): void {
    this.settings.set(s);
    this.floor.set(s.floor);
    this.terms.set(s.terms);
    this.latePct.set(s.latePct);
    this.settingsForm.reset(
      { clerkLimit: s.clerkLimit ?? '', floor: s.floor, terms: String(s.terms), latePct: s.latePct },
      { emitEvent: false },
    );
  }
}
