import { HttpClient } from '@angular/common/http';
import { Component, Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { AMOUNT } from '../claims/claims';
import { GRADES } from '../rates/rates';

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

const ROLES = ['clerk', 'steward'];

// The active sheet's settings, re-read whenever a sheet is loaded.
@Injectable({ providedIn: 'root' })
export class SettingStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly roles = signal<RoleView[]>([]);
  readonly clerkLimit = signal<string | null>(null);
  readonly floor = signal(FLOOR);
  readonly terms = signal(TERMS);
  readonly latePct = signal(LATE_PCT);
  readonly policyDate = signal('');
  readonly periodNumberingFrom = signal('');
  readonly gradeTerms = signal<Record<string, number>>({});

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      const load = this.session.loads();
      this.roles.set([]);
      this.clerkLimit.set(null);
      this.floor.set(FLOOR);
      this.terms.set(TERMS);
      this.latePct.set(LATE_PCT);
      this.policyDate.set('');
      this.periodNumberingFrom.set('');
      this.gradeTerms.set({});
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

  /** Change the active sheet's terms. */
  async setTerms(value: number): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/terms`, { value }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** The terms in days a claim of the grade is added with: the grade's own, else the sheet's. */
  termsOf(grade: string): number {
    return this.gradeTerms()[grade] ?? this.terms();
  }

  /** Set a grade's own terms on the active sheet. */
  async setGradeTerms(grade: string, value: number): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(
        `/api/sheets/${encodeURIComponent(sheet)}/settings/terms/${encodeURIComponent(grade)}`,
        { value },
      ),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Change the active sheet's late percent. */
  async setLatePct(value: string): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/late-pct`, { value }),
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
    this.terms.set(settings.terms);
    this.latePct.set(settings.latePct);
    this.policyDate.set(settings.policyDate);
    this.periodNumberingFrom.set(settings.periodNumberingFrom);
    this.gradeTerms.set(settings.gradeTerms);
  }
}

// The sheet's settings: the role given each user name (clerk, the default, or steward) and the
// clerk limit, the highest due a clerk settles outright (above it the claim goes PENDING), the floor, the least a claim's live due can be,
// the terms, the days after its date a claim is due by (fixed when the claim is added), and each
// grade's own terms, which a claim of that grade takes instead, and the late percent, what an overdue claim's
// due gains of its figure for every full 30 days it is overdue, and the policy date, before which a
// claim's date makes its figures round half up rather than half to even, and the period numbering
// from, on or after which a claim opened is numbered within its period.
// Saving sends only the settings typed into since the last save; the clerk limit, the policy date and
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
        <label for="setting-floor">Floor</label>
        <input
          id="setting-floor"
          data-testid="setting-floor"
          inputmode="decimal"
          [value]="floor()"
          (input)="floor.set(value($event)); floorTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-terms">Terms (days)</label>
        <input
          id="setting-terms"
          data-testid="setting-terms"
          inputmode="numeric"
          [value]="terms()"
          (input)="terms.set(value($event)); termsTyped.set(true); settingsError.set(null)"
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
        }
        <label for="setting-late-pct">Late percent</label>
        <input
          id="setting-late-pct"
          data-testid="setting-late-pct"
          inputmode="decimal"
          [value]="latePct()"
          (input)="latePct.set(value($event)); latePctTyped.set(true); settingsError.set(null)"
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
    </section>
  `,
})
export class Settings {
  protected readonly store = inject(SettingStore);
  protected readonly roles = ROLES;
  protected readonly grades = GRADES;

  protected readonly user = signal('');
  protected readonly role = signal('');
  protected readonly roleError = signal<string | null>(null);
  protected readonly clerkLimit = signal('');
  protected readonly clerkLimitTyped = signal(false);
  protected readonly floor = signal('');
  protected readonly floorTyped = signal(false);
  protected readonly terms = signal('');
  protected readonly termsTyped = signal(false);
  /** The grades' terms typed since the last save, by grade. */
  protected readonly gradeTerms = signal<Record<string, string>>({});
  protected readonly latePct = signal('');
  protected readonly latePctTyped = signal(false);
  protected readonly policyDate = signal('');
  protected readonly policyDateTyped = signal(false);
  protected readonly periodNumberingFrom = signal('');
  protected readonly periodNumberingFromTyped = signal(false);
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
    const terms = this.terms().trim();
    const saveTerms = this.termsTyped();
    const gradeTerms = Object.entries(this.gradeTerms()).map(([grade, days]) => [grade, days.trim()] as const);
    const latePct = this.latePct().trim();
    const saveLatePct = this.latePctTyped();
    const policyDate = this.policyDate().trim();
    const savePolicyDate = this.policyDateTyped();
    const numberingFrom = this.periodNumberingFrom().trim();
    const saveNumberingFrom = this.periodNumberingFromTyped();
    if (saveLimit && limit !== '' && !AMOUNT.test(limit)) {
      this.settingsError.set('Type a clerk limit of 0.00 or more, with up to two decimals, or leave it blank for none.');
      return;
    }
    if (saveFloor && !AMOUNT.test(floor)) {
      this.settingsError.set('Type a floor of 0.00 or more, with up to two decimals.');
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
    if (savePolicyDate && policyDate !== '' && !DATE.test(policyDate)) {
      this.settingsError.set('Type a policy date as yyyy-mm-dd, or leave it blank for the sheet\'s today.');
      return;
    }
    if (saveNumberingFrom && numberingFrom !== '' && !DATE.test(numberingFrom)) {
      this.settingsError.set('Type a period numbering from date as yyyy-mm-dd, or leave it blank for off.');
      return;
    }
    this.clerkLimitTyped.set(false);
    this.floorTyped.set(false);
    this.termsTyped.set(false);
    this.gradeTerms.set({});
    this.latePctTyped.set(false);
    this.policyDateTyped.set(false);
    this.periodNumberingFromTyped.set(false);
    const save = async () => {
      try {
        if (saveLimit) await this.store.setClerkLimit(limit === '' ? null : limit);
        if (saveFloor) await this.store.setFloor(floor);
        if (saveTerms) await this.store.setTerms(Number(terms));
        for (const [grade, days] of gradeTerms) await this.store.setGradeTerms(grade, Number(days));
        if (saveLatePct) await this.store.setLatePct(latePct);
        if (savePolicyDate) await this.store.setPolicyDate(policyDate === '' ? null : policyDate);
        if (saveNumberingFrom) await this.store.setPeriodNumberingFrom(numberingFrom === '' ? null : numberingFrom);
      } catch {
        // What was typed stays to be saved again.
        if (saveLimit) this.clerkLimitTyped.set(true);
        if (saveFloor) this.floorTyped.set(true);
        if (saveTerms) this.termsTyped.set(true);
        this.gradeTerms.update((typed) => ({ ...Object.fromEntries(gradeTerms), ...typed }));
        if (saveLatePct) this.latePctTyped.set(true);
        if (savePolicyDate) this.policyDateTyped.set(true);
        if (saveNumberingFrom) this.periodNumberingFromTyped.set(true);
        this.settingsError.set('The settings could not be saved.');
      }
    };
    this.settingsSaves = this.settingsSaves.then(save);
    await this.settingsSaves;
  }
}
