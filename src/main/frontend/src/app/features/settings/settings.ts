import { HttpClient } from '@angular/common/http';
import { Component, Injectable, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { AMOUNT } from '../claims/claims';
import { GRADES } from '../rates/rates';

export interface RoleView {
  user: string;
  /** clerk or steward; a user name not listed is a clerk. */
  role: string;
}

/** A value recorded for a dated setting, in force from its from-date (yyyy-mm-dd) until the next
 *  one's: two decimals, whole days for terms, null for no clerk limit, cap or proof threshold. */
export interface DatedValue {
  from: string;
  value: string | null;
}

interface SettingsView {
  roles: RoleView[];
  /** The clerk limit (two decimals); null when there is none. */
  clerkLimit: string | null;
  /** The floor (two decimals): the least a claim's live due can be. */
  floor: string;
  /** The cap (two decimals): the most a person's due-total can be; null when there is none. */
  cap: string | null;
  /** The terms in whole days: a claim is due by its date plus them. */
  terms: number;
  /** The late percent (two decimals): an overdue claim's due gains it of its figure for every full
   *  30 days it is overdue. */
  latePct: string;
  /** The policy date (yyyy-mm-dd): a claim dated before it rounds half up, one on or after it half to
   *  even; blank for the sheet's today. */
  policyDate: string;
  /** The period numbering from (yyyy-mm-dd): while the sheet's today is on or after it, a claim opened
   *  is numbered within its period; blank for off. */
  periodNumberingFrom: string;
  /** The grades' own terms in whole days, by grade; a grade not listed takes the sheet's terms. */
  gradeTerms: Record<string, number>;
  /** Every value recorded for the dated settings, by key (floor, late-pct, terms, terms-<grade>,
   *  clerk-limit, cap, proof-threshold), each list by from-date. */
  dated: Record<string, DatedValue[]>;
  /** The permission matrix: the roles each action allows, comma separated, by action; blank keeps
   *  the standing rule. */
  permissions: Record<string, string>;
}

/** The value recorded that is in force on a date: the one with the latest from-date on or before
 *  it; undefined before every from-date (or with no date), when the starting value applies. */
function valueOn(recorded: DatedValue[] | undefined, date: string | null): string | null | undefined {
  if (date === null || recorded === undefined) return undefined;
  let found: DatedValue | undefined;
  for (const v of recorded) if (v.from <= date && (found === undefined || v.from > found.from)) found = v;
  return found?.value;
}

/** The floor a sheet starts with. */
const FLOOR = '1.00';

/** The terms a sheet starts with, in days. */
const TERMS = 14;

/** The late percent a sheet starts with. */
const LATE_PCT = '0.00';

/** Terms of 0 or more whole days. */
const DAYS = /^\d{1,5}$/;

/** A date, yyyy-mm-dd. */
const DATE = /^\d{4}-\d{2}-\d{2}$/;

const ROLES = ['clerk', 'steward', 'viewer'];

/** The actions the permission matrix governs, each with the roles its standing rule allows. */
const PERMISSIONS: { action: string; label: string; standing: string }[] = [
  { action: 'open', label: 'Open', standing: 'clerk and steward' },
  { action: 'settle', label: 'Settle', standing: 'clerk and steward' },
  { action: 'void', label: 'Void', standing: 'clerk and steward' },
  { action: 'reassign', label: 'Reassign', standing: 'steward' },
  { action: 'close', label: 'Close a period', standing: 'clerk and steward' },
  { action: 'reopen', label: 'Reopen a period', standing: 'steward' },
];

/** Whether a permission entry is blank or a comma separated list of roles. */
function rolesList(typed: string): boolean {
  return typed === '' || typed.split(',').every((r) => ROLES.includes(r.trim()));
}

// The active sheet's settings, re-read whenever a sheet is loaded.
@Injectable({ providedIn: 'root' })
export class SettingStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly roles = signal<RoleView[]>([]);
  /** Every value recorded for the dated settings, by key. */
  readonly dated = signal<Record<string, DatedValue[]>>({});
  /** The clerk limit in force on the sheet's today; null when there is none. */
  readonly clerkLimit = computed(() => this.onToday('clerk-limit') ?? null);
  /** The floor in force on the sheet's today. */
  readonly floor = computed(() => this.onToday('floor') ?? FLOOR);
  /** The cap in force on the sheet's today; null when there is none. */
  readonly cap = computed(() => this.onToday('cap') ?? null);
  /** The proof threshold in force on the sheet's today; null when there is none. */
  readonly proofThreshold = computed(() => this.onToday('proof-threshold') ?? null);
  /** The terms in force on the sheet's today. */
  readonly terms = computed(() => Number(this.onToday('terms') ?? TERMS));
  /** The late percent in force on the sheet's today. */
  readonly latePct = computed(() => this.onToday('late-pct') ?? LATE_PCT);
  /** The grades' own terms in force on the sheet's today, by grade; a grade not listed takes the
   *  sheet's terms. */
  readonly gradeTerms = computed(() => {
    const byGrade: Record<string, number> = {};
    for (const g of GRADES) {
      const days = this.onToday(`terms-${g}`);
      if (days !== undefined && days !== null) byGrade[g] = Number(days);
    }
    return byGrade;
  });
  /** The floor in force on a date: the least the live due of a claim of that date can be. */
  readonly floorOn = computed(() => {
    const recorded = this.dated()['floor'];
    return (date: string) => valueOn(recorded, date) ?? FLOOR;
  });
  /** The late percent in force on a date: what an overdue claim of that date gains. */
  readonly latePctOn = computed(() => {
    const recorded = this.dated()['late-pct'];
    return (date: string) => valueOn(recorded, date) ?? LATE_PCT;
  });
  readonly policyDate = signal('');
  readonly periodNumberingFrom = signal('');
  /** The permission matrix as set, by action; blank keeps the standing rule. */
  readonly permissions = signal<Record<string, string>>({});

  private onToday(key: string): string | null | undefined {
    return valueOn(this.dated()[key], this.session.today());
  }

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      const load = this.session.loads();
      this.roles.set([]);
      this.dated.set({});
      this.policyDate.set('');
      this.periodNumberingFrom.set('');
      this.permissions.set({});
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

  /** Record the active sheet's clerk limit from a date (null for the sheet's today); null records none. */
  async setClerkLimit(value: string | null, from: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/clerk-limit`, { value, from }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Record the active sheet's floor from a date (null for the sheet's today). */
  async setFloor(value: string, from: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/floor`, { value, from }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Record the active sheet's cap from a date (null for the sheet's today); null records none. */
  async setCap(value: string | null, from: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/cap`, { value, from }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Record the active sheet's proof threshold from a date (null for the sheet's today); null records none. */
  async setProofThreshold(value: string | null, from: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/proof-threshold`, { value, from }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Record the active sheet's terms from a date (null for the sheet's today). */
  async setTerms(value: number, from: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/terms`, { value, from }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** The terms in days a claim of the grade is added with: the grade's own, else the sheet's. */
  termsOf(grade: string): number {
    return this.gradeTerms()[grade] ?? this.terms();
  }

  /** Record a grade's own terms on the active sheet from a date (null for the sheet's today). */
  async setGradeTerms(grade: string, value: number, from: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(
        `/api/sheets/${encodeURIComponent(sheet)}/settings/terms/${encodeURIComponent(grade)}`,
        { value, from },
      ),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Record the active sheet's late percent from a date (null for the sheet's today). */
  async setLatePct(value: string, from: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/late-pct`, { value, from }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Change the active sheet's policy date; null leaves it blank (the sheet's today). */
  async setPolicyDate(value: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/policy-date`, { value }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Change the active sheet's period numbering from; null leaves it blank (off). */
  async setPeriodNumberingFrom(value: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/period-numbering-from`, { value }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Set the roles that may take an action on the active sheet; blank keeps the standing rule. */
  async setPermission(action: string, value: string): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(
        `/api/sheets/${encodeURIComponent(sheet)}/settings/permissions/${encodeURIComponent(action)}`,
        { value },
      ),
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
    this.dated.set(settings.dated);
    this.policyDate.set(settings.policyDate);
    this.periodNumberingFrom.set(settings.periodNumberingFrom);
    this.permissions.set(settings.permissions);
  }
}

// The sheet's settings: the role given each user name (clerk, the default, steward, or viewer, who
// sees everything and can change nothing) and the
// clerk limit, the highest due a clerk settles outright (above it the claim goes PENDING), the floor, the least a claim's live due can be,
// the cap, the most a person's due-total can be, the proof threshold, above which a claim's due (by the threshold
// in force on its date) cannot be settled or approved until the claim has a proof,
// the terms, the days after its date a claim is due by (fixed when the claim is added), and each
// grade's own terms, which a claim of that grade takes instead, and the late percent, what an overdue claim's
// due gains of its figure for every full 30 days it is overdue, and the policy date, before which a
// claim's date makes its figures round half up rather than half to even, and the period numbering
// from, on or after which a claim opened is numbered within its period, and the permission matrix,
// the roles that may open, settle, void, reassign, close and reopen (blank keeps the standing rule).
// The settings that carry a figure are dated: each is saved from the from-date typed beside it (blank
// for the sheet's today), keeping the values recorded before, and the value in force on the sheet's
// today is shown for each. Saving sends only the settings typed into since the last save; the clerk limit, the cap, the proof threshold, the policy date and
// the period numbering from count as typed into once focused, as a blank one (none, or the sheet's today) is a value of its own.
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
          (focus)="clerkLimitTyped.set(true)"
          (input)="clerkLimit.set(value($event)); clerkLimitTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-clerk-limit-from">Clerk limit from</label>
        <input
          id="setting-clerk-limit-from"
          data-testid="setting-clerk-limit-from"
          placeholder="yyyy-mm-dd"
          [value]="clerkLimitFrom()"
          (input)="clerkLimitFrom.set(value($event)); settingsError.set(null)"
        />
        <label for="setting-floor">Floor</label>
        <input
          id="setting-floor"
          data-testid="setting-floor"
          inputmode="decimal"
          [value]="floor()"
          (input)="floor.set(value($event)); floorTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-floor-from">Floor from</label>
        <input
          id="setting-floor-from"
          data-testid="setting-floor-from"
          placeholder="yyyy-mm-dd"
          [value]="floorFrom()"
          (input)="floorFrom.set(value($event)); settingsError.set(null)"
        />
        <label for="setting-cap">Cap</label>
        <input
          id="setting-cap"
          data-testid="setting-cap"
          inputmode="decimal"
          [value]="cap()"
          (focus)="capTyped.set(true)"
          (input)="cap.set(value($event)); capTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-cap-from">Cap from</label>
        <input
          id="setting-cap-from"
          data-testid="setting-cap-from"
          placeholder="yyyy-mm-dd"
          [value]="capFrom()"
          (input)="capFrom.set(value($event)); settingsError.set(null)"
        />
        <label for="setting-proof-threshold">Proof threshold</label>
        <input
          id="setting-proof-threshold"
          data-testid="setting-proof-threshold"
          inputmode="decimal"
          [value]="proofThreshold()"
          (focus)="proofThresholdTyped.set(true)"
          (input)="proofThreshold.set(value($event)); proofThresholdTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-proof-threshold-from">Proof threshold from</label>
        <input
          id="setting-proof-threshold-from"
          data-testid="setting-proof-threshold-from"
          placeholder="yyyy-mm-dd"
          [value]="proofThresholdFrom()"
          (input)="proofThresholdFrom.set(value($event)); settingsError.set(null)"
        />
        <label for="setting-terms">Terms (days)</label>
        <input
          id="setting-terms"
          data-testid="setting-terms"
          inputmode="numeric"
          [value]="terms()"
          (input)="terms.set(value($event)); termsTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-terms-from">Terms from</label>
        <input
          id="setting-terms-from"
          data-testid="setting-terms-from"
          placeholder="yyyy-mm-dd"
          [value]="termsFrom()"
          (input)="termsFrom.set(value($event)); settingsError.set(null)"
        />
        @for (g of grades; track g) {
          <label [for]="'setting-terms-' + g">Terms for grade {{ g }} (days)</label>
          <input
            [id]="'setting-terms-' + g"
            [attr.data-testid]="'setting-terms-' + g"
            inputmode="numeric"
            [value]="gradeTerms()[g] ?? ''"
            (input)="typeGradeTerms(g, value($event))"
          />
          <label [for]="'setting-terms-' + g + '-from'">Terms for grade {{ g }} from</label>
          <input
            [id]="'setting-terms-' + g + '-from'"
            [attr.data-testid]="'setting-terms-' + g + '-from'"
            placeholder="yyyy-mm-dd"
            [value]="gradeTermsFrom()[g] ?? ''"
            (input)="typeGradeTermsFrom(g, value($event))"
          />
        }
        <label for="setting-late-pct">Late percent</label>
        <input
          id="setting-late-pct"
          data-testid="setting-late-pct"
          inputmode="decimal"
          [value]="latePct()"
          (input)="latePct.set(value($event)); latePctTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-late-pct-from">Late percent from</label>
        <input
          id="setting-late-pct-from"
          data-testid="setting-late-pct-from"
          placeholder="yyyy-mm-dd"
          [value]="latePctFrom()"
          (input)="latePctFrom.set(value($event)); settingsError.set(null)"
        />
        <label for="setting-policy-date">Policy date</label>
        <input
          id="setting-policy-date"
          data-testid="setting-policy-date"
          placeholder="yyyy-mm-dd"
          [value]="policyDate()"
          (focus)="policyDateTyped.set(true)"
          (input)="policyDate.set(value($event)); policyDateTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-period-numbering-from">Period numbering from</label>
        <input
          id="setting-period-numbering-from"
          data-testid="setting-period-numbering-from"
          placeholder="yyyy-mm-dd"
          [value]="periodNumberingFrom()"
          (focus)="periodNumberingFromTyped.set(true)"
          (input)="periodNumberingFrom.set(value($event)); periodNumberingFromTyped.set(true); settingsError.set(null)"
        />
        <fieldset>
          <legend>Permissions (roles, comma separated; blank for the standing rule)</legend>
          @for (p of permissionActions; track p.action) {
            <label [for]="'setting-perm-' + p.action">{{ p.label }}</label>
            <input
              [id]="'setting-perm-' + p.action"
              [attr.data-testid]="'setting-perm-' + p.action"
              [value]="permissions()[p.action] ?? ''"
              (focus)="typePermission(p.action, permissions()[p.action] ?? '')"
              (input)="typePermission(p.action, value($event))"
            />
          }
        </fieldset>
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
      <p>
        Cap in force:
        <strong data-testid="setting-cap-current">{{ store.cap() ?? '' }}</strong>
        @if (store.cap() === null) {
          <span>none</span>
        }
      </p>
      <p>
        Proof threshold in force:
        <strong data-testid="setting-proof-threshold-current">{{ store.proofThreshold() ?? '' }}</strong>
        @if (store.proofThreshold() === null) {
          <span>none</span>
        }
      </p>
      <p>
        Terms in force:
        <strong data-testid="setting-terms-current">{{ store.terms() }}</strong> days
      </p>
      @for (g of grades; track g) {
        <p>
          Terms in force for grade {{ g }}:
          <strong [attr.data-testid]="'setting-terms-' + g + '-current'">{{ store.termsOf(g) }}</strong> days
          @if (store.gradeTerms()[g] === undefined) {
            <span>(the sheet's terms)</span>
          }
        </p>
      }
      <p>
        Late percent in force:
        <strong data-testid="setting-late-pct-current">{{ store.latePct() }}</strong>
      </p>
      <p>
        Policy date in force:
        <strong data-testid="setting-policy-date-current">{{ store.policyDate() }}</strong>
        @if (store.policyDate() === '') {
          <span>the sheet's today</span>
        }
      </p>
      <p>
        Period numbering from:
        <strong data-testid="setting-period-numbering-from-current">{{ store.periodNumberingFrom() }}</strong>
        @if (store.periodNumberingFrom() === '') {
          <span>off</span>
        }
      </p>
      @for (p of permissionActions; track p.action) {
        <p>
          {{ p.label }} allowed for:
          <strong [attr.data-testid]="'setting-perm-' + p.action + '-current'">{{ store.permissions()[p.action] ?? '' }}</strong>
          @if (!store.permissions()[p.action]) {
            <span>{{ p.standing }} (the standing rule)</span>
          }
        </p>
      }
    </section>
  `,
})
export class Settings {
  protected readonly store = inject(SettingStore);
  protected readonly roles = ROLES;
  protected readonly grades = GRADES;
  protected readonly permissionActions = PERMISSIONS;

  protected readonly user = signal('');
  protected readonly role = signal('');
  protected readonly roleError = signal<string | null>(null);
  protected readonly clerkLimit = signal('');
  protected readonly clerkLimitTyped = signal(false);
  protected readonly clerkLimitFrom = signal('');
  protected readonly floor = signal('');
  protected readonly floorTyped = signal(false);
  protected readonly floorFrom = signal('');
  protected readonly cap = signal('');
  protected readonly capTyped = signal(false);
  protected readonly capFrom = signal('');
  protected readonly proofThreshold = signal('');
  protected readonly proofThresholdTyped = signal(false);
  protected readonly proofThresholdFrom = signal('');
  protected readonly terms = signal('');
  protected readonly termsTyped = signal(false);
  protected readonly termsFrom = signal('');
  /** The grades' terms typed since the last save, by grade. */
  protected readonly gradeTerms = signal<Record<string, string>>({});
  /** The from-dates typed for the grades' terms, by grade; blank for the sheet's today. */
  protected readonly gradeTermsFrom = signal<Record<string, string>>({});
  protected readonly latePct = signal('');
  protected readonly latePctTyped = signal(false);
  protected readonly latePctFrom = signal('');
  protected readonly policyDate = signal('');
  protected readonly policyDateTyped = signal(false);
  protected readonly periodNumberingFrom = signal('');
  protected readonly periodNumberingFromTyped = signal(false);
  /** The permission entries typed since the last save, by action; a focused one counts as typed, as
   *  a blank one (the standing rule) is a value of its own. */
  protected readonly permissions = signal<Record<string, string>>({});
  protected readonly settingsError = signal<string | null>(null);
  /** Whether a role submit is waiting for its answer; a second one meanwhile is dropped. */
  private readonly busy = signal(false);
  /** The settings saves submitted, each run after the one before. */
  private settingsSaves: Promise<void> = Promise.resolve();

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected typeGradeTerms(grade: string, typed: string): void {
    this.gradeTerms.update((all) => ({ ...all, [grade]: typed }));
    this.settingsError.set(null);
  }

  protected typePermission(action: string, typed: string): void {
    this.permissions.update((all) => ({ ...all, [action]: typed }));
    this.settingsError.set(null);
  }

  protected typeGradeTermsFrom(grade: string, typed: string): void {
    this.gradeTermsFrom.update((all) => ({ ...all, [grade]: typed }));
    this.settingsError.set(null);
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

  /** Save the settings typed. A submit while an earlier one is waiting for its answer is saved
   *  after it, with the values typed when it was submitted. */
  protected async saveSettings(event: Event): Promise<void> {
    event.preventDefault();
    this.settingsError.set(null);
    const limit = this.clerkLimit().trim();
    const floor = this.floor().trim();
    const saveLimit = this.clerkLimitTyped();
    const saveFloor = this.floorTyped();
    const cap = this.cap().trim();
    const saveCap = this.capTyped();
    const threshold = this.proofThreshold().trim();
    const saveThreshold = this.proofThresholdTyped();
    const terms = this.terms().trim();
    const saveTerms = this.termsTyped();
    const gradeTerms = Object.entries(this.gradeTerms()).map(([grade, days]) => [grade, days.trim()] as const);
    const latePct = this.latePct().trim();
    const saveLatePct = this.latePctTyped();
    const policyDate = this.policyDate().trim();
    const savePolicyDate = this.policyDateTyped();
    const numberingFrom = this.periodNumberingFrom().trim();
    const saveNumberingFrom = this.periodNumberingFromTyped();
    const permissions = Object.entries(this.permissions()).map(
      ([action, roles]) => [action, roles.split(',').map((r) => r.trim()).filter((r) => r !== '').join(',')] as const,
    );
    const limitFrom = this.clerkLimitFrom().trim();
    const floorFrom = this.floorFrom().trim();
    const capFrom = this.capFrom().trim();
    const thresholdFrom = this.proofThresholdFrom().trim();
    const termsFrom = this.termsFrom().trim();
    const latePctFrom = this.latePctFrom().trim();
    const gradeTermsFrom: Record<string, string> = Object.fromEntries(
      Object.entries(this.gradeTermsFrom()).map(([grade, from]) => [grade, from.trim()]),
    );
    const froms: [boolean, string, string][] = [
      [saveLimit, limitFrom, 'the clerk limit'],
      [saveFloor, floorFrom, 'the floor'],
      [saveCap, capFrom, 'the cap'],
      [saveThreshold, thresholdFrom, 'the proof threshold'],
      [saveTerms, termsFrom, 'the terms'],
      ...gradeTerms.map(([grade]) => [true, gradeTermsFrom[grade] ?? '', `the terms for grade ${grade}`] as [boolean, string, string]),
      [saveLatePct, latePctFrom, 'the late percent'],
    ];
    if (saveLimit && limit !== '' && !AMOUNT.test(limit)) {
      this.settingsError.set('Type a clerk limit of 0.00 or more, with up to two decimals, or leave it blank for none.');
      return;
    }
    if (saveFloor && !AMOUNT.test(floor)) {
      this.settingsError.set('Type a floor of 0.00 or more, with up to two decimals.');
      return;
    }
    if (saveCap && cap !== '' && !AMOUNT.test(cap)) {
      this.settingsError.set('Type a cap of 0.00 or more, with up to two decimals, or leave it blank for none.');
      return;
    }
    if (saveThreshold && threshold !== '' && !AMOUNT.test(threshold)) {
      this.settingsError.set('Type a proof threshold of 0.00 or more, with up to two decimals, or leave it blank for none.');
      return;
    }
    if (saveTerms && !DAYS.test(terms)) {
      this.settingsError.set('Type terms of 0 or more whole days.');
      return;
    }
    const badGrade = gradeTerms.find(([, days]) => !DAYS.test(days));
    if (badGrade) {
      this.settingsError.set(`Type terms for grade ${badGrade[0]} of 0 or more whole days.`);
      return;
    }
    if (saveLatePct && !AMOUNT.test(latePct)) {
      this.settingsError.set('Type a late percent of 0.00 or more, with up to two decimals.');
      return;
    }
    const badFrom = froms.find(([save, from]) => save && from !== '' && !DATE.test(from));
    if (badFrom) {
      this.settingsError.set(`Type the from-date of ${badFrom[2]} as yyyy-mm-dd, or leave it blank for the sheet's today.`);
      return;
    }
    if (savePolicyDate && policyDate !== '' && !DATE.test(policyDate)) {
      this.settingsError.set('Type a policy date as yyyy-mm-dd, or leave it blank for the sheet\'s today.');
      return;
    }
    if (saveNumberingFrom && numberingFrom !== '' && !DATE.test(numberingFrom)) {
      this.settingsError.set('Type a period numbering from date as yyyy-mm-dd, or leave it blank for off.');
      return;
    }
    const badPermission = Object.entries(this.permissions()).find(([, roles]) => !rolesList(roles.trim()));
    if (badPermission) {
      this.settingsError.set(`Type the roles that may ${badPermission[0]} as clerk, steward or clerk,steward, or leave it blank for the standing rule.`);
      return;
    }
    const fromOf = (from: string) => (from === '' ? null : from);
    this.clerkLimitTyped.set(false);
    this.floorTyped.set(false);
    this.capTyped.set(false);
    this.proofThresholdTyped.set(false);
    this.termsTyped.set(false);
    this.gradeTerms.set({});
    this.latePctTyped.set(false);
    this.policyDateTyped.set(false);
    this.periodNumberingFromTyped.set(false);
    this.permissions.set({});
    const save = async () => {
      try {
        if (saveLimit) await this.store.setClerkLimit(limit === '' ? null : limit, fromOf(limitFrom));
        if (saveFloor) await this.store.setFloor(floor, fromOf(floorFrom));
        if (saveCap) await this.store.setCap(cap === '' ? null : cap, fromOf(capFrom));
        if (saveThreshold) await this.store.setProofThreshold(threshold === '' ? null : threshold, fromOf(thresholdFrom));
        if (saveTerms) await this.store.setTerms(Number(terms), fromOf(termsFrom));
        for (const [grade, days] of gradeTerms) {
          await this.store.setGradeTerms(grade, Number(days), fromOf(gradeTermsFrom[grade] ?? ''));
        }
        if (saveLatePct) await this.store.setLatePct(latePct, fromOf(latePctFrom));
        if (savePolicyDate) await this.store.setPolicyDate(policyDate === '' ? null : policyDate);
        if (saveNumberingFrom) await this.store.setPeriodNumberingFrom(numberingFrom === '' ? null : numberingFrom);
        for (const [action, roles] of permissions) await this.store.setPermission(action, roles);
      } catch {
        // What was typed stays to be saved again.
        if (saveLimit) this.clerkLimitTyped.set(true);
        if (saveFloor) this.floorTyped.set(true);
        if (saveCap) this.capTyped.set(true);
        if (saveThreshold) this.proofThresholdTyped.set(true);
        if (saveTerms) this.termsTyped.set(true);
        this.gradeTerms.update((typed) => ({ ...Object.fromEntries(gradeTerms), ...typed }));
        if (saveLatePct) this.latePctTyped.set(true);
        if (savePolicyDate) this.policyDateTyped.set(true);
        if (saveNumberingFrom) this.periodNumberingFromTyped.set(true);
        this.permissions.update((typed) => ({ ...Object.fromEntries(permissions), ...typed }));
        this.settingsError.set('The settings could not be saved.');
      }
    };
    this.settingsSaves = this.settingsSaves.then(save);
    await this.settingsSaves;
  }
}
