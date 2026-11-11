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
  /** Each grade's own terms in days, by grade; a grade left out uses the sheet's terms. */
  gradeTerms: Record<string, number>;
  /** The percent of its figure an overdue claim's due gains every full 30 days, two decimals. */
  latePct: string;
  /** The date (yyyy-mm-dd) before which a claim rounds half up, and on or after which half to even; null: the today. */
  policyDate: string | null;
  /** The date (yyyy-mm-dd) from which, on the sheet's today, a claim opened is numbered within its period; null: off. */
  periodNumberingFrom: string | null;
  /** The most a person's due-total can be, two decimals; null when there is no cap. */
  cap: string | null;
}

interface RoleView {
  user: string;
  /** clerk or steward; a user name not listed is a clerk. */
  role: string;
}

const ROLES = ['clerk', 'steward'];
const GRADES = ['A', 'B', 'C'];

// The sheet's settings (its clerk limit, its floor, its terms and each grade's own, its late percent, its policy date, its period numbering from and its cap) and the roles of its user names, each with the form that sets it.
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
        @for (g of grades; track g) {
          <label [for]="'setting-terms-' + g">Terms for grade {{ g }} (blank: the sheet's terms)</label>
          <input
            [id]="'setting-terms-' + g"
            [attr.data-testid]="'setting-terms-' + g"
            inputmode="numeric"
            [value]="gradeTerms()[g] ?? ''"
            (input)="setGradeTerms(g, value($event)); settingsError.set(null)"
          />
        }
        <label for="setting-late-pct">Late percent (of an overdue claim's due, every full 30 days)</label>
        <input
          id="setting-late-pct"
          data-testid="setting-late-pct"
          inputmode="decimal"
          [value]="latePct()"
          (input)="latePct.set(value($event)); latePctEdited = true; settingsError.set(null)"
        />
        <label for="setting-policy-date">Policy date (claims before it round half up, others half to even; blank: today)</label>
        <input
          id="setting-policy-date"
          data-testid="setting-policy-date"
          type="date"
          [value]="policyDate()"
          (input)="policyDate.set(value($event)); policyDateEdited = true; settingsError.set(null)"
        />
        <label for="setting-period-numbering-from">Period numbering from (claims opened from then are numbered within their period; blank: off)</label>
        <input
          id="setting-period-numbering-from"
          data-testid="setting-period-numbering-from"
          type="date"
          [value]="periodNumberingFrom()"
          (input)="periodNumberingFrom.set(value($event)); periodNumberingFromEdited = true; settingsError.set(null)"
        />
        <label for="setting-cap">Cap (the most a person's due-total can be; blank: no cap)</label>
        <input
          id="setting-cap"
          data-testid="setting-cap"
          inputmode="decimal"
          [value]="cap()"
          (input)="cap.set(value($event)); capEdited = true; settingsError.set(null)"
        />
        <button type="submit" data-testid="settings-save">Save settings</button>
        @if (settingsError(); as message) {
          <p role="alert" data-testid="settings-error">{{ message }}</p>
        }
      </form>
      <p>Clerk limit in force: <strong data-testid="setting-clerk-limit-current">{{ current() ?? '' }}</strong></p>
      <p>Floor in force: <strong data-testid="setting-floor-current">{{ session.floor() ?? '' }}</strong></p>
      <p>Terms in force: <strong data-testid="setting-terms-current">{{ session.terms() ?? '' }}</strong></p>
      @for (g of grades; track g) {
        <p>Terms for grade {{ g }} in force: <strong [attr.data-testid]="'setting-terms-' + g + '-current'">{{ gradeTermsCurrent()[g] ?? '' }}</strong></p>
      }
      <p>Late percent in force: <strong data-testid="setting-late-pct-current">{{ session.latePct() ?? '' }}</strong></p>
      <p>Policy date in force: <strong data-testid="setting-policy-date-current">{{ session.policyDate() ?? '' }}</strong></p>
      <p>Period numbering from: <strong data-testid="setting-period-numbering-from-current">{{ periodNumberingFromCurrent() ?? '' }}</strong></p>
      <p>Cap in force: <strong data-testid="setting-cap-current">{{ session.cap() ?? '' }}</strong></p>
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
  protected readonly grades = GRADES;
  /** Each grade's terms as typed, and as in force (blank when the grade has none of its own). */
  protected readonly gradeTerms = signal<Record<string, string>>({});
  protected readonly gradeTermsCurrent = signal<Record<string, string>>({});
  /** The grades whose terms were typed in since the form was last saved or read. */
  protected gradeTermsEdited = new Set<string>();
  protected readonly current = signal<string | null>(null);
  protected readonly clerkLimit = signal('');
  protected readonly floor = signal('');
  protected readonly terms = signal('');
  protected readonly latePct = signal('');
  protected readonly policyDate = signal('');
  protected readonly periodNumberingFrom = signal('');
  protected readonly periodNumberingFromCurrent = signal<string | null>(null);
  protected readonly cap = signal('');
  /** Which settings were typed in since the form was last saved or read: a save sends only those. */
  protected clerkLimitEdited = false;
  protected floorEdited = false;
  protected termsEdited = false;
  protected latePctEdited = false;
  protected policyDateEdited = false;
  protected periodNumberingFromEdited = false;
  protected capEdited = false;
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
        this.gradeTermsCurrent.set({});
        this.session.latePct.set(null);
        this.session.policyDate.set(null);
        this.periodNumberingFromCurrent.set(null);
        this.session.cap.set(null);
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

  protected setGradeTerms(grade: string, terms: string): void {
    this.gradeTerms.update((all) => ({ ...all, [grade]: terms }));
    this.gradeTermsEdited.add(grade);
  }

  protected async saveSettings(event: Event): Promise<void> {
    event.preventDefault();
    if (this.savingSettings) return;
    this.settingsError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    // Saving with nothing typed saves the clerk limit as it stands.
    const saveLimit = this.clerkLimitEdited || (!this.floorEdited && !this.termsEdited && this.gradeTermsEdited.size === 0 && !this.latePctEdited && !this.policyDateEdited && !this.periodNumberingFromEdited && !this.capEdited);
    const saveFloor = this.floorEdited;
    const saveTerms = this.termsEdited;
    const saveGradeTerms = GRADES.filter((g) => this.gradeTermsEdited.has(g));
    const saveLatePct = this.latePctEdited;
    const savePolicyDate = this.policyDateEdited;
    const savePeriodNumberingFrom = this.periodNumberingFromEdited;
    const saveCap = this.capEdited;
    const limit = this.clerkLimit().trim();
    const floor = this.floor().trim();
    const terms = this.terms().trim();
    const latePct = this.latePct().trim();
    const policyDate = this.policyDate().trim();
    const periodNumberingFrom = this.periodNumberingFrom().trim();
    const cap = this.cap().trim();
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
    if (saveGradeTerms.some((g) => !/^\d{1,5}$/.test((this.gradeTerms()[g] ?? '').trim()))) {
      this.settingsError.set('Type terms for a grade of 0 or more whole days.');
      return;
    }
    if (saveLatePct && !/^\d{1,3}(\.\d{1,2})?$/.test(latePct)) {
      this.settingsError.set('Type a late percent of 0.00 or more with at most two decimals.');
      return;
    }
    if (savePolicyDate && policyDate !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(policyDate)) {
      this.settingsError.set('Type a policy date as yyyy-mm-dd, or leave it blank for the sheet\'s today.');
      return;
    }
    if (savePeriodNumberingFrom && periodNumberingFrom !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(periodNumberingFrom)) {
      this.settingsError.set('Type a period numbering from date as yyyy-mm-dd, or leave it blank for off.');
      return;
    }
    if (saveCap && cap !== '' && !/^\d{1,10}(\.\d{1,2})?$/.test(cap)) {
      this.settingsError.set('Type a cap of 0.00 or more with at most two decimals, or leave it blank for no cap.');
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
      for (const g of saveGradeTerms) {
        const gradeTerms = (this.gradeTerms()[g] ?? '').trim();
        saved = await firstValueFrom(
          this.http.put<SettingsView>(`${url}/terms/${g}`, { terms: gradeTerms }, this.session.headers()),
        );
        this.gradeTermsEdited.delete(g);
      }
      if (saveLatePct) {
        saved = await firstValueFrom(this.http.put<SettingsView>(`${url}/late-pct`, { latePct }, this.session.headers()));
        this.latePctEdited = false;
      }
      if (savePolicyDate) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(
            `${url}/policy-date`,
            { policyDate: policyDate === '' ? null : policyDate },
            this.session.headers(),
          ),
        );
        this.policyDateEdited = false;
      }
      if (savePeriodNumberingFrom) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(
            `${url}/period-numbering-from`,
            { periodNumberingFrom: periodNumberingFrom === '' ? null : periodNumberingFrom },
            this.session.headers(),
          ),
        );
        this.periodNumberingFromEdited = false;
      }
      if (saveCap) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(`${url}/cap`, { cap: cap === '' ? null : cap }, this.session.headers()),
        );
        this.capEdited = false;
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
    const gradeTerms: Record<string, string> = {};
    for (const [g, t] of Object.entries(settings.gradeTerms)) gradeTerms[g] = String(t);
    this.gradeTermsCurrent.set(gradeTerms);
    this.gradeTerms.set(gradeTerms);
    this.session.latePct.set(settings.latePct);
    this.latePct.set(settings.latePct);
    this.session.policyDate.set(settings.policyDate ?? '');
    this.policyDate.set(settings.policyDate ?? '');
    this.periodNumberingFromCurrent.set(settings.periodNumberingFrom ?? '');
    this.periodNumberingFrom.set(settings.periodNumberingFrom ?? '');
    this.session.cap.set(settings.cap);
    this.cap.set(settings.cap ?? '');
    this.clerkLimitEdited = false;
    this.floorEdited = false;
    this.termsEdited = false;
    this.gradeTermsEdited.clear();
    this.latePctEdited = false;
    this.policyDateEdited = false;
    this.periodNumberingFromEdited = false;
    this.capEdited = false;
  }
}
