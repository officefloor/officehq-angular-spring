import { Component, inject, signal } from '@angular/core';
import { GRADES } from '../../core/people';
import { ROLES, Roles } from '../../core/roles';
import { Session } from '../../core/session';

// The sheet's settings: the clerk limit (the largest due a clerk may settle; blank for no limit), the
// floor (the least a claim's live due can be), the cap (the most a person's due-total can be; blank for none), the terms (the days after a claim's date it is due by, fixed
// when the claim is added), each grade's own terms (blank: the grade uses the sheet's terms), the
// late percent (what an overdue claim's due gains of its figure for every full 30 days), the policy
// date (a claim dated before it rounds half up, on or after it half to even; blank for the sheet's today), the
// period numbering from-date (while the sheet's today is on or after it, a claim opened is numbered within its
// period; blank for off), and
// the role each user name has on the sheet (clerk unless given another).
@Component({
  selector: 'app-settings',
  template: `
    <section aria-label="Settings" data-testid="settings">
      <h2>Settings</h2>
      <form (submit)="save($event)" data-testid="settings-form">
        <label for="setting-clerk-limit">Clerk limit (blank for none)</label>
        <input
          id="setting-clerk-limit"
          data-testid="setting-clerk-limit"
          inputmode="decimal"
          [value]="limit()"
          (input)="limit.set(value($event)); limitTouched.set(true); error.set(null)"
        />
        <p>In force: <strong data-testid="setting-clerk-limit-current">{{ session.clerkLimit() ?? '' }}</strong></p>
        <label for="setting-floor">Floor (the least a live due can be)</label>
        <input
          id="setting-floor"
          data-testid="setting-floor"
          inputmode="decimal"
          [value]="floor()"
          (input)="floor.set(value($event)); floorTouched.set(true); error.set(null)"
        />
        <p>In force: <strong data-testid="setting-floor-current">{{ session.floor() }}</strong></p>
        <label for="setting-cap">Cap (the most a person's due-total can be; blank for none)</label>
        <input
          id="setting-cap"
          data-testid="setting-cap"
          inputmode="decimal"
          [value]="cap()"
          (input)="cap.set(value($event)); capTouched.set(true); error.set(null)"
          (keydown.delete)="capTouched.set(true); error.set(null)"
          (keydown.backspace)="capTouched.set(true); error.set(null)"
        />
        <p>In force: <strong data-testid="setting-cap-current">{{ session.cap() ?? '' }}</strong></p>
        <label for="setting-terms">Terms (days after a claim's date it is due by)</label>
        <input
          id="setting-terms"
          data-testid="setting-terms"
          inputmode="numeric"
          [value]="terms()"
          (input)="terms.set(value($event)); termsTouched.set(true); error.set(null)"
        />
        <p>In force: <strong data-testid="setting-terms-current">{{ session.terms() }}</strong></p>
        @for (g of grades; track g) {
          <label [for]="'setting-terms-' + g">Terms for grade {{ g }} (blank for the sheet's terms)</label>
          <input
            [id]="'setting-terms-' + g"
            [attr.data-testid]="'setting-terms-' + g"
            inputmode="numeric"
            [value]="gradeTerms()[g] ?? ''"
            (input)="setGradeTerms(g, value($event))"
            (keydown.delete)="touchGradeTerms(g)"
            (keydown.backspace)="touchGradeTerms(g)"
          />
          <p>In force: <strong [attr.data-testid]="'setting-terms-' + g + '-current'">{{ session.gradeTerms()[g] ?? '' }}</strong></p>
        }
        <label for="setting-late-pct">Late percent (of an overdue claim's figure, per full 30 days)</label>
        <input
          id="setting-late-pct"
          data-testid="setting-late-pct"
          inputmode="decimal"
          [value]="latePct()"
          (input)="latePct.set(value($event)); latePctTouched.set(true); error.set(null)"
        />
        <p>In force: <strong data-testid="setting-late-pct-current">{{ session.latePct() }}</strong></p>
        <label for="setting-policy-date">Policy date (YYYY-MM-DD; blank for the sheet's today)</label>
        <input
          id="setting-policy-date"
          data-testid="setting-policy-date"
          [value]="policyDate()"
          (input)="policyDate.set(value($event)); policyDateTouched.set(true); error.set(null)"
          (keydown.delete)="policyDateTouched.set(true); error.set(null)"
          (keydown.backspace)="policyDateTouched.set(true); error.set(null)"
        />
        <p>In force: <strong data-testid="setting-policy-date-current">{{ session.policyDate() ?? '' }}</strong></p>
        <label for="setting-period-numbering-from">Period numbering from (YYYY-MM-DD; blank for off)</label>
        <input
          id="setting-period-numbering-from"
          data-testid="setting-period-numbering-from"
          [value]="numberingFrom()"
          (input)="numberingFrom.set(value($event)); numberingFromTouched.set(true); error.set(null)"
          (keydown.delete)="numberingFromTouched.set(true); error.set(null)"
          (keydown.backspace)="numberingFromTouched.set(true); error.set(null)"
        />
        <p>In force: <strong data-testid="setting-period-numbering-from-current">{{ session.periodNumberingFrom() ?? '' }}</strong></p>
        <button type="submit" data-testid="settings-save" [disabled]="saving()">Save settings</button>
        @if (error(); as err) {
          <p role="alert" data-testid="settings-error">{{ err }}</p>
        }
      </form>
      <h3>Roles</h3>
      <form (submit)="setRole($event)" data-testid="role-form">
        <label for="role-form-user">User name</label>
        <input
          id="role-form-user"
          data-testid="role-form-user"
          [value]="user()"
          (input)="user.set(value($event)); roleError.set(null)"
        />
        <label for="role-form-role">Role ({{ roleNames }})</label>
        <input
          id="role-form-role"
          data-testid="role-form-role"
          [value]="role()"
          (input)="role.set(value($event)); roleError.set(null)"
        />
        <button type="submit" data-testid="role-form-submit" [disabled]="settingRole()">Set role</button>
        @if (roleError(); as err) {
          <p role="alert" data-testid="role-form-error">{{ err }}</p>
        }
      </form>
      <table data-testid="role-list">
        <caption>Roles (anyone else is a clerk)</caption>
        <thead>
          <tr><th scope="col">User</th><th scope="col">Role</th></tr>
        </thead>
        <tbody>
          @for (r of roles.roles(); track r.user) {
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
export class SettingsPanel {
  protected readonly session = inject(Session);
  protected readonly roles = inject(Roles);
  protected readonly roleNames = ROLES.join(' or ');
  protected readonly limit = signal('');
  protected readonly limitTouched = signal(false);
  protected readonly floor = signal('');
  protected readonly floorTouched = signal(false);
  protected readonly cap = signal('');
  protected readonly capTouched = signal(false);
  protected readonly terms = signal('');
  protected readonly termsTouched = signal(false);
  protected readonly grades = GRADES;
  /** What is typed for each grade's terms, and the grades typed into since the last save. */
  protected readonly gradeTerms = signal<Record<string, string>>({});
  protected readonly gradeTermsTouched = signal<ReadonlySet<string>>(new Set());
  protected readonly latePct = signal('');
  protected readonly latePctTouched = signal(false);
  protected readonly policyDate = signal('');
  protected readonly policyDateTouched = signal(false);
  protected readonly numberingFrom = signal('');
  protected readonly numberingFromTouched = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly user = signal('');
  protected readonly role = signal('');
  protected readonly roleError = signal<string | null>(null);
  protected readonly settingRole = signal(false);

  protected setGradeTerms(grade: string, terms: string): void {
    this.gradeTerms.update((t) => ({ ...t, [grade]: terms }));
    this.touchGradeTerms(grade);
  }

  protected touchGradeTerms(grade: string): void {
    this.gradeTermsTouched.update((t) => new Set([...t, grade]));
    this.error.set(null);
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected async save(e: Event): Promise<void> {
    e.preventDefault();
    if (this.saving()) return; // a second submit before the first has answered
    this.error.set(null);
    this.saving.set(true);
    try {
      // Only the settings typed into since the last save change (a delete in a blank field that may be blank
      // counts: it saves the blank). With none, the settings that may be blank
      // are saved where they differ from the value in force (a blank field clears one), else the clerk limit.
      const untouched = !this.limitTouched() && !this.floorTouched() && !this.capTouched() && !this.termsTouched()
        && !this.latePctTouched() && !this.policyDateTouched() && !this.numberingFromTouched()
        && this.gradeTermsTouched().size === 0;
      const floor = this.floorTouched();
      const cap = this.capTouched()
        || (untouched && this.cap().trim() !== (this.session.cap() ?? ''));
      const terms = this.termsTouched();
      const latePct = this.latePctTouched();
      const policyDate = this.policyDateTouched()
        || (untouched && this.policyDate().trim() !== (this.session.policyDate() ?? ''));
      const numberingFrom = this.numberingFromTouched()
        || (untouched && this.numberingFrom().trim() !== (this.session.periodNumberingFrom() ?? ''));
      const limit = this.limitTouched() || (untouched && !cap && !policyDate && !numberingFrom)
        || (untouched && this.limit().trim() !== (this.session.clerkLimit() ?? ''));
      const err = (limit ? await this.session.setClerkLimit(this.limit()) : null)
        ?? (floor ? await this.session.setFloor(this.floor()) : null)
        ?? (cap ? await this.session.setCap(this.cap()) : null)
        ?? (terms ? await this.session.setTerms(this.terms()) : null)
        ?? (await this.saveGradeTerms())
        ?? (latePct ? await this.session.setLatePct(this.latePct()) : null)
        ?? (policyDate ? await this.session.setPolicyDate(this.policyDate()) : null)
        ?? (numberingFrom ? await this.session.setPeriodNumberingFrom(this.numberingFrom()) : null);
      if (err) this.error.set(err);
      else {
        this.limit.set('');
        this.limitTouched.set(false);
        this.floor.set('');
        this.floorTouched.set(false);
        this.cap.set('');
        this.capTouched.set(false);
        this.terms.set('');
        this.termsTouched.set(false);
        this.gradeTerms.set({});
        this.gradeTermsTouched.set(new Set());
        this.latePct.set('');
        this.latePctTouched.set(false);
        this.policyDate.set('');
        this.policyDateTouched.set(false);
        this.numberingFrom.set('');
        this.numberingFromTouched.set(false);
      }
    } finally {
      this.saving.set(false);
    }
  }

  /** Save the terms of each grade typed into, in grade order; the first refusal stops the rest. */
  private async saveGradeTerms(): Promise<string | null> {
    for (const g of this.grades) {
      if (!this.gradeTermsTouched().has(g)) continue;
      const err = await this.session.setGradeTerms(g, this.gradeTerms()[g] ?? '');
      if (err) return err;
    }
    return null;
  }

  protected async setRole(e: Event): Promise<void> {
    e.preventDefault();
    if (this.settingRole()) return; // a second submit before the first has answered
    this.roleError.set(null);
    this.settingRole.set(true);
    try {
      const err = await this.roles.set(this.user(), this.role());
      if (err) this.roleError.set(err);
      else {
        this.user.set('');
        this.role.set('');
      }
    } finally {
      this.settingRole.set(false);
    }
  }
}
