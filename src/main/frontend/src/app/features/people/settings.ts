import { Component, effect, inject, output, signal, untracked } from '@angular/core';
import { GRADES, Grade } from './people.service';
import { Session } from '../../core/session';
import { Graces } from './rates.service';
import { Dated, PERM_ACTIONS, PermAction, ROLES, Role, RoleGiven, SettingsService, SheetSettings } from './settings.service';

const LIMIT = /^\d{1,10}(\.\d{1,2})?$/;
const TERMS = /^\d{1,4}$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const ROUNDING = /^(half-up|half-even)$/;
const MONTH = /^(0?[1-9]|1[0-2])$/;
const PERM = /^((clerk|steward)\s*(,\s*(clerk|steward)\s*)*)?$/;
const STANDING: Record<PermAction, string> = {
  open: 'clerk and steward',
  settle: 'clerk and steward',
  void: 'clerk and steward',
  reassign: 'steward',
  close: 'clerk and steward',
  reopen: 'steward',
};

// The sheet's settings: its clerk limit (the most a clerk may settle a claim for; blank for no
// limit), its floor (the least a claim's live due can be), its cap (the most a person's due-total
// can be; blank for no cap), its terms (how many days after its date
// a claim is due by), each grade's own terms (used in place of the sheet's for a person of that
// grade; blank for the sheet's), its proof threshold (the due above which a claim needs a proof before it
// is settled or approved; blank for none), its late percent (the percent of its figure an overdue claim's due gains for
// every full 30 days it is overdue), its policy date (a claim dated before it rounds half up, one on
// or after it half to even; blank for the sheet's today), its period numbering from (the date from which
// a claim opened is numbered within its period; blank for off), its grace days (when the sheet's today
// is changed, an open period whose to-date plus them is before it is closed; blank for off), its fiscal start month (the month, 1 to 12, its fiscal year starts in), its entry date (a claim
// added without a date is dated it in place of the sheet's today; blank for off), its permission matrix (for each of open,
// settle, void, reassign, close and reopen, the roles that may take it as a comma separated list; blank
// for the standing rule) and the role each user name has been given (any other name is a clerk).
// Each grade's grace days (a claim of a person of that grade on its date, dated on or after the
// grace's from-date, is overdue only once its due-by plus them is past, and its late charge counts from
// there; blank for none) and each grade's own proof threshold (used in place of the sheet's for a claim
// of a person of that grade on its date; blank for the sheet's) are dated too, and so is its rounding
// mode (half-up or half-even: a claim dated on or after its from-date rounds every live figure by it in
// place of by the policy date; blank for none).
// The clerk limit, floor, cap, proof threshold, terms, grades' terms and late percent are dated: each is saved from the
// date typed beside it (blank for the sheet's today), and each shows the value in force on the sheet's
// today. Read from the
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
        <label for="setting-clerk-limit-from">Clerk limit from</label>
        <input
          id="setting-clerk-limit-from"
          data-testid="setting-clerk-limit-from"
          type="date"
          [value]="limitFrom()"
          (input)="limitFrom.set(value($event)); limitChanged = true; settingsError.set(null)"
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
        <label for="setting-floor-from">Floor from</label>
        <input
          id="setting-floor-from"
          data-testid="setting-floor-from"
          type="date"
          [value]="floorFrom()"
          (input)="floorFrom.set(value($event)); floorChanged = true; settingsError.set(null)"
        />
        <span>In force: <span data-testid="setting-floor-current">{{ floor() }}</span></span>
        <label for="setting-cap">Cap</label>
        <input
          id="setting-cap"
          data-testid="setting-cap"
          inputmode="decimal"
          [value]="capInput()"
          (input)="capInput.set(value($event)); capChanged = true; settingsError.set(null)"
          (keydown)="capChanged = true"
        />
        <label for="setting-cap-from">Cap from</label>
        <input
          id="setting-cap-from"
          data-testid="setting-cap-from"
          type="date"
          [value]="capFrom()"
          (input)="capFrom.set(value($event)); capChanged = true; settingsError.set(null)"
        />
        <span>
          In force: <span data-testid="setting-cap-current">{{ cap() ?? '' }}</span>
          @if (cap() === null) {
            (no cap)
          }
        </span>
        <label for="setting-rounding">Rounding</label>
        <input
          id="setting-rounding"
          data-testid="setting-rounding"
          [value]="roundingInput()"
          (input)="roundingInput.set(value($event)); roundingChanged = true; settingsError.set(null)"
          (keydown)="roundingChanged = true"
        />
        <label for="setting-rounding-from">Rounding from</label>
        <input
          id="setting-rounding-from"
          data-testid="setting-rounding-from"
          type="date"
          [value]="roundingFrom()"
          (input)="roundingFrom.set(value($event)); roundingChanged = true; settingsError.set(null)"
        />
        <span>
          In force: <span data-testid="setting-rounding-current">{{ rounding() ?? '' }}</span>
          @if (rounding() === null) {
            (by the policy date)
          }
        </span>
        <label for="setting-proof-threshold">Proof threshold</label>
        <input
          id="setting-proof-threshold"
          data-testid="setting-proof-threshold"
          inputmode="decimal"
          [value]="proofThresholdInput()"
          (input)="proofThresholdInput.set(value($event)); proofThresholdChanged = true; settingsError.set(null)"
          (keydown)="proofThresholdChanged = true"
        />
        <label for="setting-proof-threshold-from">Proof threshold from</label>
        <input
          id="setting-proof-threshold-from"
          data-testid="setting-proof-threshold-from"
          type="date"
          [value]="proofThresholdFrom()"
          (input)="proofThresholdFrom.set(value($event)); proofThresholdChanged = true; settingsError.set(null)"
        />
        <span>
          In force: <span data-testid="setting-proof-threshold-current">{{ proofThreshold() ?? '' }}</span>
          @if (proofThreshold() === null) {
            (none)
          }
        </span>
        @for (g of grades; track g) {
          <label [for]="'setting-proof-threshold-' + g">Proof threshold for grade {{ g }}</label>
          <input
            [id]="'setting-proof-threshold-' + g"
            [attr.data-testid]="'setting-proof-threshold-' + g"
            inputmode="decimal"
            [value]="gradeProofThresholdInput()[g]"
            (input)="setGradeProofThresholdInput(g, value($event)); gradeProofThresholdChanged.add(g); settingsError.set(null)"
            (keydown)="gradeProofThresholdChanged.add(g)"
          />
          <label [for]="'setting-proof-threshold-' + g + '-from'">Proof threshold for grade {{ g }} from</label>
          <input
            [id]="'setting-proof-threshold-' + g + '-from'"
            [attr.data-testid]="'setting-proof-threshold-' + g + '-from'"
            type="date"
            [value]="gradeProofThresholdFrom()[g]"
            (input)="setGradeProofThresholdFrom(g, value($event)); gradeProofThresholdChanged.add(g); settingsError.set(null)"
          />
          <span>
            In force: <span [attr.data-testid]="'setting-proof-threshold-' + g + '-current'">{{ gradeProofThresholdShown(g) }}</span>
            @if (gradeProofThresholds()[g] === undefined) {
              (the sheet's proof threshold)
            }
          </span>
        }
        <label for="setting-note-threshold">Note threshold</label>
        <input
          id="setting-note-threshold"
          data-testid="setting-note-threshold"
          inputmode="decimal"
          [value]="noteThresholdInput()"
          (input)="noteThresholdInput.set(value($event)); noteThresholdChanged = true; settingsError.set(null)"
          (keydown)="noteThresholdChanged = true"
        />
        <label for="setting-note-threshold-from">Note threshold from</label>
        <input
          id="setting-note-threshold-from"
          data-testid="setting-note-threshold-from"
          type="date"
          [value]="noteThresholdFrom()"
          (input)="noteThresholdFrom.set(value($event)); noteThresholdChanged = true; settingsError.set(null)"
        />
        <span>
          In force: <span data-testid="setting-note-threshold-current">{{ noteThreshold() ?? '' }}</span>
          @if (noteThreshold() === null) {
            (none)
          }
        </span>
        <label for="setting-terms">Terms (business days)</label>
        <input
          id="setting-terms"
          data-testid="setting-terms"
          inputmode="numeric"
          [value]="termsInput()"
          (input)="termsInput.set(value($event)); termsChanged = true; settingsError.set(null)"
          (keydown)="termsChanged = true"
        />
        <label for="setting-terms-from">Terms from</label>
        <input
          id="setting-terms-from"
          data-testid="setting-terms-from"
          type="date"
          [value]="termsFrom()"
          (input)="termsFrom.set(value($event)); termsChanged = true; settingsError.set(null)"
        />
        <span>In force: <span data-testid="setting-terms-current">{{ terms() }}</span></span>
        @for (g of grades; track g) {
          <label [for]="'setting-terms-' + g">Terms for grade {{ g }} (business days)</label>
          <input
            [id]="'setting-terms-' + g"
            [attr.data-testid]="'setting-terms-' + g"
            inputmode="numeric"
            [value]="gradeTermsInput()[g]"
            (input)="setGradeTermsInput(g, value($event)); gradeTermsChanged.add(g); settingsError.set(null)"
            (keydown)="gradeTermsChanged.add(g)"
          />
          <label [for]="'setting-terms-' + g + '-from'">Terms for grade {{ g }} from</label>
          <input
            [id]="'setting-terms-' + g + '-from'"
            [attr.data-testid]="'setting-terms-' + g + '-from'"
            type="date"
            [value]="gradeTermsFrom()[g]"
            (input)="setGradeTermsFrom(g, value($event)); gradeTermsChanged.add(g); settingsError.set(null)"
          />
          <span>
            In force: <span [attr.data-testid]="'setting-terms-' + g + '-current'">{{ gradeTermsShown(g) }}</span>
            @if (gradeTerms()[g] === undefined) {
              (the sheet's terms)
            }
          </span>
        }
        @for (g of grades; track g) {
          <label [for]="'setting-grace-' + g">Grace for grade {{ g }} (days)</label>
          <input
            [id]="'setting-grace-' + g"
            [attr.data-testid]="'setting-grace-' + g"
            inputmode="numeric"
            [value]="gradeGraceInput()[g]"
            (input)="setGradeGraceInput(g, value($event)); gradeGraceChanged.add(g); settingsError.set(null)"
            (keydown)="gradeGraceChanged.add(g)"
          />
          <label [for]="'setting-grace-' + g + '-from'">Grace for grade {{ g }} from</label>
          <input
            [id]="'setting-grace-' + g + '-from'"
            [attr.data-testid]="'setting-grace-' + g + '-from'"
            type="date"
            [value]="gradeGraceFrom()[g]"
            (input)="setGradeGraceFrom(g, value($event)); gradeGraceChanged.add(g); settingsError.set(null)"
          />
          <span>
            In force: <span [attr.data-testid]="'setting-grace-' + g + '-current'">{{ gradeGraceShown(g) }}</span>
            @if (gradeGraces()[g] === undefined) {
              (none)
            }
          </span>
        }
        <label for="setting-late-pct">Late percent</label>
        <input
          id="setting-late-pct"
          data-testid="setting-late-pct"
          inputmode="decimal"
          [value]="latePctInput()"
          (input)="latePctInput.set(value($event)); latePctChanged = true; settingsError.set(null)"
          (keydown)="latePctChanged = true"
        />
        <label for="setting-late-pct-from">Late percent from</label>
        <input
          id="setting-late-pct-from"
          data-testid="setting-late-pct-from"
          type="date"
          [value]="latePctFrom()"
          (input)="latePctFrom.set(value($event)); latePctChanged = true; settingsError.set(null)"
        />
        <span>In force: <span data-testid="setting-late-pct-current">{{ latePct() }}</span></span>
        <label for="setting-policy-date">Policy date</label>
        <input
          id="setting-policy-date"
          data-testid="setting-policy-date"
          type="date"
          [value]="policyDateInput()"
          (input)="policyDateInput.set(value($event)); policyDateChanged = true; settingsError.set(null)"
          (keydown)="policyDateChanged = true"
        />
        <span>
          In force: <span data-testid="setting-policy-date-current">{{ policyDate() ?? '' }}</span>
          @if (policyDate() === null) {
            (the sheet's today)
          }
        </span>
        <label for="setting-period-numbering-from">Period numbering from</label>
        <input
          id="setting-period-numbering-from"
          data-testid="setting-period-numbering-from"
          type="date"
          [value]="periodNumberingFromInput()"
          (input)="periodNumberingFromInput.set(value($event)); periodNumberingFromChanged = true; settingsError.set(null)"
          (keydown)="periodNumberingFromChanged = true"
        />
        <span>
          In force: <span data-testid="setting-period-numbering-from-current">{{ periodNumberingFrom() ?? '' }}</span>
          @if (periodNumberingFrom() === null) {
            (off)
          }
        </span>
        <label for="setting-grace-days">Grace days</label>
        <input
          id="setting-grace-days"
          data-testid="setting-grace-days"
          inputmode="numeric"
          [value]="graceDaysInput()"
          (input)="graceDaysInput.set(value($event)); graceDaysChanged = true; settingsError.set(null)"
          (keydown)="graceDaysChanged = true"
        />
        <span>
          In force: <span data-testid="setting-grace-days-current">{{ graceDays() ?? '' }}</span>
          @if (graceDays() === null) {
            (off)
          }
        </span>
        <label for="setting-entry-date">Entry date</label>
        <input
          id="setting-entry-date"
          data-testid="setting-entry-date"
          type="date"
          [value]="entryDateInput()"
          (input)="entryDateInput.set(value($event)); entryDateChanged = true; settingsError.set(null)"
          (keydown)="entryDateChanged = true"
        />
        <span>
          In force: <span data-testid="setting-entry-date-current">{{ entryDate() ?? '' }}</span>
          @if (entryDate() === null) {
            (off)
          }
        </span>
        <label for="setting-fiscal-start-month">Fiscal year starts in month</label>
        <input
          id="setting-fiscal-start-month"
          data-testid="setting-fiscal-start-month"
          inputmode="numeric"
          [value]="fiscalStartMonthInput()"
          (input)="fiscalStartMonthInput.set(value($event)); fiscalStartMonthChanged = true; settingsError.set(null)"
          (keydown)="fiscalStartMonthChanged = true"
        />
        <span>
          In force: <span data-testid="setting-fiscal-start-month-current">{{ fiscalStartMonth() }}</span>
        </span>
        <label for="setting-pot">Pot</label>
        <input
          id="setting-pot"
          data-testid="setting-pot"
          inputmode="decimal"
          [value]="potInput()"
          (input)="potInput.set(value($event)); potChanged = true; settingsError.set(null)"
          (keydown)="potChanged = true"
        />
        <span>
          In force: <span data-testid="setting-pot-current">{{ pot() ?? '' }}</span>
          @if (pot() === null) {
            (none)
          }
        </span>
        <label for="setting-alert-threshold">Alert threshold</label>
        <input
          id="setting-alert-threshold"
          data-testid="setting-alert-threshold"
          inputmode="decimal"
          [value]="alertThresholdInput()"
          (input)="alertThresholdInput.set(value($event)); alertThresholdChanged = true; settingsError.set(null)"
          (keydown)="alertThresholdChanged = true"
        />
        <span>
          In force: <span data-testid="setting-alert-threshold-current">{{ alertThreshold() ?? '' }}</span>
          @if (alertThreshold() === null) {
            (off)
          }
        </span>
        <label for="setting-parent">Parent sheet</label>
        <input
          id="setting-parent"
          data-testid="setting-parent"
          [value]="parentInput()"
          (input)="parentInput.set(value($event)); parentChanged = true; settingsError.set(null)"
          (keydown)="parentChanged = true"
        />
        <span>
          In force: <span data-testid="setting-parent-current">{{ parent() ?? '' }}</span>
          @if (parent() === null) {
            (none)
          }
        </span>
        <fieldset>
          <legend>Permissions (roles, comma separated; blank for the standing rule)</legend>
          @for (a of permActions; track a) {
            <label [for]="'setting-perm-' + a">Who may {{ a }}</label>
            <input
              [id]="'setting-perm-' + a"
              [attr.data-testid]="'setting-perm-' + a"
              [value]="permInput()[a]"
              (input)="setPermInput(a, value($event)); permChanged.add(a); settingsError.set(null)"
              (keydown)="permChanged.add(a)"
            />
            <span>
              In force: <span [attr.data-testid]="'setting-perm-' + a + '-current'">{{ perms()[a] }}</span>
              @if (perms()[a] === '') {
                ({{ standing[a] }})
              }
            </span>
          }
        </fieldset>
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
  /** The cap in force, with two decimals; null when there is none. */
  protected readonly cap = signal<string | null>(null);
  /** The rounding mode in force (half-up or half-even); null when there is none. */
  protected readonly rounding = signal<string | null>(null);
  /** Every rounding mode recorded, each from its date, oldest first, once one is saved. */
  readonly roundingSaved = output<Dated<string | null>[]>();
  /** Every cap recorded, each from its date, oldest first, once one is saved. */
  readonly capSaved = output<Dated<string | null>[]>();
  protected readonly given = signal<RoleGiven[]>([]);
  /** The proof threshold in force, with two decimals; null when there is none. */
  protected readonly proofThreshold = signal<string | null>(null);
  /** The note threshold in force, with two decimals; null when there is none. */
  protected readonly noteThreshold = signal<string | null>(null);
  /** The terms in force, in days, as text; empty until read. */
  protected readonly terms = signal('');
  protected readonly grades = GRADES;
  /** The grades' own terms in force, in days, by grade; a grade not in it uses the sheet's terms. */
  protected readonly gradeTerms = signal<Partial<Record<Grade, number>>>({});
  /** The grades' own proof thresholds in force, with two decimals, by grade; a grade not in it uses
   *  the sheet's. */
  protected readonly gradeProofThresholds = signal<Partial<Record<Grade, string>>>({});
  /** The grades' grace days in force, by grade; a grade not in it has none. */
  protected readonly gradeGraces = signal<Partial<Record<Grade, number>>>({});
  /** Every grace recorded for each grade, each from its date, oldest first, once one is saved. */
  readonly graceSaved = output<Graces>();
  /** The late percent in force, with two decimals; empty until read. */
  protected readonly latePct = signal('');
  /** Every floor recorded, each from its date, oldest first, once one is saved. */
  readonly floorSaved = output<Dated[]>();
  /** Every late percent recorded, each from its date, oldest first, once one is saved. */
  readonly latePctSaved = output<Dated[]>();
  /** The policy date as set; null when it is the sheet's today. */
  protected readonly policyDate = signal<string | null>(null);
  /** The policy date saved; null when it is the sheet's today. */
  readonly policyDateSaved = output<string | null>();

  protected readonly permActions = PERM_ACTIONS;
  protected readonly standing = STANDING;
  /** The permission matrix as set, by action; blank for the action's standing rule. */
  protected readonly perms = signal<Record<PermAction, string>>(blankPerms());
  protected readonly permInput = signal<Record<PermAction, string>>(blankPerms());
  protected readonly permChanged = new Set<PermAction>();

  /** The period numbering from date as set; null when it is off. */
  protected readonly periodNumberingFrom = signal<string | null>(null);
  /** The grace days as set, as text; null when they are off. */
  protected readonly graceDays = signal<string | null>(null);
  protected readonly pot = signal<string | null>(null);
  /** The alert threshold as set; null when it is off. */
  protected readonly alertThreshold = signal<string | null>(null);
  /** The month (1 to 12) the fiscal year starts in, as text; empty until read. */
  protected readonly fiscalStartMonth = signal('');
  /** The fiscal start month saved, 1 to 12. */
  readonly fiscalStartMonthSaved = output<number>();
  /** The parent sheet's id as set; null when there is none. */
  protected readonly parent = signal<string | null>(null);
  /** The entry date as set; null when it is off. */
  protected readonly entryDate = signal<string | null>(null);

  protected readonly limit = signal('');
  protected readonly floorInput = signal('');
  protected readonly capInput = signal('');
  protected readonly roundingInput = signal('');
  protected readonly proofThresholdInput = signal('');
  protected readonly noteThresholdInput = signal('');
  protected readonly termsInput = signal('');
  protected readonly latePctInput = signal('');
  protected readonly gradeTermsInput = signal<Record<Grade, string>>(blankGrades());
  protected readonly gradeGraceInput = signal<Record<Grade, string>>(blankGrades());
  protected readonly gradeProofThresholdInput = signal<Record<Grade, string>>(blankGrades());
  protected readonly policyDateInput = signal('');
  protected readonly periodNumberingFromInput = signal('');
  protected readonly graceDaysInput = signal('');
  protected readonly potInput = signal('');
  protected readonly alertThresholdInput = signal('');
  protected readonly fiscalStartMonthInput = signal('');
  protected readonly parentInput = signal('');
  protected readonly entryDateInput = signal('');
  /** The date each dated setting is saved from (YYYY-MM-DD); blank for the sheet's today. */
  protected readonly limitFrom = signal('');
  protected readonly floorFrom = signal('');
  protected readonly capFrom = signal('');
  protected readonly roundingFrom = signal('');
  protected readonly proofThresholdFrom = signal('');
  protected readonly noteThresholdFrom = signal('');
  protected readonly termsFrom = signal('');
  protected readonly latePctFrom = signal('');
  protected readonly gradeTermsFrom = signal<Record<Grade, string>>(blankGrades());
  protected readonly gradeGraceFrom = signal<Record<Grade, string>>(blankGrades());
  protected readonly gradeProofThresholdFrom = signal<Record<Grade, string>>(blankGrades());
  /** Whether each field was typed in (a key pressed in it, even one that left its value as it was)
   *  since the settings were last loaded or saved; a field is saved when it was, or when it differs
   *  from the value in force. */
  protected limitChanged = false;
  protected floorChanged = false;
  protected capChanged = false;
  protected roundingChanged = false;
  protected proofThresholdChanged = false;
  protected noteThresholdChanged = false;
  protected termsChanged = false;
  protected latePctChanged = false;
  protected readonly gradeTermsChanged = new Set<Grade>();
  protected readonly gradeGraceChanged = new Set<Grade>();
  protected readonly gradeProofThresholdChanged = new Set<Grade>();
  protected policyDateChanged = false;
  protected periodNumberingFromChanged = false;
  protected graceDaysChanged = false;
  protected potChanged = false;
  protected alertThresholdChanged = false;
  protected fiscalStartMonthChanged = false;
  protected parentChanged = false;
  protected entryDateChanged = false;
  protected readonly settingsError = signal<string | null>(null);
  protected readonly roleUser = signal('');
  protected readonly role = signal('');
  protected readonly roleError = signal<string | null>(null);
  /** A submit is waiting for its answer; another submit of the same form meanwhile is ignored. */
  private savingSettings = false;
  /** A settings submit came while one was waiting; it is made once that one is answered. */
  private settingsResubmit = false;
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
      this.cap.set(null);
      this.capInput.set('');
      this.capChanged = false;
      this.rounding.set(null);
      this.roundingInput.set('');
      this.roundingChanged = false;
      this.roundingFrom.set('');
      this.proofThreshold.set(null);
      this.proofThresholdInput.set('');
      this.proofThresholdChanged = false;
      this.proofThresholdFrom.set('');
      this.noteThreshold.set(null);
      this.noteThresholdInput.set('');
      this.noteThresholdChanged = false;
      this.noteThresholdFrom.set('');
      this.terms.set('');
      this.termsInput.set('');
      this.limitChanged = false;
      this.floorChanged = false;
      this.termsChanged = false;
      this.gradeTerms.set({});
      this.gradeTermsInput.set(blankGrades());
      this.gradeTermsChanged.clear();
      this.gradeGraces.set({});
      this.gradeGraceInput.set(blankGrades());
      this.gradeGraceChanged.clear();
      this.gradeGraceFrom.set(blankGrades());
      this.gradeProofThresholds.set({});
      this.gradeProofThresholdInput.set(blankGrades());
      this.gradeProofThresholdChanged.clear();
      this.gradeProofThresholdFrom.set(blankGrades());
      this.latePct.set('');
      this.latePctInput.set('');
      this.latePctChanged = false;
      this.policyDate.set(null);
      this.policyDateInput.set('');
      this.policyDateChanged = false;
      this.periodNumberingFrom.set(null);
      this.periodNumberingFromInput.set('');
      this.periodNumberingFromChanged = false;
      this.graceDays.set(null);
      this.graceDaysInput.set('');
      this.graceDaysChanged = false;
      this.pot.set(null);
      this.potInput.set('');
      this.potChanged = false;
      this.alertThreshold.set(null);
      this.alertThresholdInput.set('');
      this.alertThresholdChanged = false;
      this.fiscalStartMonth.set('');
      this.fiscalStartMonthInput.set('');
      this.fiscalStartMonthChanged = false;
      this.parent.set(null);
      this.parentInput.set('');
      this.parentChanged = false;
      this.entryDate.set(null);
      this.entryDateInput.set('');
      this.entryDateChanged = false;
      this.limitFrom.set('');
      this.floorFrom.set('');
      this.capFrom.set('');
      this.termsFrom.set('');
      this.latePctFrom.set('');
      this.gradeTermsFrom.set(blankGrades());
      this.perms.set(blankPerms());
      this.permInput.set(blankPerms());
      this.permChanged.clear();
      this.settingsError.set(null);
      this.roleUser.set('');
      this.role.set('');
      this.roleError.set(null);
      if (sheet) void this.read(sheet);
    });
    // What is in force follows the sheet's today: re-read it when the today changes.
    let shownToday: string | null = null;
    effect(() => {
      const today = this.session.today();
      const sheet = untracked(() => this.session.sheetId());
      if (shownToday !== null && today !== shownToday && sheet) void this.read(sheet);
      shownToday = today;
    });
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected setGradeTermsFrom(grade: Grade, text: string): void {
    this.gradeTermsFrom.update((inputs) => ({ ...inputs, [grade]: text }));
  }

  protected setGradeTermsInput(grade: Grade, text: string): void {
    this.gradeTermsInput.update((inputs) => ({ ...inputs, [grade]: text }));
  }

  protected setGradeGraceFrom(grade: Grade, text: string): void {
    this.gradeGraceFrom.update((inputs) => ({ ...inputs, [grade]: text }));
  }

  protected setGradeGraceInput(grade: Grade, text: string): void {
    this.gradeGraceInput.update((inputs) => ({ ...inputs, [grade]: text }));
  }

  protected setGradeProofThresholdFrom(grade: Grade, text: string): void {
    this.gradeProofThresholdFrom.update((inputs) => ({ ...inputs, [grade]: text }));
  }

  protected setGradeProofThresholdInput(grade: Grade, text: string): void {
    this.gradeProofThresholdInput.update((inputs) => ({ ...inputs, [grade]: text }));
  }

  /** The grade's own proof threshold in force; empty when it uses the sheet's. */
  protected gradeProofThresholdShown(grade: Grade): string {
    return this.gradeProofThresholds()[grade] ?? '';
  }

  /** The grade's grace days in force, as text; empty when it has none. */
  protected gradeGraceShown(grade: Grade): string {
    const grace = this.gradeGraces()[grade];
    return grace === undefined ? '' : String(grace);
  }

  protected setPermInput(action: PermAction, text: string): void {
    this.permInput.update((inputs) => ({ ...inputs, [action]: text }));
  }

  /** The grade's own terms in force, as text; empty when it uses the sheet's terms. */
  protected gradeTermsShown(grade: Grade): string {
    const own = this.gradeTerms()[grade];
    return own === undefined ? '' : String(own);
  }

  protected async saveSettings(event: Event): Promise<void> {
    event.preventDefault();
    if (this.savingSettings) {
      this.settingsResubmit = true;
      return;
    }
    this.settingsError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const limit = this.limit().trim();
    const floor = this.floorInput().trim();
    const saveLimit = this.limitChanged || limit !== (this.clerkLimit() ?? '');
    const saveFloor = this.floorChanged || floor !== this.floor();
    const cap = this.capInput().trim();
    const saveCap = this.capChanged || cap !== (this.cap() ?? '');
    const rounding = this.roundingInput().trim();
    const saveRounding = this.roundingChanged || rounding !== (this.rounding() ?? '');
    const proofThreshold = this.proofThresholdInput().trim();
    const saveProofThreshold = this.proofThresholdChanged || proofThreshold !== (this.proofThreshold() ?? '');
    const noteThreshold = this.noteThresholdInput().trim();
    const saveNoteThreshold = this.noteThresholdChanged || noteThreshold !== (this.noteThreshold() ?? '');
    const terms = this.termsInput().trim();
    const saveTerms = this.termsChanged || terms !== this.terms();
    const gradeTerms = GRADES.map((grade) => ({ grade, text: this.gradeTermsInput()[grade].trim() })).filter(
      ({ grade, text }) => this.gradeTermsChanged.has(grade) || text !== this.gradeTermsShown(grade),
    );
    const gradeGraces = GRADES.map((grade) => ({ grade, text: this.gradeGraceInput()[grade].trim() })).filter(
      ({ grade, text }) => this.gradeGraceChanged.has(grade) || text !== this.gradeGraceShown(grade),
    );
    const gradeProofThresholds = GRADES.map((grade) => ({ grade, text: this.gradeProofThresholdInput()[grade].trim() })).filter(
      ({ grade, text }) => this.gradeProofThresholdChanged.has(grade) || text !== this.gradeProofThresholdShown(grade),
    );
    const latePct = this.latePctInput().trim();
    const saveLatePct = this.latePctChanged || latePct !== this.latePct();
    const policyDate = this.policyDateInput().trim();
    const savePolicyDate = this.policyDateChanged || policyDate !== (this.policyDate() ?? '');
    const numberingFrom = this.periodNumberingFromInput().trim();
    const saveNumberingFrom =
      this.periodNumberingFromChanged || numberingFrom !== (this.periodNumberingFrom() ?? '');
    const graceDays = this.graceDaysInput().trim();
    const saveGraceDays = this.graceDaysChanged || graceDays !== (this.graceDays() ?? '');
    const fiscalStartMonth = this.fiscalStartMonthInput().trim();
    const saveFiscalStartMonth = this.fiscalStartMonthChanged || fiscalStartMonth !== this.fiscalStartMonth();
    const pot = this.potInput().trim();
    const savePot = this.potChanged || pot !== (this.pot() ?? '');
    const alertThreshold = this.alertThresholdInput().trim();
    const saveAlertThreshold = this.alertThresholdChanged || alertThreshold !== (this.alertThreshold() ?? '');
    const parent = this.parentInput().trim();
    const saveParent = this.parentChanged || parent !== (this.parent() ?? '');
    const entryDate = this.entryDateInput().trim();
    const saveEntryDate = this.entryDateChanged || entryDate !== (this.entryDate() ?? '');
    const perms = PERM_ACTIONS.map((action) => ({ action, text: this.permInput()[action].trim() })).filter(
      ({ action, text }) => this.permChanged.has(action) || text !== this.perms()[action],
    );
    const limitFrom = this.limitFrom().trim();
    const floorFrom = this.floorFrom().trim();
    const capFrom = this.capFrom().trim();
    const roundingFrom = this.roundingFrom().trim();
    const proofThresholdFrom = this.proofThresholdFrom().trim();
    const noteThresholdFrom = this.noteThresholdFrom().trim();
    const termsFrom = this.termsFrom().trim();
    const latePctFrom = this.latePctFrom().trim();
    const gradeTermsFrom = { ...this.gradeTermsFrom() };
    const gradeGraceFrom = { ...this.gradeGraceFrom() };
    const gradeProofThresholdFrom = { ...this.gradeProofThresholdFrom() };
    const badFrom = [
      saveLimit ? limitFrom : '',
      saveFloor ? floorFrom : '',
      saveCap ? capFrom : '',
      saveRounding ? roundingFrom : '',
      saveProofThreshold ? proofThresholdFrom : '',
      saveNoteThreshold ? noteThresholdFrom : '',
      saveTerms ? termsFrom : '',
      saveLatePct ? latePctFrom : '',
      ...gradeTerms.map(({ grade }) => gradeTermsFrom[grade].trim()),
      ...gradeGraces.map(({ grade }) => gradeGraceFrom[grade].trim()),
      ...gradeProofThresholds.map(({ grade }) => gradeProofThresholdFrom[grade].trim()),
    ].some((from) => from !== '' && !DATE.test(from));
    if (badFrom) {
      this.settingsError.set('Type a from-date as YYYY-MM-DD, or leave it blank for the sheet\'s today.');
      return;
    }
    if (saveLimit && limit !== '' && !LIMIT.test(limit)) {
      this.settingsError.set('Type a clerk limit of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    if (saveFloor && !LIMIT.test(floor)) {
      this.settingsError.set('Type a floor of 0.00 or more, with up to two decimals.');
      return;
    }
    if (saveCap && cap !== '' && !LIMIT.test(cap)) {
      this.settingsError.set('Type a cap of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    if (saveRounding && rounding !== '' && !ROUNDING.test(rounding)) {
      this.settingsError.set('Type a rounding mode of half-up or half-even, or leave it blank for none.');
      return;
    }
    if (saveProofThreshold && proofThreshold !== '' && !LIMIT.test(proofThreshold)) {
      this.settingsError.set('Type a proof threshold of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    const badProofThreshold = gradeProofThresholds.find(({ text }) => text !== '' && !LIMIT.test(text));
    if (badProofThreshold) {
      this.settingsError.set(
        `Type a proof threshold for grade ${badProofThreshold.grade} of 0.00 or more, with up to two decimals, or leave it blank for the sheet's.`,
      );
      return;
    }
    if (saveNoteThreshold && noteThreshold !== '' && !LIMIT.test(noteThreshold)) {
      this.settingsError.set('Type a note threshold of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    if (saveTerms && !TERMS.test(terms)) {
      this.settingsError.set('Type terms as a whole number of business days, 0 or more.');
      return;
    }
    const badGrade = gradeTerms.find(({ text }) => text !== '' && !TERMS.test(text));
    if (badGrade) {
      this.settingsError.set(
        `Type terms for grade ${badGrade.grade} as a whole number of business days, 0 or more, or leave them blank for the sheet's terms.`,
      );
      return;
    }
    const badGrace = gradeGraces.find(({ text }) => text !== '' && !TERMS.test(text));
    if (badGrace) {
      this.settingsError.set(
        `Type grace for grade ${badGrace.grade} as a whole number of days, 0 or more, or leave it blank for none.`,
      );
      return;
    }
    if (saveLatePct && !LIMIT.test(latePct)) {
      this.settingsError.set('Type a late percent of 0.00 or more, with up to two decimals.');
      return;
    }
    if (savePolicyDate && policyDate !== '' && !DATE.test(policyDate)) {
      this.settingsError.set('Type a policy date as YYYY-MM-DD, or leave it blank for the sheet\'s today.');
      return;
    }
    if (saveNumberingFrom && numberingFrom !== '' && !DATE.test(numberingFrom)) {
      this.settingsError.set('Type a period numbering from date as YYYY-MM-DD, or leave it blank to turn it off.');
      return;
    }
    if (saveGraceDays && graceDays !== '' && !TERMS.test(graceDays)) {
      this.settingsError.set('Type grace days as a whole number of days, 0 or more, or leave them blank to turn them off.');
      return;
    }
    if (saveFiscalStartMonth && !MONTH.test(fiscalStartMonth)) {
      this.settingsError.set('Type the month the fiscal year starts in as a whole number from 1 to 12.');
      return;
    }
    if (savePot && pot !== '' && !LIMIT.test(pot)) {
      this.settingsError.set('Type a pot of 0.00 or more, with at most two decimals, or leave it blank for none.');
      return;
    }
    if (saveAlertThreshold && alertThreshold !== '' && !LIMIT.test(alertThreshold)) {
      this.settingsError.set('Type an alert threshold of 0.00 or more, with at most two decimals, or leave it blank to turn it off.');
      return;
    }
    if (saveEntryDate && entryDate !== '' && !DATE.test(entryDate)) {
      this.settingsError.set('Type an entry date as YYYY-MM-DD, or leave it blank to turn it off.');
      return;
    }
    const badPerm = perms.find(({ text }) => !PERM.test(text));
    if (badPerm) {
      this.settingsError.set(
        `Type who may ${badPerm.action} as roles (clerk, steward) separated by commas, or leave it blank for the standing rule.`,
      );
      return;
    }
    this.savingSettings = true;
    try {
      if (saveLimit) {
        const settings = await this.service.setClerkLimit(sheet, limit || null, limitFrom || null);
        if (this.session.sheetId() !== sheet) return;
        if (this.limit().trim() === limit && this.limitFrom().trim() === limitFrom) {
          this.limitChanged = false;
          this.limitFrom.set('');
        }
        this.show(settings);
      }
      if (saveFloor) {
        const settings = await this.service.setFloor(sheet, floor, floorFrom || null);
        if (this.session.sheetId() !== sheet) return;
        if (this.floorInput().trim() === floor && this.floorFrom().trim() === floorFrom) {
          this.floorChanged = false;
          this.floorFrom.set('');
        }
        this.show(settings);
        this.floorSaved.emit(settings.floors);
      }
      if (saveCap) {
        const settings = await this.service.setCap(sheet, cap || null, capFrom || null);
        if (this.session.sheetId() !== sheet) return;
        if (this.capInput().trim() === cap && this.capFrom().trim() === capFrom) {
          this.capChanged = false;
          this.capFrom.set('');
        }
        this.show(settings);
        this.capSaved.emit(settings.caps);
      }
      if (saveRounding) {
        const settings = await this.service.setRounding(sheet, rounding || null, roundingFrom || null);
        if (this.session.sheetId() !== sheet) return;
        if (this.roundingInput().trim() === rounding && this.roundingFrom().trim() === roundingFrom) {
          this.roundingChanged = false;
          this.roundingFrom.set('');
        }
        this.show(settings);
        this.roundingSaved.emit(settings.roundings);
      }
      if (saveProofThreshold) {
        const settings = await this.service.setProofThreshold(sheet, proofThreshold || null, proofThresholdFrom || null);
        if (this.session.sheetId() !== sheet) return;
        if (this.proofThresholdInput().trim() === proofThreshold && this.proofThresholdFrom().trim() === proofThresholdFrom) {
          this.proofThresholdChanged = false;
          this.proofThresholdFrom.set('');
        }
        this.show(settings);
      }
      for (const { grade, text } of gradeProofThresholds) {
        const from = gradeProofThresholdFrom[grade].trim();
        const settings = await this.service.setGradeProofThreshold(sheet, grade, text || null, from || null);
        if (this.session.sheetId() !== sheet) return;
        if (this.gradeProofThresholdInput()[grade].trim() === text && this.gradeProofThresholdFrom()[grade].trim() === from) {
          this.gradeProofThresholdChanged.delete(grade);
          this.setGradeProofThresholdFrom(grade, '');
        }
        this.show(settings);
      }
      if (saveNoteThreshold) {
        const settings = await this.service.setNoteThreshold(sheet, noteThreshold || null, noteThresholdFrom || null);
        if (this.session.sheetId() !== sheet) return;
        if (this.noteThresholdInput().trim() === noteThreshold && this.noteThresholdFrom().trim() === noteThresholdFrom) {
          this.noteThresholdChanged = false;
          this.noteThresholdFrom.set('');
        }
        this.show(settings);
      }
      if (saveTerms) {
        const settings = await this.service.setTerms(sheet, terms, termsFrom || null);
        if (this.session.sheetId() !== sheet) return;
        if (this.termsInput().trim() === terms && this.termsFrom().trim() === termsFrom) {
          this.termsChanged = false;
          this.termsFrom.set('');
        }
        this.show(settings);
      }
      for (const { grade, text } of gradeTerms) {
        const from = gradeTermsFrom[grade].trim();
        const settings = await this.service.setGradeTerms(sheet, grade, text || null, from || null);
        if (this.session.sheetId() !== sheet) return;
        if (this.gradeTermsInput()[grade].trim() === text && this.gradeTermsFrom()[grade].trim() === from) {
          this.gradeTermsChanged.delete(grade);
          this.setGradeTermsFrom(grade, '');
        }
        this.show(settings);
      }
      for (const { grade, text } of gradeGraces) {
        const from = gradeGraceFrom[grade].trim();
        const settings = await this.service.setGradeGrace(sheet, grade, text || null, from || null);
        if (this.session.sheetId() !== sheet) return;
        if (this.gradeGraceInput()[grade].trim() === text && this.gradeGraceFrom()[grade].trim() === from) {
          this.gradeGraceChanged.delete(grade);
          this.setGradeGraceFrom(grade, '');
        }
        this.show(settings);
        this.graceSaved.emit(settings.graces);
      }
      if (saveLatePct) {
        const settings = await this.service.setLatePct(sheet, latePct, latePctFrom || null);
        if (this.session.sheetId() !== sheet) return;
        if (this.latePctInput().trim() === latePct && this.latePctFrom().trim() === latePctFrom) {
          this.latePctChanged = false;
          this.latePctFrom.set('');
        }
        this.show(settings);
        this.latePctSaved.emit(settings.latePcts);
      }
      if (savePolicyDate) {
        const settings = await this.service.setPolicyDate(sheet, policyDate || null);
        if (this.session.sheetId() !== sheet) return;
        if (this.policyDateInput().trim() === policyDate) this.policyDateChanged = false;
        this.show(settings);
        this.policyDateSaved.emit(settings.policyDate);
      }
      if (saveNumberingFrom) {
        const settings = await this.service.setPeriodNumberingFrom(sheet, numberingFrom || null);
        if (this.session.sheetId() !== sheet) return;
        if (this.periodNumberingFromInput().trim() === numberingFrom) this.periodNumberingFromChanged = false;
        this.show(settings);
      }
      if (saveGraceDays) {
        const settings = await this.service.setGraceDays(sheet, graceDays || null);
        if (this.session.sheetId() !== sheet) return;
        if (this.graceDaysInput().trim() === graceDays) this.graceDaysChanged = false;
        this.show(settings);
      }
      if (saveFiscalStartMonth) {
        const settings = await this.service.setFiscalStartMonth(sheet, fiscalStartMonth);
        if (this.session.sheetId() !== sheet) return;
        if (this.fiscalStartMonthInput().trim() === fiscalStartMonth) this.fiscalStartMonthChanged = false;
        this.show(settings);
        this.fiscalStartMonthSaved.emit(settings.fiscalStartMonth);
      }
      if (savePot) {
        const settings = await this.service.setPot(sheet, pot || null);
        if (this.session.sheetId() !== sheet) return;
        if (this.potInput().trim() === pot) this.potChanged = false;
        this.show(settings);
      }
      if (saveAlertThreshold) {
        const settings = await this.service.setAlertThreshold(sheet, alertThreshold || null);
        if (this.session.sheetId() !== sheet) return;
        if (this.alertThresholdInput().trim() === alertThreshold) this.alertThresholdChanged = false;
        this.show(settings);
      }
      if (saveParent) {
        const settings = await this.service.setParent(sheet, parent || null);
        if (this.session.sheetId() !== sheet) return;
        if (this.parentInput().trim() === parent) this.parentChanged = false;
        this.show(settings);
        this.session.parent.set(settings.parent ?? null);
      }
      if (saveEntryDate) {
        const settings = await this.service.setEntryDate(sheet, entryDate || null);
        if (this.session.sheetId() !== sheet) return;
        if (this.entryDateInput().trim() === entryDate) this.entryDateChanged = false;
        this.show(settings);
      }
      for (const { action, text } of perms) {
        const settings = await this.service.setPermission(sheet, action, text);
        if (this.session.sheetId() !== sheet) return;
        if (this.permInput()[action].trim() === text) this.permChanged.delete(action);
        this.show(settings);
      }
      // Show what is in force in each field, but keep what was typed in one while the save was waiting.
      if (!this.limitChanged) this.limit.set(this.clerkLimit() ?? '');
      if (!this.floorChanged) this.floorInput.set(this.floor());
      if (!this.capChanged) this.capInput.set(this.cap() ?? '');
      if (!this.roundingChanged) this.roundingInput.set(this.rounding() ?? '');
      if (!this.proofThresholdChanged) this.proofThresholdInput.set(this.proofThreshold() ?? '');
      if (!this.noteThresholdChanged) this.noteThresholdInput.set(this.noteThreshold() ?? '');
      if (!this.termsChanged) this.termsInput.set(this.terms());
      if (!this.latePctChanged) this.latePctInput.set(this.latePct());
      this.showGradeTermsInputs();
      this.showGradeGraceInputs();
      this.showGradeProofThresholdInputs();
      this.showPermInputs();
      if (!this.policyDateChanged) this.policyDateInput.set(this.policyDate() ?? '');
      if (!this.periodNumberingFromChanged) this.periodNumberingFromInput.set(this.periodNumberingFrom() ?? '');
      if (!this.graceDaysChanged) this.graceDaysInput.set(this.graceDays() ?? '');
      if (!this.fiscalStartMonthChanged) this.fiscalStartMonthInput.set(this.fiscalStartMonth());
      if (!this.potChanged) this.potInput.set(this.pot() ?? '');
      if (!this.alertThresholdChanged) this.alertThresholdInput.set(this.alertThreshold() ?? '');
      if (!this.parentChanged) this.parentInput.set(this.parent() ?? '');
      if (!this.entryDateChanged) this.entryDateInput.set(this.entryDate() ?? '');
    } catch {
      this.settingsError.set('Those settings could not be saved.');
    } finally {
      this.savingSettings = false;
      if (this.settingsResubmit) {
        this.settingsResubmit = false;
        void this.saveSettings(event);
      }
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
        if (!this.capChanged) this.capInput.set(settings.cap ?? '');
        if (!this.roundingChanged) this.roundingInput.set(settings.rounding ?? '');
        if (!this.proofThresholdChanged) this.proofThresholdInput.set(settings.proofThreshold ?? '');
        if (!this.noteThresholdChanged) this.noteThresholdInput.set(settings.noteThreshold ?? '');
        if (!this.termsChanged) this.termsInput.set(String(settings.terms));
        if (!this.latePctChanged) this.latePctInput.set(settings.latePct);
        this.showGradeTermsInputs();
        this.showGradeGraceInputs();
        this.showGradeProofThresholdInputs();
        this.showPermInputs();
        if (!this.policyDateChanged) this.policyDateInput.set(settings.policyDate ?? '');
        if (!this.periodNumberingFromChanged) this.periodNumberingFromInput.set(settings.periodNumberingFrom ?? '');
        if (!this.graceDaysChanged) this.graceDaysInput.set(this.graceDays() ?? '');
        if (!this.fiscalStartMonthChanged) this.fiscalStartMonthInput.set(this.fiscalStartMonth());
        if (!this.potChanged) this.potInput.set(settings.pot ?? '');
        if (!this.alertThresholdChanged) this.alertThresholdInput.set(settings.alertThreshold ?? '');
        if (!this.parentChanged) this.parentInput.set(settings.parent ?? '');
        if (!this.entryDateChanged) this.entryDateInput.set(settings.entryDate ?? '');
      }
    } catch {
      // The settings could not be read; they show as none.
    }
  }

  /** Show each grade's own terms in force in its field, but keep what was typed in one since. */
  private showGradeTermsInputs(): void {
    this.gradeTermsInput.update((inputs) => {
      const next = { ...inputs };
      for (const grade of GRADES) if (!this.gradeTermsChanged.has(grade)) next[grade] = this.gradeTermsShown(grade);
      return next;
    });
  }

  /** Show each grade's grace in force in its field, but keep what was typed in one since. */
  private showGradeGraceInputs(): void {
    this.gradeGraceInput.update((inputs) => {
      const next = { ...inputs };
      for (const grade of GRADES) if (!this.gradeGraceChanged.has(grade)) next[grade] = this.gradeGraceShown(grade);
      return next;
    });
  }

  /** Show each grade's own proof threshold in force in its field, but keep what was typed in one since. */
  private showGradeProofThresholdInputs(): void {
    this.gradeProofThresholdInput.update((inputs) => {
      const next = { ...inputs };
      for (const grade of GRADES) {
        if (!this.gradeProofThresholdChanged.has(grade)) next[grade] = this.gradeProofThresholdShown(grade);
      }
      return next;
    });
  }

  /** Show each action's roles as set in its field, but keep what was typed in one since. */
  private showPermInputs(): void {
    this.permInput.update((inputs) => {
      const next = { ...inputs };
      for (const action of PERM_ACTIONS) if (!this.permChanged.has(action)) next[action] = this.perms()[action];
      return next;
    });
  }

  private show(settings: SheetSettings): void {
    this.perms.set({ ...blankPerms(), ...settings.permissions });
    this.clerkLimit.set(settings.clerkLimit);
    this.floor.set(settings.floor);
    this.cap.set(settings.cap);
    this.rounding.set(settings.rounding ?? null);
    this.proofThreshold.set(settings.proofThreshold);
    this.noteThreshold.set(settings.noteThreshold);
    this.terms.set(String(settings.terms));
    this.gradeTerms.set(settings.gradeTerms ?? {});
    this.gradeGraces.set(settings.gradeGraces ?? {});
    this.gradeProofThresholds.set(settings.gradeProofThresholds ?? {});
    this.latePct.set(settings.latePct);
    this.policyDate.set(settings.policyDate);
    this.periodNumberingFrom.set(settings.periodNumberingFrom);
    this.graceDays.set(settings.graceDays === null ? null : String(settings.graceDays));
    this.entryDate.set(settings.entryDate ?? null);
    this.pot.set(settings.pot ?? null);
    this.alertThreshold.set(settings.alertThreshold ?? null);
    this.fiscalStartMonth.set(String(settings.fiscalStartMonth ?? 1));
    this.parent.set(settings.parent ?? null);
    this.given.set(settings.roles);
  }
}

function blankGrades(): Record<Grade, string> {
  return { A: '', B: '', C: '' };
}

function blankPerms(): Record<PermAction, string> {
  return { open: '', settle: '', void: '', reassign: '', close: '', reopen: '' };
}
