import { Component, computed, effect, inject, linkedSignal, signal, untracked } from '@angular/core';
import { AMOUNT } from '../../core/claims';
import { GRADES } from '../../core/people';
import { RATE } from '../../core/rates';
import { ROLES, RoleName, Roles } from '../../core/roles';
import { Session } from '../../core/session';

// The sheet's settings (the clerk limit, the floor, the terms, each grade's own terms, the late percent, the policy date and the period numbering from) and the roles given to user names.
@Component({
  selector: 'app-settings',
  template: `
    <section aria-labelledby="settings-heading">
      <h2 id="settings-heading">Settings</h2>

      <p>Clerk limit <strong data-testid="setting-clerk-limit-current">{{ session.clerkLimit() ?? '' }}</strong></p>
      <form (submit)="saveSettings($event)">
        <label for="setting-clerk-limit">Clerk limit (blank for no limit)</label>
        <input
          id="setting-clerk-limit"
          inputmode="decimal"
          data-testid="setting-clerk-limit"
          [value]="clerkLimit()"
          (input)="clerkLimit.set($any($event.target).value); settingsError.set(null)"
        />
        <p>Floor <strong data-testid="setting-floor-current">{{ session.floor() ?? '' }}</strong></p>
        <label for="setting-floor">Floor (the least any live due can be)</label>
        <input
          id="setting-floor"
          inputmode="decimal"
          data-testid="setting-floor"
          [value]="floor()"
          (input)="floor.set($any($event.target).value); settingsError.set(null)"
        />
        <p>Terms (days) <strong data-testid="setting-terms-current">{{ session.terms() ?? '' }}</strong></p>
        <label for="setting-terms">Terms (days after a claim's date it is due by)</label>
        <input
          id="setting-terms"
          inputmode="numeric"
          data-testid="setting-terms"
          [value]="terms()"
          (input)="terms.set($any($event.target).value); settingsError.set(null)"
        />
        @for (g of grades; track g) {
          <p>Terms for grade {{ g }} (days) <strong [attr.data-testid]="'setting-terms-' + g + '-current'">{{ session.gradeTerms()[g] ?? '' }}</strong></p>
          <label [for]="'setting-terms-' + g">Terms for grade {{ g }} (days; blank to use the sheet's terms)</label>
          <input
            [id]="'setting-terms-' + g"
            inputmode="numeric"
            [attr.data-testid]="'setting-terms-' + g"
            [value]="gradeTerms()[g] ?? ''"
            (input)="setGradeTerms(g, $any($event.target).value)"
          />
        }
        <p>Late percent <strong data-testid="setting-late-pct-current">{{ session.latePct() ?? '' }}</strong></p>
        <label for="setting-late-pct">Late percent (added per full 30 days a claim is overdue)</label>
        <input
          id="setting-late-pct"
          inputmode="decimal"
          data-testid="setting-late-pct"
          [value]="latePct()"
          (input)="latePct.set($any($event.target).value); settingsError.set(null)"
        />
        <p>Policy date <strong data-testid="setting-policy-date-current">{{ session.policyDate() ?? '' }}</strong></p>
        <label for="setting-policy-date">Policy date (claims dated before it round half up, others half to even; blank for today)</label>
        <input
          id="setting-policy-date"
          type="date"
          data-testid="setting-policy-date"
          [value]="policyDate()"
          (input)="policyDate.set($any($event.target).value); settingsError.set(null)"
        />
        <p>Period numbering from <strong data-testid="setting-period-numbering-from-current">{{ session.periodNumberingFrom() ?? '' }}</strong></p>
        <label for="setting-period-numbering-from">Period numbering from (claims opened from this date are numbered within their period; blank for off)</label>
        <input
          id="setting-period-numbering-from"
          type="date"
          data-testid="setting-period-numbering-from"
          [value]="periodNumberingFrom()"
          (input)="periodNumberingFrom.set($any($event.target).value); settingsError.set(null)"
        />
        <button type="submit" data-testid="settings-save">Save settings</button>
        @if (settingsError(); as err) {
          <p role="alert" data-testid="settings-error">{{ err }}</p>
        }
      </form>

      <h3>Roles</h3>
      <form (submit)="saveRole($event)">
        <label for="role-user">User name</label>
        <input
          id="role-user"
          data-testid="role-form-user"
          [value]="user()"
          (input)="user.set($any($event.target).value); roleError.set(null)"
        />
        <label for="role-role">Role (clerk or steward)</label>
        <input
          id="role-role"
          data-testid="role-form-role"
          [value]="role()"
          (input)="role.set($any($event.target).value); roleError.set(null)"
        />
        <button type="submit" data-testid="role-form-submit">Set role</button>
        @if (roleError(); as err) {
          <p role="alert" data-testid="role-form-error">{{ err }}</p>
        }
      </form>

      <table>
        <caption>Roles (any other name is a clerk; the name being entered is shown with its role)</caption>
        <thead>
          <tr><th scope="col">User</th><th scope="col">Role</th></tr>
        </thead>
        <tbody>
          @for (r of roles.all(); track r.user) {
            <tr [attr.data-testid]="'role-row-' + r.user">
              <td data-testid="role-user">{{ r.user }}</td>
              <td data-testid="role-value">{{ r.role }}</td>
            </tr>
          }
          @if (typedDefault(); as name) {
            <tr [attr.data-testid]="'role-row-' + name">
              <td data-testid="role-user">{{ name }}</td>
              <td data-testid="role-value">clerk</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class SettingsPanel {
  protected readonly session = inject(Session);
  protected readonly roles = inject(Roles);

  protected readonly clerkLimit = linkedSignal(() => this.session.clerkLimit() ?? '');
  protected readonly floor = linkedSignal(() => this.session.floor() ?? '');
  protected readonly terms = linkedSignal(() => String(this.session.terms() ?? ''));
  protected readonly grades = GRADES;
  protected readonly gradeTerms = linkedSignal<Record<string, string>>(() => {
    const own = this.session.gradeTerms();
    return Object.fromEntries(GRADES.map((g) => [g, own[g] == null ? '' : String(own[g])]));
  });
  protected readonly latePct = linkedSignal(() => this.session.latePct() ?? '');
  protected readonly policyDate = linkedSignal(() => this.session.policyDate() ?? '');
  protected readonly periodNumberingFrom = linkedSignal(() => this.session.periodNumberingFrom() ?? '');
  protected readonly settingsError = signal<string | null>(null);
  protected readonly user = signal('');
  protected readonly role = signal('');
  protected readonly roleError = signal<string | null>(null);
  /** The name being entered in the role form when it has no role given, so it shows as a clerk. */
  protected readonly typedDefault = computed(() => {
    const name = this.user().trim();
    return name && !this.roles.all().some((r) => r.user === name) ? name : null;
  });
  /** Set while a submit awaits the store, so a second submit does not store it twice. */
  private savingSettings = false;
  private savingRole = false;

  constructor() {
    // Re-read the store each time the sheet is (re-)loaded.
    effect(() => {
      this.session.loads();
      untracked(() => {
        this.settingsError.set(null);
        this.roleError.set(null);
        void this.session.track(this.roles.reload());
      });
    });
  }

  protected setGradeTerms(grade: string, value: string): void {
    this.gradeTerms.update((all) => ({ ...all, [grade]: value }));
    this.settingsError.set(null);
  }

  protected async saveSettings(event: Event): Promise<void> {
    event.preventDefault();
    if (this.savingSettings) return;
    this.settingsError.set(null);
    const limit = this.clerkLimit().trim();
    if (limit && !AMOUNT.test(limit)) {
      this.settingsError.set('Enter a clerk limit of 0.00 or more, with at most two decimals, or leave it blank.');
      return;
    }
    const floor = this.floor().trim();
    if (!AMOUNT.test(floor)) {
      this.settingsError.set('Enter a floor of 0.00 or more, with at most two decimals.');
      return;
    }
    const terms = this.terms().trim();
    if (!/^\d{1,4}$/.test(terms)) {
      this.settingsError.set('Enter terms as a whole number of days, from 0 to 9999.');
      return;
    }
    const gradeTerms: Record<string, string> = {};
    for (const g of GRADES) {
      const days = (this.gradeTerms()[g] ?? '').trim();
      if (days && !/^\d{1,4}$/.test(days)) {
        this.settingsError.set(`Enter terms for grade ${g} as a whole number of days, from 0 to 9999, or leave them blank for the sheet's terms.`);
        return;
      }
      gradeTerms[g] = days;
    }
    const latePct = this.latePct().trim();
    if (!RATE.test(latePct)) {
      this.settingsError.set('Enter a late percent of 0.00 or more, below 1000, with at most two decimals.');
      return;
    }
    const policyDate = this.policyDate().trim();
    if (policyDate && !/^\d{4}-\d{2}-\d{2}$/.test(policyDate)) {
      this.settingsError.set('Enter a policy date as yyyy-mm-dd, or leave it blank for the sheet\'s today.');
      return;
    }
    const periodNumberingFrom = this.periodNumberingFrom().trim();
    if (periodNumberingFrom && !/^\d{4}-\d{2}-\d{2}$/.test(periodNumberingFrom)) {
      this.settingsError.set('Enter a period numbering from date as yyyy-mm-dd, or leave it blank for off.');
      return;
    }
    this.savingSettings = true;
    try {
      await this.session.setSettings(limit || null, floor, terms, latePct, policyDate, periodNumberingFrom, gradeTerms);
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
    const user = this.user().trim();
    const role = this.role().trim();
    if (!user) {
      this.roleError.set('Enter a user name.');
      return;
    }
    if (!ROLES.includes(role as RoleName)) {
      this.roleError.set('Enter a role: clerk or steward.');
      return;
    }
    this.savingRole = true;
    try {
      await this.roles.set(user, role as RoleName);
      this.user.set('');
      this.role.set('');
    } catch {
      this.roleError.set('The role could not be set.');
    } finally {
      this.savingRole = false;
    }
  }
}
