import { Component, inject, signal } from '@angular/core';
import { GRADES } from '../../core/people';
import { Pools } from '../../core/pools';
import { Rates } from '../../core/rates';
import { ROLES, Roles } from '../../core/roles';
import { PERMISSION_ACTIONS, Session } from '../../core/session';

// The sheet's settings. Those that carry a figure (the clerk limit, the floor, the cap, the proof threshold, each grade's proof threshold, the note threshold, the terms, each grade's
// terms and the late percent) are saved from a from-date (blank for the sheet's today), keeping the values
// already recorded; each shows the value in force on the sheet's today. The settings: the clerk limit (the largest due a clerk may settle; blank for no limit), the
// floor (the least a claim's live due can be), the cap (the most a person's due-total can be; blank for none), the proof threshold (a claim whose due is above
// the one in force on its date needs a proof to be settled or approved; blank for none), the note threshold (a claim whose due
// is above the one in force on its date cannot be opened with an empty note; blank for none), the terms (the business days after a claim's date it is due by, fixed
// when the claim is added), each grade's own terms (blank: the grade uses the sheet's terms), each grade's
// grace days (a claim whose person's grade on its date has grace then is overdue, and its late charge counted, only
// from its due-by plus them; blank for none), the late percent (what an overdue claim's due gains of its figure for every full 30 days), the policy
// date (a claim dated before it rounds half up, on or after it half to even; blank for the sheet's today), the
// period numbering from-date (while the sheet's today is on or after it, a claim opened is numbered within its
// period; blank for off), the grace days (a today change closes each open period whose to-date plus these days
// is before the new today; blank for off; not dated), the permission matrix (for each action, the roles that may take it, comma separated;
// blank keeps the standing rule; not dated), the entry date (while set, the default date of a new claim in
// place of the sheet's today; blank for off; not dated), and
// the role each user name has on the sheet (clerk unless given another), and the delegations of the steward role
// (a steward gives a user the role from a date to a date, inclusive, by the sheet's today).
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
          (focus)="focused.set('limit')"
        />
        <label for="setting-clerk-limit-from">Clerk limit from (YYYY-MM-DD; blank for the sheet's today)</label>
        <input
          id="setting-clerk-limit-from"
          data-testid="setting-clerk-limit-from"
          [value]="fromOf('clerk-limit')"
          (input)="setFrom('clerk-limit', value($event))"
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
        <label for="setting-floor-from">Floor from (YYYY-MM-DD; blank for the sheet's today)</label>
        <input
          id="setting-floor-from"
          data-testid="setting-floor-from"
          [value]="fromOf('floor')"
          (input)="setFrom('floor', value($event))"
        />
        <p>In force: <strong data-testid="setting-floor-current">{{ session.floor() }}</strong></p>
        <label for="setting-cap">Cap (the most a person's due-total can be; blank for none)</label>
        <input
          id="setting-cap"
          data-testid="setting-cap"
          inputmode="decimal"
          [value]="cap()"
          (input)="cap.set(value($event)); capTouched.set(true); error.set(null)"
          (focus)="focused.set('cap')"
          (keydown.delete)="capTouched.set(true); error.set(null)"
          (keydown.backspace)="capTouched.set(true); error.set(null)"
        />
        <label for="setting-cap-from">Cap from (YYYY-MM-DD; blank for the sheet's today)</label>
        <input
          id="setting-cap-from"
          data-testid="setting-cap-from"
          [value]="fromOf('cap')"
          (input)="setFrom('cap', value($event))"
        />
        <p>In force: <strong data-testid="setting-cap-current">{{ session.cap() ?? '' }}</strong></p>
        <label for="setting-proof-threshold">Proof threshold (a claim due above it needs a proof to be settled; blank for none)</label>
        <input
          id="setting-proof-threshold"
          data-testid="setting-proof-threshold"
          inputmode="decimal"
          [value]="proofThreshold()"
          (input)="proofThreshold.set(value($event)); proofThresholdTouched.set(true); error.set(null)"
          (focus)="focused.set('proof-threshold')"
          (keydown.delete)="proofThresholdTouched.set(true); error.set(null)"
          (keydown.backspace)="proofThresholdTouched.set(true); error.set(null)"
        />
        <label for="setting-proof-threshold-from">Proof threshold from (YYYY-MM-DD; blank for the sheet's today)</label>
        <input
          id="setting-proof-threshold-from"
          data-testid="setting-proof-threshold-from"
          [value]="fromOf('proof-threshold')"
          (input)="setFrom('proof-threshold', value($event))"
        />
        <p>In force: <strong data-testid="setting-proof-threshold-current">{{ session.proofThreshold() ?? '' }}</strong></p>
        @for (g of grades; track g) {
          <label [for]="'setting-proof-threshold-' + g">Proof threshold for grade {{ g }} (blank: the sheet's)</label>
          <input
            [id]="'setting-proof-threshold-' + g"
            [attr.data-testid]="'setting-proof-threshold-' + g"
            inputmode="decimal"
            [value]="gradeProofThreshold()[g] ?? ''"
            (input)="setGradeProofThreshold(g, value($event))"
            (focus)="focused.set('proof-threshold-' + g)"
            (keydown.delete)="touchGradeProofThreshold(g)"
            (keydown.backspace)="touchGradeProofThreshold(g)"
          />
          <label [for]="'setting-proof-threshold-' + g + '-from'">Proof threshold for grade {{ g }} from (YYYY-MM-DD; blank for the sheet's today)</label>
          <input
            [id]="'setting-proof-threshold-' + g + '-from'"
            [attr.data-testid]="'setting-proof-threshold-' + g + '-from'"
            [value]="fromOf('proof-threshold-' + g)"
            (input)="setFrom('proof-threshold-' + g, value($event))"
          />
          <p>In force: <strong [attr.data-testid]="'setting-proof-threshold-' + g + '-current'">{{ session.gradeProofThreshold()[g] ?? '' }}</strong></p>
        }
        <label for="setting-note-threshold">Note threshold (a claim due above it needs a note to be opened; blank for none)</label>
        <input
          id="setting-note-threshold"
          data-testid="setting-note-threshold"
          inputmode="decimal"
          [value]="noteThreshold()"
          (input)="noteThreshold.set(value($event)); noteThresholdTouched.set(true); error.set(null)"
          (focus)="focused.set('note-threshold')"
          (keydown.delete)="noteThresholdTouched.set(true); error.set(null)"
          (keydown.backspace)="noteThresholdTouched.set(true); error.set(null)"
        />
        <label for="setting-note-threshold-from">Note threshold from (YYYY-MM-DD; blank for the sheet's today)</label>
        <input
          id="setting-note-threshold-from"
          data-testid="setting-note-threshold-from"
          [value]="fromOf('note-threshold')"
          (input)="setFrom('note-threshold', value($event))"
        />
        <p>In force: <strong data-testid="setting-note-threshold-current">{{ session.noteThreshold() ?? '' }}</strong></p>
        <label for="setting-terms">Terms (business days after a claim's date it is due by)</label>
        <input
          id="setting-terms"
          data-testid="setting-terms"
          inputmode="numeric"
          [value]="terms()"
          (input)="terms.set(value($event)); termsTouched.set(true); error.set(null)"
        />
        <label for="setting-terms-from">Terms from (YYYY-MM-DD; blank for the sheet's today)</label>
        <input
          id="setting-terms-from"
          data-testid="setting-terms-from"
          [value]="fromOf('terms')"
          (input)="setFrom('terms', value($event))"
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
            (focus)="focused.set('terms-' + g)"
            (keydown.delete)="touchGradeTerms(g)"
            (keydown.backspace)="touchGradeTerms(g)"
          />
          <label [for]="'setting-terms-' + g + '-from'">Terms for grade {{ g }} from (YYYY-MM-DD; blank for the sheet's today)</label>
          <input
            [id]="'setting-terms-' + g + '-from'"
            [attr.data-testid]="'setting-terms-' + g + '-from'"
            [value]="fromOf('terms-' + g)"
            (input)="setFrom('terms-' + g, value($event))"
          />
          <p>In force: <strong [attr.data-testid]="'setting-terms-' + g + '-current'">{{ session.gradeTerms()[g] ?? '' }}</strong></p>
        }
        @for (g of grades; track g) {
          <label [for]="'setting-grace-' + g">Grace days for grade {{ g }} (days past a claim's due-by before it is overdue; blank for none)</label>
          <input
            [id]="'setting-grace-' + g"
            [attr.data-testid]="'setting-grace-' + g"
            inputmode="numeric"
            [value]="gradeGrace()[g] ?? ''"
            (input)="setGradeGrace(g, value($event))"
            (focus)="focused.set('grace-' + g)"
            (keydown.delete)="touchGradeGrace(g)"
            (keydown.backspace)="touchGradeGrace(g)"
          />
          <label [for]="'setting-grace-' + g + '-from'">Grace days for grade {{ g }} from (YYYY-MM-DD; blank for the sheet's today)</label>
          <input
            [id]="'setting-grace-' + g + '-from'"
            [attr.data-testid]="'setting-grace-' + g + '-from'"
            [value]="fromOf('grace-' + g)"
            (input)="setFrom('grace-' + g, value($event))"
          />
          <p>In force: <strong [attr.data-testid]="'setting-grace-' + g + '-current'">{{ session.gradeGrace()[g] ?? '' }}</strong></p>
        }
        <label for="setting-late-pct">Late percent (of an overdue claim's figure plus earlier charges, per full 30 days)</label>
        <input
          id="setting-late-pct"
          data-testid="setting-late-pct"
          inputmode="decimal"
          [value]="latePct()"
          (input)="latePct.set(value($event)); latePctTouched.set(true); error.set(null)"
        />
        <label for="setting-late-pct-from">Late percent from (YYYY-MM-DD; blank for the sheet's today)</label>
        <input
          id="setting-late-pct-from"
          data-testid="setting-late-pct-from"
          [value]="fromOf('late-pct')"
          (input)="setFrom('late-pct', value($event))"
        />
        <p>In force: <strong data-testid="setting-late-pct-current">{{ session.latePct() }}</strong></p>
        <label for="setting-policy-date">Policy date (YYYY-MM-DD; blank for the sheet's today)</label>
        <input
          id="setting-policy-date"
          data-testid="setting-policy-date"
          [value]="policyDate()"
          (input)="policyDate.set(value($event)); policyDateTouched.set(true); error.set(null)"
          (focus)="focused.set('policy-date')"
          (keydown.delete)="policyDateTouched.set(true); error.set(null)"
          (keydown.backspace)="policyDateTouched.set(true); error.set(null)"
        />
        <p>In force: <strong data-testid="setting-policy-date-current">{{ session.policyDate() ?? '' }}</strong></p>
        <label for="setting-entry-date">Entry date (YYYY-MM-DD; a new claim's default date; blank for the sheet's today)</label>
        <input
          id="setting-entry-date"
          data-testid="setting-entry-date"
          [value]="entryDate()"
          (input)="entryDate.set(value($event)); entryDateTouched.set(true); error.set(null)"
          (focus)="focused.set('entry-date')"
          (keydown.delete)="entryDateTouched.set(true); error.set(null)"
          (keydown.backspace)="entryDateTouched.set(true); error.set(null)"
        />
        <p>In force: <strong data-testid="setting-entry-date-current">{{ session.entryDate() ?? '' }}</strong></p>
        <label for="setting-period-numbering-from">Period numbering from (YYYY-MM-DD; blank for off)</label>
        <input
          id="setting-period-numbering-from"
          data-testid="setting-period-numbering-from"
          [value]="numberingFrom()"
          (input)="numberingFrom.set(value($event)); numberingFromTouched.set(true); error.set(null)"
          (focus)="focused.set('period-numbering-from')"
          (keydown.delete)="numberingFromTouched.set(true); error.set(null)"
          (keydown.backspace)="numberingFromTouched.set(true); error.set(null)"
        />
        <p>In force: <strong data-testid="setting-period-numbering-from-current">{{ session.periodNumberingFrom() ?? '' }}</strong></p>
        <label for="setting-grace-days">Grace days (a today change closes periods ended more than this many days before; blank for off)</label>
        <input
          id="setting-grace-days"
          data-testid="setting-grace-days"
          inputmode="numeric"
          [value]="graceDays()"
          (input)="graceDays.set(value($event)); graceDaysTouched.set(true); error.set(null)"
          (focus)="focused.set('grace-days')"
          (keydown.delete)="graceDaysTouched.set(true); error.set(null)"
          (keydown.backspace)="graceDaysTouched.set(true); error.set(null)"
        />
        <p>In force: <strong data-testid="setting-grace-days-current">{{ session.graceDays() ?? '' }}</strong></p>
        <label for="setting-pot">Pot (shared out among the members of each balanced pool that is in no other pool; blank for none)</label>
        <input
          id="setting-pot"
          data-testid="setting-pot"
          inputmode="decimal"
          [value]="pot()"
          (input)="pot.set(value($event)); potTouched.set(true); error.set(null)"
          (focus)="focused.set('pot')"
          (keydown.delete)="potTouched.set(true); error.set(null)"
          (keydown.backspace)="potTouched.set(true); error.set(null)"
        />
        <p>In force: <strong data-testid="setting-pot-current">{{ session.pot() ?? '' }}</strong></p>
        <label for="setting-parent">Parent sheet (its rates apply to a grade with none in force here; blank for none)</label>
        <input
          id="setting-parent"
          data-testid="setting-parent"
          [value]="parent()"
          (input)="parent.set(value($event)); parentTouched.set(true); error.set(null)"
          (focus)="focused.set('parent')"
          (keydown.delete)="parentTouched.set(true); error.set(null)"
          (keydown.backspace)="parentTouched.set(true); error.set(null)"
        />
        <p>In force: <strong data-testid="setting-parent-current">{{ session.parent() ?? '' }}</strong></p>
        @for (a of actions; track a) {
          <label [for]="'setting-perm-' + a">Roles that may {{ a }} (comma separated; blank for the standing rule)</label>
          <input
            [id]="'setting-perm-' + a"
            [attr.data-testid]="'setting-perm-' + a"
            [value]="perms()[a] ?? ''"
            (input)="setPerm(a, value($event))"
            (focus)="focused.set('perm-' + a)"
            (keydown.delete)="touchPerm(a)"
            (keydown.backspace)="touchPerm(a)"
          />
          <p>In force: <strong [attr.data-testid]="'setting-perm-' + a + '-current'">{{ session.permissions()[a] ?? '' }}</strong></p>
        }
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
      <h3>Delegations</h3>
      <form (submit)="delegate($event)" data-testid="delegate-form">
        <label for="delegate-user">User name</label>
        <input
          id="delegate-user"
          data-testid="delegate-user"
          [value]="delegateUser()"
          (input)="delegateUser.set(value($event)); delegateError.set(null)"
        />
        <label for="delegate-from">From (YYYY-MM-DD; blank for the sheet's today)</label>
        <input
          id="delegate-from"
          data-testid="delegate-from"
          [value]="delegateFrom()"
          (input)="delegateFrom.set(value($event)); delegateError.set(null)"
        />
        <label for="delegate-to">To (YYYY-MM-DD; blank for the from-date)</label>
        <input
          id="delegate-to"
          data-testid="delegate-to"
          [value]="delegateTo()"
          (input)="delegateTo.set(value($event)); delegateError.set(null)"
        />
        <button type="submit" data-testid="delegate-submit" [disabled]="delegating()">Delegate steward role</button>
        @if (delegateError(); as err) {
          <p role="alert" data-testid="delegate-error">{{ err }}</p>
        }
      </form>
      <ul data-testid="delegate-list" aria-label="Delegations">
        @for (d of roles.delegations(); track d.id) {
          <li data-testid="delegate-line">{{ d.delegator }} delegated steward to {{ d.user }} from {{ d.from }} to {{ d.to }}</li>
        }
      </ul>
    </section>
  `,
})
export class SettingsPanel {
  protected readonly session = inject(Session);
  protected readonly roles = inject(Roles);
  private readonly pools = inject(Pools);
  private readonly rates = inject(Rates);
  protected readonly roleNames = ROLES.join(' or ');
  protected readonly limit = signal('');
  protected readonly limitTouched = signal(false);
  protected readonly floor = signal('');
  protected readonly floorTouched = signal(false);
  protected readonly cap = signal('');
  protected readonly capTouched = signal(false);
  protected readonly proofThreshold = signal('');
  protected readonly proofThresholdTouched = signal(false);
  protected readonly noteThreshold = signal('');
  protected readonly noteThresholdTouched = signal(false);
  protected readonly terms = signal('');
  protected readonly termsTouched = signal(false);
  protected readonly grades = GRADES;
  /** What is typed for each grade's terms, and the grades typed into since the last save. */
  protected readonly gradeTerms = signal<Record<string, string>>({});
  protected readonly gradeTermsTouched = signal<ReadonlySet<string>>(new Set());
  /** What is typed for each grade's proof threshold, and the grades typed into since the last save. */
  protected readonly gradeProofThreshold = signal<Record<string, string>>({});
  protected readonly gradeProofThresholdTouched = signal<ReadonlySet<string>>(new Set());
  /** What is typed for each grade's grace days, and the grades typed into since the last save. */
  protected readonly gradeGrace = signal<Record<string, string>>({});
  protected readonly gradeGraceTouched = signal<ReadonlySet<string>>(new Set());
  protected readonly actions = PERMISSION_ACTIONS;
  /** What is typed for each action's roles, and the actions typed into since the last save. */
  protected readonly perms = signal<Record<string, string>>({});
  protected readonly permsTouched = signal<ReadonlySet<string>>(new Set());
  protected readonly latePct = signal('');
  protected readonly latePctTouched = signal(false);
  /** The from-date typed for each dated setting (blank: the sheet's today). */
  protected readonly froms = signal<Record<string, string>>({});
  protected readonly policyDate = signal('');
  protected readonly policyDateTouched = signal(false);
  protected readonly numberingFrom = signal('');
  protected readonly numberingFromTouched = signal(false);
  protected readonly graceDays = signal('');
  protected readonly graceDaysTouched = signal(false);
  protected readonly entryDate = signal('');
  protected readonly entryDateTouched = signal(false);
  protected readonly pot = signal('');
  protected readonly potTouched = signal(false);
  protected readonly parent = signal('');
  protected readonly parentTouched = signal(false);
  /** The setting that may be blank whose field was focused last: an untouched save (a blank typed into a
   *  blank field fires no input) saves that one. */
  protected readonly focused = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  protected readonly user = signal('');
  protected readonly role = signal('');
  protected readonly roleError = signal<string | null>(null);
  protected readonly settingRole = signal(false);
  protected readonly delegateUser = signal('');
  protected readonly delegateFrom = signal('');
  protected readonly delegateTo = signal('');
  protected readonly delegateError = signal<string | null>(null);
  protected readonly delegating = signal(false);

  protected fromOf(key: string): string {
    return this.froms()[key] ?? '';
  }

  protected setFrom(key: string, from: string): void {
    this.froms.update((f) => ({ ...f, [key]: from }));
    this.error.set(null);
  }

  protected setGradeTerms(grade: string, terms: string): void {
    this.gradeTerms.update((t) => ({ ...t, [grade]: terms }));
    this.touchGradeTerms(grade);
  }

  protected touchGradeTerms(grade: string): void {
    this.gradeTermsTouched.update((t) => new Set([...t, grade]));
    this.error.set(null);
  }

  protected setGradeProofThreshold(grade: string, threshold: string): void {
    this.gradeProofThreshold.update((t) => ({ ...t, [grade]: threshold }));
    this.touchGradeProofThreshold(grade);
  }

  protected touchGradeProofThreshold(grade: string): void {
    this.gradeProofThresholdTouched.update((t) => new Set([...t, grade]));
    this.error.set(null);
  }

  protected setGradeGrace(grade: string, days: string): void {
    this.gradeGrace.update((t) => ({ ...t, [grade]: days }));
    this.touchGradeGrace(grade);
  }

  protected touchGradeGrace(grade: string): void {
    this.gradeGraceTouched.update((t) => new Set([...t, grade]));
    this.error.set(null);
  }

  protected setPerm(action: string, roles: string): void {
    this.perms.update((p) => ({ ...p, [action]: roles }));
    this.touchPerm(action);
  }

  protected touchPerm(action: string): void {
    this.permsTouched.update((t) => new Set([...t, action]));
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
      // counts: it saves the blank). With none, the setting that may be blank whose field was focused last is
      // saved; with none focused, those that differ from the value in force (a blank field clears one), else
      // the clerk limit.
      const untouched = !this.limitTouched() && !this.floorTouched() && !this.capTouched() && !this.proofThresholdTouched()
        && !this.noteThresholdTouched()
        && !this.termsTouched()
        && !this.latePctTouched() && !this.policyDateTouched() && !this.numberingFromTouched()
        && !this.graceDaysTouched() && !this.entryDateTouched() && !this.potTouched() && !this.parentTouched()
        && this.gradeTermsTouched().size === 0 && this.gradeGraceTouched().size === 0
        && this.gradeProofThresholdTouched().size === 0
        && this.permsTouched().size === 0;
      const pick = untouched ? this.focused() : null;
      if (pick?.startsWith('terms-')) this.touchGradeTerms(pick.slice('terms-'.length));
      if (pick?.startsWith('perm-')) this.touchPerm(pick.slice('perm-'.length));
      const graceGrade = this.grades.find((g) => pick === 'grace-' + g);
      if (graceGrade) this.touchGradeGrace(graceGrade);
      const proofGrade = this.grades.find((g) => pick === 'proof-threshold-' + g);
      if (proofGrade) this.touchGradeProofThreshold(proofGrade);
      const guess = untouched && pick === null;
      const floor = this.floorTouched();
      const cap = this.capTouched() || pick === 'cap'
        || (guess && this.cap().trim() !== (this.session.cap() ?? ''));
      const proofThreshold = this.proofThresholdTouched() || pick === 'proof-threshold'
        || (guess && this.proofThreshold().trim() !== (this.session.proofThreshold() ?? ''));
      const noteThreshold = this.noteThresholdTouched() || pick === 'note-threshold'
        || (guess && this.noteThreshold().trim() !== (this.session.noteThreshold() ?? ''));
      const terms = this.termsTouched();
      const latePct = this.latePctTouched();
      const policyDate = this.policyDateTouched() || pick === 'policy-date'
        || (guess && this.policyDate().trim() !== (this.session.policyDate() ?? ''));
      const numberingFrom = this.numberingFromTouched() || pick === 'period-numbering-from'
        || (guess && this.numberingFrom().trim() !== (this.session.periodNumberingFrom() ?? ''));
      const graceDays = this.graceDaysTouched() || pick === 'grace-days'
        || (guess && this.graceDays().trim() !== (this.session.graceDays()?.toString() ?? ''));
      const entryDate = this.entryDateTouched() || pick === 'entry-date'
        || (guess && this.entryDate().trim() !== (this.session.entryDate() ?? ''));
      const pot = this.potTouched() || pick === 'pot'
        || (guess && this.pot().trim() !== (this.session.pot() ?? ''));
      const parent = this.parentTouched() || pick === 'parent'
        || (guess && this.parent().trim() !== (this.session.parent() ?? ''));
      const limit = this.limitTouched() || pick === 'limit'
        || (guess && !cap && !proofThreshold && !noteThreshold && !policyDate && !numberingFrom && !graceDays
          && !entryDate && !pot && !parent)
        || (guess && this.limit().trim() !== (this.session.clerkLimit() ?? ''));
      const err = (limit ? await this.session.setClerkLimit(this.limit(), this.fromOf('clerk-limit')) : null)
        ?? (floor ? await this.session.setFloor(this.floor(), this.fromOf('floor')) : null)
        ?? (cap ? await this.session.setCap(this.cap(), this.fromOf('cap')) : null)
        ?? (proofThreshold
          ? await this.session.setProofThreshold(this.proofThreshold(), this.fromOf('proof-threshold'))
          : null)
        ?? (await this.saveGradeProofThreshold())
        ?? (noteThreshold
          ? await this.session.setNoteThreshold(this.noteThreshold(), this.fromOf('note-threshold'))
          : null)
        ?? (terms ? await this.session.setTerms(this.terms(), this.fromOf('terms')) : null)
        ?? (await this.saveGradeTerms())
        ?? (await this.saveGradeGrace())
        ?? (await this.savePerms())
        ?? (latePct ? await this.session.setLatePct(this.latePct(), this.fromOf('late-pct')) : null)
        ?? (policyDate ? await this.session.setPolicyDate(this.policyDate()) : null)
        ?? (numberingFrom ? await this.session.setPeriodNumberingFrom(this.numberingFrom()) : null)
        ?? (graceDays ? await this.session.setGraceDays(this.graceDays()) : null)
        ?? (entryDate ? await this.session.setEntryDate(this.entryDate()) : null)
        ?? (pot ? await this.savePot() : null)
        ?? (parent ? await this.saveParent() : null);
      if (err) this.error.set(err);
      else {
        this.limit.set('');
        this.limitTouched.set(false);
        this.floor.set('');
        this.floorTouched.set(false);
        this.cap.set('');
        this.capTouched.set(false);
        this.proofThreshold.set('');
        this.proofThresholdTouched.set(false);
        this.noteThreshold.set('');
        this.noteThresholdTouched.set(false);
        this.terms.set('');
        this.termsTouched.set(false);
        this.gradeTerms.set({});
        this.gradeTermsTouched.set(new Set());
        this.gradeGrace.set({});
        this.gradeGraceTouched.set(new Set());
        this.gradeProofThreshold.set({});
        this.gradeProofThresholdTouched.set(new Set());
        this.perms.set({});
        this.permsTouched.set(new Set());
        this.froms.set({});
        this.latePct.set('');
        this.latePctTouched.set(false);
        this.policyDate.set('');
        this.policyDateTouched.set(false);
        this.numberingFrom.set('');
        this.numberingFromTouched.set(false);
        this.graceDays.set('');
        this.graceDaysTouched.set(false);
        this.entryDate.set('');
        this.entryDateTouched.set(false);
        this.pot.set('');
        this.potTouched.set(false);
        this.parent.set('');
        this.parentTouched.set(false);
        this.focused.set(null);
      }
    } finally {
      this.saving.set(false);
    }
  }

  /** Save the pot, then re-read the pools: their cuts are shares of it. */
  private async savePot(): Promise<string | null> {
    const err = await this.session.setPot(this.pot());
    if (!err) await this.pools.load();
    return err;
  }

  /** Save the parent, then re-read the rates: a grade with none in force here takes the parent's. */
  private async saveParent(): Promise<string | null> {
    const err = await this.session.setParent(this.parent());
    if (!err) await this.rates.load();
    return err;
  }

  /** Save the terms of each grade typed into, in grade order; the first refusal stops the rest. */
  private async saveGradeTerms(): Promise<string | null> {
    for (const g of this.grades) {
      if (!this.gradeTermsTouched().has(g)) continue;
      const err = await this.session.setGradeTerms(g, this.gradeTerms()[g] ?? '', this.fromOf('terms-' + g));
      if (err) return err;
    }
    return null;
  }

  /** Save the proof threshold of each grade typed into, in grade order; the first refusal stops the rest. */
  private async saveGradeProofThreshold(): Promise<string | null> {
    for (const g of this.grades) {
      if (!this.gradeProofThresholdTouched().has(g)) continue;
      const err = await this.session.setGradeProofThreshold(g, this.gradeProofThreshold()[g] ?? '',
        this.fromOf('proof-threshold-' + g));
      if (err) return err;
    }
    return null;
  }

  /** Save the grace days of each grade typed into, in grade order; the first refusal stops the rest. */
  private async saveGradeGrace(): Promise<string | null> {
    for (const g of this.grades) {
      if (!this.gradeGraceTouched().has(g)) continue;
      const err = await this.session.setGradeGrace(g, this.gradeGrace()[g] ?? '', this.fromOf('grace-' + g));
      if (err) return err;
    }
    return null;
  }

  /** Save the roles of each action typed into, in matrix order; the first refusal stops the rest. */
  private async savePerms(): Promise<string | null> {
    for (const a of this.actions) {
      if (!this.permsTouched().has(a)) continue;
      const err = await this.session.setPermission(a, this.perms()[a] ?? '');
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

  protected async delegate(e: Event): Promise<void> {
    e.preventDefault();
    if (this.delegating()) return; // a second submit before the first has answered
    this.delegateError.set(null);
    this.delegating.set(true);
    try {
      const err = await this.roles.delegate(this.delegateUser(), this.delegateFrom(), this.delegateTo());
      if (err) this.delegateError.set(err);
      else {
        this.delegateUser.set('');
        this.delegateFrom.set('');
        this.delegateTo.set('');
      }
    } finally {
      this.delegating.set(false);
    }
  }
}
