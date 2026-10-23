import { Component, effect, inject, signal, untracked } from '@angular/core';
import { FigurePipe } from '../../core/locale';
import {
  ALERT_THRESHOLD, CAP, DatedKey, ENTRY_DATE, FLOOR, PERMISSION, PERMISSION_ACTIONS, PermissionAction, GRADES, GRADE_GRACE, GRADE_TERMS, Grade, LATE_PCT, LIMIT, FISCAL_START_MONTH, GRACE_DAYS, NOTE_THRESHOLD, PARENT, POT, PERIOD_NUMBERING_FROM, POLICY_DATE, PROOF_KIND_LIST, PROOF_THRESHOLD, ROLES, ROUNDING, SETTING_FROM, SettingsView, TERMS,
  RoleName, RoleView, SettingsApi,
} from '../../core/settings';
import { Session } from '../../core/session';

// The sheet's settings (the clerk limit, the cap, the proof threshold and each grade's own, the note threshold, the floor, the terms and each grade's own terms, the late percent, the policy date, period numbering from, the grace days, the alert threshold and the permission matrix) and the roles user names have on it (clerk by default,
// or steward), each changed through its own form. The settings that carry a figure are saved from a from-date (the
// sheet's today unless one is typed) and shown as in force on the sheet's today.
@Component({
  selector: 'app-settings',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="settings-heading">
      <h2 id="settings-heading">Settings</h2>
      <form (submit)="saveSettings($event)" aria-label="Change settings">
        <label for="setting-clerk-limit">Clerk limit (empty: no limit)</label>
        <input
          id="setting-clerk-limit"
          data-testid="setting-clerk-limit"
          inputmode="decimal"
          [value]="limit()"
          (input)="limit.set(value($event)); limitEdited = true; settingsError.set(null)"
        />
        <label for="setting-clerk-limit-from">Clerk limit from (yyyy-mm-dd; empty: the sheet's today)</label>
        <input
          id="setting-clerk-limit-from"
          data-testid="setting-clerk-limit-from"
          [value]="fromInput()['clerk-limit']"
          (input)="fromTyped('clerk-limit', value($event))"
        />
        <label for="setting-cap">Cap (empty: no cap)</label>
        <input
          id="setting-cap"
          data-testid="setting-cap"
          inputmode="decimal"
          [value]="capInput()"
          (input)="capInput.set(value($event)); capEdited = true; settingsError.set(null)"
          (keydown.delete)="cleared($event, 'cap')"
          (keydown.backspace)="cleared($event, 'cap')"
        />
        <label for="setting-cap-from">Cap from (yyyy-mm-dd; empty: the sheet's today)</label>
        <input
          id="setting-cap-from"
          data-testid="setting-cap-from"
          [value]="fromInput()['cap']"
          (input)="fromTyped('cap', value($event))"
        />
        <label for="setting-proof-threshold">Proof threshold (empty: none)</label>
        <input
          id="setting-proof-threshold"
          data-testid="setting-proof-threshold"
          inputmode="decimal"
          [value]="proofThresholdInput()"
          (input)="proofThresholdInput.set(value($event)); proofThresholdEdited = true; settingsError.set(null)"
          (keydown.delete)="cleared($event, 'proofThreshold')"
          (keydown.backspace)="cleared($event, 'proofThreshold')"
        />
        <label for="setting-proof-threshold-from">Proof threshold from (yyyy-mm-dd; empty: the sheet's today)</label>
        <input
          id="setting-proof-threshold-from"
          data-testid="setting-proof-threshold-from"
          [value]="fromInput()['proof-threshold']"
          (input)="fromTyped('proof-threshold', value($event))"
        />
        @for (g of grades; track g) {
          <label [for]="'setting-proof-threshold-' + g">Proof threshold for grade {{ g }} (empty: the sheet's)</label>
          <input
            [id]="'setting-proof-threshold-' + g"
            [attr.data-testid]="'setting-proof-threshold-' + g"
            inputmode="decimal"
            [value]="gradeProofThresholdInput()[g]"
            (input)="gradeProofThresholdTyped(g, value($event))"
          />
          <label [for]="'setting-proof-threshold-' + g + '-from'">Proof threshold for grade {{ g }} from (yyyy-mm-dd; empty: the sheet's today)</label>
          <input
            [id]="'setting-proof-threshold-' + g + '-from'"
            [attr.data-testid]="'setting-proof-threshold-' + g + '-from'"
            [value]="fromInput()[proofKey(g)]"
            (input)="fromTyped(proofKey(g), value($event))"
          />
        }
        <label for="setting-note-threshold">Note threshold (empty: none)</label>
        <input
          id="setting-note-threshold"
          data-testid="setting-note-threshold"
          inputmode="decimal"
          [value]="noteThresholdInput()"
          (input)="noteThresholdInput.set(value($event)); noteThresholdEdited = true; settingsError.set(null)"
          (keydown.delete)="cleared($event, 'noteThreshold')"
          (keydown.backspace)="cleared($event, 'noteThreshold')"
        />
        <label for="setting-note-threshold-from">Note threshold from (yyyy-mm-dd; empty: the sheet's today)</label>
        <input
          id="setting-note-threshold-from"
          data-testid="setting-note-threshold-from"
          [value]="fromInput()['note-threshold']"
          (input)="fromTyped('note-threshold', value($event))"
        />
        <label for="setting-review-threshold">Review threshold (empty: none)</label>
        <input
          id="setting-review-threshold"
          data-testid="setting-review-threshold"
          inputmode="decimal"
          [value]="reviewThresholdInput()"
          (input)="reviewThresholdInput.set(value($event)); reviewThresholdEdited = true; settingsError.set(null)"
          (keydown.delete)="cleared($event, 'reviewThreshold')"
          (keydown.backspace)="cleared($event, 'reviewThreshold')"
        />
        <label for="setting-review-threshold-from">Review threshold from (yyyy-mm-dd; empty: the sheet's today)</label>
        <input
          id="setting-review-threshold-from"
          data-testid="setting-review-threshold-from"
          [value]="fromInput()['review-threshold']"
          (input)="fromTyped('review-threshold', value($event))"
        />
        <label for="setting-rounding">Rounding mode (half-up or half-even; empty: none, the policy date decides)</label>
        <input
          id="setting-rounding"
          data-testid="setting-rounding"
          [value]="roundingInput()"
          (input)="roundingInput.set(value($event)); roundingEdited = true; settingsError.set(null)"
          (keydown.delete)="cleared($event, 'rounding')"
          (keydown.backspace)="cleared($event, 'rounding')"
        />
        <label for="setting-rounding-from">Rounding mode from (yyyy-mm-dd; empty: the sheet's today)</label>
        <input
          id="setting-rounding-from"
          data-testid="setting-rounding-from"
          [value]="fromInput()['rounding']"
          (input)="fromTyped('rounding', value($event))"
        />
        <label for="setting-floor">Floor</label>
        <input
          id="setting-floor"
          data-testid="setting-floor"
          inputmode="decimal"
          [value]="floorInput()"
          (input)="floorInput.set(value($event)); floorEdited = true; settingsError.set(null)"
        />
        <label for="setting-floor-from">Floor from (yyyy-mm-dd; empty: the sheet's today)</label>
        <input
          id="setting-floor-from"
          data-testid="setting-floor-from"
          [value]="fromInput()['floor']"
          (input)="fromTyped('floor', value($event))"
        />
        <label for="setting-terms">Terms (business days)</label>
        <input
          id="setting-terms"
          data-testid="setting-terms"
          inputmode="numeric"
          [value]="termsInput()"
          (input)="termsInput.set(value($event)); termsEdited = true; settingsError.set(null)"
        />
        <label for="setting-terms-from">Terms from (yyyy-mm-dd; empty: the sheet's today)</label>
        <input
          id="setting-terms-from"
          data-testid="setting-terms-from"
          [value]="fromInput()['terms']"
          (input)="fromTyped('terms', value($event))"
        />
        @for (g of grades; track g) {
          <label [for]="'setting-terms-' + g">Terms for grade {{ g }} (days; empty: the sheet's terms)</label>
          <input
            [id]="'setting-terms-' + g"
            [attr.data-testid]="'setting-terms-' + g"
            inputmode="numeric"
            [value]="gradeTermsInput()[g]"
            (input)="gradeTermsTyped(g, value($event))"
          />
          <label [for]="'setting-terms-' + g + '-from'">Terms for grade {{ g }} from (yyyy-mm-dd; empty: the sheet's today)</label>
          <input
            [id]="'setting-terms-' + g + '-from'"
            [attr.data-testid]="'setting-terms-' + g + '-from'"
            [value]="fromInput()[gradeKey(g)]"
            (input)="fromTyped(gradeKey(g), value($event))"
          />
        }
        @for (g of grades; track g) {
          <label [for]="'setting-grace-' + g">Grace days for grade {{ g }} past the due-by before a claim is overdue (empty: none)</label>
          <input
            [id]="'setting-grace-' + g"
            [attr.data-testid]="'setting-grace-' + g"
            inputmode="numeric"
            [value]="gradeGraceInput()[g]"
            (input)="gradeGraceTyped(g, value($event))"
          />
          <label [for]="'setting-grace-' + g + '-from'">Grace days for grade {{ g }} from (yyyy-mm-dd; empty: the sheet's today)</label>
          <input
            [id]="'setting-grace-' + g + '-from'"
            [attr.data-testid]="'setting-grace-' + g + '-from'"
            [value]="fromInput()[graceKey(g)]"
            (input)="fromTyped(graceKey(g), value($event))"
          />
        }
        <label for="setting-late-pct">Late percent (per full 30 days overdue)</label>
        <input
          id="setting-late-pct"
          data-testid="setting-late-pct"
          inputmode="decimal"
          [value]="latePctInput()"
          (input)="latePctInput.set(value($event)); latePctEdited = true; settingsError.set(null)"
        />
        <label for="setting-late-pct-from">Late percent from (yyyy-mm-dd; empty: the sheet's today)</label>
        <input
          id="setting-late-pct-from"
          data-testid="setting-late-pct-from"
          [value]="fromInput()['late-pct']"
          (input)="fromTyped('late-pct', value($event))"
        />
        <label for="setting-policy-date">Policy date (yyyy-mm-dd; empty: the sheet's today)</label>
        <input
          id="setting-policy-date"
          data-testid="setting-policy-date"
          [value]="policyDateInput()"
          (input)="policyDateInput.set(value($event)); policyDateEdited = true; settingsError.set(null)"
          (keydown.delete)="cleared($event, 'policyDate')"
          (keydown.backspace)="cleared($event, 'policyDate')"
        />
        <label for="setting-period-numbering-from">Period numbering from (yyyy-mm-dd; empty: off)</label>
        <input
          id="setting-period-numbering-from"
          data-testid="setting-period-numbering-from"
          [value]="periodNumberingFromInput()"
          (input)="periodNumberingFromInput.set(value($event)); periodNumberingFromEdited = true; settingsError.set(null)"
          (keydown.delete)="cleared($event, 'periodNumberingFrom')"
          (keydown.backspace)="cleared($event, 'periodNumberingFrom')"
        />
        <label for="setting-entry-date">Entry date, the default date of a new claim (yyyy-mm-dd; empty: off)</label>
        <input
          id="setting-entry-date"
          data-testid="setting-entry-date"
          [value]="entryDateInput()"
          (input)="entryDateInput.set(value($event)); entryDateEdited = true; settingsError.set(null)"
          (keydown.delete)="cleared($event, 'entryDate')"
          (keydown.backspace)="cleared($event, 'entryDate')"
        />
        <label for="setting-grace-days">Grace days before a period closes itself (a whole number; empty: off)</label>
        <input
          id="setting-grace-days"
          data-testid="setting-grace-days"
          [value]="graceDaysInput()"
          (input)="graceDaysInput.set(value($event)); graceDaysEdited = true; settingsError.set(null)"
          (keydown.delete)="cleared($event, 'graceDays')"
          (keydown.backspace)="cleared($event, 'graceDays')"
        />
        <label for="setting-escalate-after">Escalate a claim reminded this many days after its previous reminder (a whole number; empty: off)</label>
        <input
          id="setting-escalate-after"
          data-testid="setting-escalate-after"
          inputmode="numeric"
          [value]="escalateAfterInput()"
          (input)="escalateAfterInput.set(value($event)); escalateAfterEdited = true; settingsError.set(null)"
          (keydown.delete)="cleared($event, 'escalateAfter')"
          (keydown.backspace)="cleared($event, 'escalateAfter')"
        />
        <label for="setting-review-sla">Review SLA: a claim in review longer than this many days is late (a whole number; empty: none)</label>
        <input
          id="setting-review-sla"
          data-testid="setting-review-sla"
          inputmode="numeric"
          [value]="reviewSlaInput()"
          (input)="reviewSlaInput.set(value($event)); reviewSlaEdited = true; settingsError.set(null)"
          (keydown.delete)="cleared($event, 'reviewSla')"
          (keydown.backspace)="cleared($event, 'reviewSla')"
        />
        <label for="setting-proof-kinds">Proof kinds a claim above the proof threshold needs one of each of (receipt, photo; comma separated; empty: none)</label>
        <input
          id="setting-proof-kinds"
          data-testid="setting-proof-kinds"
          [value]="proofKindsInput()"
          (input)="proofKindsInput.set(value($event)); proofKindsEdited = true; settingsError.set(null)"
          (keydown.delete)="cleared($event, 'proofKinds')"
          (keydown.backspace)="cleared($event, 'proofKinds')"
        />
        <label for="setting-pot">Pot, split among each balanced pool's members (empty: none)</label>
        <input
          id="setting-pot"
          data-testid="setting-pot"
          inputmode="decimal"
          [value]="potInput()"
          (input)="potInput.set(value($event)); potEdited = true; settingsError.set(null)"
          (keydown.delete)="cleared($event, 'pot')"
          (keydown.backspace)="cleared($event, 'pot')"
        />
        <label for="setting-alert-threshold">Alert threshold: a person whose due-total goes above it is alerted (empty: off)</label>
        <input
          id="setting-alert-threshold"
          data-testid="setting-alert-threshold"
          inputmode="decimal"
          [value]="alertThresholdInput()"
          (input)="alertThresholdInput.set(value($event)); alertThresholdEdited = true; settingsError.set(null)"
          (keydown.delete)="cleared($event, 'alertThreshold')"
          (keydown.backspace)="cleared($event, 'alertThreshold')"
        />
        <label for="setting-fiscal-start-month">Month the fiscal year starts in (1 to 12)</label>
        <input
          id="setting-fiscal-start-month"
          data-testid="setting-fiscal-start-month"
          inputmode="numeric"
          [value]="fiscalStartMonthInput()"
          (input)="fiscalStartMonthInput.set(value($event)); fiscalStartMonthEdited = true; settingsError.set(null)"
        />
        <label for="setting-parent">Parent sheet, whose rates apply where this sheet has none (empty: none)</label>
        <input
          id="setting-parent"
          data-testid="setting-parent"
          [value]="parentInput()"
          (input)="parentInput.set(value($event)); parentEdited = true; settingsError.set(null)"
          (keydown.delete)="cleared($event, 'parent')"
          (keydown.backspace)="cleared($event, 'parent')"
        />
        @for (a of permissionActions; track a) {
          <label [for]="'setting-perm-' + a">Roles that may {{ a }} (comma separated; empty: {{ standing[a] }})</label>
          <input
            [id]="'setting-perm-' + a"
            [attr.data-testid]="'setting-perm-' + a"
            [value]="permissionInput()[a]"
            (input)="permissionTyped(a, value($event))"
          />
        }
        <button type="submit" data-testid="settings-save">Save settings</button>
        @if (settingsError(); as message) {
          <p role="alert" data-testid="settings-error">{{ message }}</p>
        }
      </form>
      <p>Clerk limit in force: <span data-testid="setting-clerk-limit-current">{{ (clerkLimit() ?? '') | figure }}</span></p>
      <p>Cap in force: <span data-testid="setting-cap-current">{{ session.cap() | figure }}</span></p>
      <p>Proof threshold in force: <span data-testid="setting-proof-threshold-current">{{ (proofThreshold() ?? '') | figure }}</span></p>
      @for (g of grades; track g) {
        <p>Proof threshold for grade {{ g }} in force:
          <span [attr.data-testid]="'setting-proof-threshold-' + g + '-current'">{{ (gradeProofThreshold()[g] ?? '') | figure }}</span></p>
      }
      <p>Note threshold in force: <span data-testid="setting-note-threshold-current">{{ (noteThreshold() ?? '') | figure }}</span></p>
      <p>Review threshold in force: <span data-testid="setting-review-threshold-current">{{ (reviewThreshold() ?? '') | figure }}</span></p>
      <p>Rounding mode in force: <span data-testid="setting-rounding-current">{{ rounding() ?? '' }}</span></p>
      <p>Floor in force: <span data-testid="setting-floor-current">{{ session.floor() | figure }}</span></p>
      <p>Terms in force: <span data-testid="setting-terms-current">{{ session.terms() }}</span></p>
      @for (g of grades; track g) {
        <p>Terms for grade {{ g }} in force:
          <span [attr.data-testid]="'setting-terms-' + g + '-current'">{{ gradeTerms()[g] ?? '' }}</span></p>
      }
      @for (g of grades; track g) {
        <p>Grace days for grade {{ g }} in force:
          <span [attr.data-testid]="'setting-grace-' + g + '-current'">{{ gradeGrace()[g] ?? '' }}</span></p>
      }
      <p>Late percent in force: <span data-testid="setting-late-pct-current">{{ session.latePct() | figure }}</span></p>
      <p>Policy date in force: <span data-testid="setting-policy-date-current">{{ session.policyDate() }}</span></p>
      <p>Period numbering from in force: <span data-testid="setting-period-numbering-from-current">{{ periodNumberingFrom() }}</span></p>
      <p>Entry date in force: <span data-testid="setting-entry-date-current">{{ entryDate() }}</span></p>
      <p>Grace days in force: <span data-testid="setting-grace-days-current">{{ graceDays() }}</span></p>
      <p>Escalate after days in force: <span data-testid="setting-escalate-after-current">{{ escalateAfter() }}</span></p>
      <p>Review SLA days in force: <span data-testid="setting-review-sla-current">{{ session.reviewSla() }}</span></p>
      <p>Proof kinds in force: <span data-testid="setting-proof-kinds-current">{{ session.proofKinds() }}</span></p>
      <p>Pot in force: <span data-testid="setting-pot-current">{{ session.pot() | figure }}</span></p>
      <p>Alert threshold in force: <span data-testid="setting-alert-threshold-current">{{ alertThreshold() | figure }}</span></p>
      <p>Fiscal year starts in month: <span data-testid="setting-fiscal-start-month-current">{{ session.fiscalStartMonth() }}</span></p>
      <p>Parent sheet in force: <span data-testid="setting-parent-current">{{ session.parent() }}</span></p>
      @for (a of permissionActions; track a) {
        <p>Roles that may {{ a }} in force:
          <span [attr.data-testid]="'setting-perm-' + a + '-current'">{{ permissions()[a] }}</span></p>
      }
      <h3>Roles</h3>
      <form (submit)="saveRole($event)" aria-label="Set a role">
        <label for="role-form-user">User</label>
        <input
          id="role-form-user"
          data-testid="role-form-user"
          [value]="roleUser()"
          (input)="roleUser.set(value($event)); roleError.set(null)"
        />
        <label for="role-form-role">Role ({{ roleNames }})</label>
        <input
          id="role-form-role"
          data-testid="role-form-role"
          [value]="roleName()"
          (input)="roleName.set(value($event)); roleError.set(null)"
        />
        <button type="submit" data-testid="role-form-submit">Set role</button>
        @if (roleError(); as message) {
          <p role="alert" data-testid="role-form-error">{{ message }}</p>
        }
      </form>
      <table>
        <caption>Roles set (any other name is a clerk)</caption>
        <thead>
          <tr>
            <th scope="col">User</th>
            <th scope="col">Role</th>
          </tr>
        </thead>
        <tbody>
          @for (r of roles(); track r.user) {
            <tr [attr.data-testid]="'role-row-' + r.user">
              <th scope="row">{{ r.user }}</th>
              <td data-testid="role-value">{{ r.role }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Settings {
  protected readonly session = inject(Session);
  private readonly api = inject(SettingsApi);

  protected readonly roleNames = ROLES.join(' or ');
  protected readonly clerkLimit = signal<string | null>(null);
  protected readonly roles = signal<RoleView[]>([]);
  protected readonly limit = signal('');
  protected readonly capInput = signal('');
  protected readonly proofThreshold = signal<string | null>(null);
  protected readonly proofThresholdInput = signal('');
  /** Each grade's proof threshold in force; null when it has none (the sheet's applies). */
  protected readonly gradeProofThreshold = signal<Record<Grade, string | null>>({ A: null, B: null, C: null });
  protected readonly gradeProofThresholdInput = signal<Record<Grade, string>>({ A: '', B: '', C: '' });
  protected readonly noteThreshold = signal<string | null>(null);
  protected readonly noteThresholdInput = signal('');
  protected readonly reviewThreshold = signal<string | null>(null);
  protected readonly reviewThresholdInput = signal('');
  protected readonly rounding = signal<string | null>(null);
  protected readonly roundingInput = signal('');
  protected readonly floorInput = signal('');
  protected readonly termsInput = signal('');
  protected readonly grades = GRADES;
  /** Each grade's own terms in force; null when it uses the sheet's terms. */
  protected readonly gradeTerms = signal<Record<Grade, number | null>>({ A: null, B: null, C: null });
  protected readonly gradeTermsInput = signal<Record<Grade, string>>({ A: '', B: '', C: '' });
  /** Each grade's grace in force, in days; null when it has none. */
  protected readonly gradeGrace = signal<Record<Grade, number | null>>({ A: null, B: null, C: null });
  protected readonly gradeGraceInput = signal<Record<Grade, string>>({ A: '', B: '', C: '' });
  protected readonly latePctInput = signal('');
  /** The from-date typed for each dated setting; empty for the sheet's today. */
  protected readonly fromInput = signal<Record<DatedKey, string>>(Settings.noFroms());
  protected readonly policyDateInput = signal('');
  protected readonly periodNumberingFrom = signal('');
  protected readonly periodNumberingFromInput = signal('');
  protected readonly entryDate = signal('');
  protected readonly entryDateInput = signal('');
  protected readonly graceDays = signal('');
  protected readonly graceDaysInput = signal('');
  protected readonly escalateAfter = signal('');
  protected readonly escalateAfterInput = signal('');
  protected readonly reviewSlaInput = signal('');
  protected readonly proofKindsInput = signal('');
  protected readonly potInput = signal('');
  protected readonly alertThreshold = signal('');
  protected readonly alertThresholdInput = signal('');
  protected readonly parentInput = signal('');
  protected readonly fiscalStartMonthInput = signal('');
  /** Which settings were typed into since the last save: a save changes only those. */
  protected limitEdited = false;
  protected capEdited = false;
  protected proofThresholdEdited = false;
  protected noteThresholdEdited = false;
  protected reviewThresholdEdited = false;
  protected roundingEdited = false;
  protected floorEdited = false;
  protected termsEdited = false;
  protected latePctEdited = false;
  protected gradeTermsEdited = new Set<Grade>();
  protected gradeGraceEdited = new Set<Grade>();
  protected gradeProofThresholdEdited = new Set<Grade>();
  protected policyDateEdited = false;
  protected periodNumberingFromEdited = false;
  protected entryDateEdited = false;
  protected graceDaysEdited = false;
  protected escalateAfterEdited = false;
  protected reviewSlaEdited = false;
  protected proofKindsEdited = false;
  protected potEdited = false;
  protected alertThresholdEdited = false;
  protected parentEdited = false;
  protected fiscalStartMonthEdited = false;
  protected readonly permissionActions = PERMISSION_ACTIONS;
  /** Who may take each action under its standing rule, kept when its entry is blank. */
  protected readonly standing: Record<PermissionAction, string> = {
    open: 'clerk and steward', settle: 'clerk and steward', void: 'clerk and steward',
    reassign: 'steward', close: 'clerk and steward', reopen: 'steward',
  };
  /** The roles set for each action; blank for its standing rule. */
  protected readonly permissions = signal<Record<PermissionAction, string>>(Settings.noPermissions());
  protected readonly permissionInput = signal<Record<PermissionAction, string>>(Settings.noPermissions());
  protected permissionsEdited = new Set<PermissionAction>();
  protected readonly roleUser = signal('');
  protected readonly roleName = signal('');
  protected readonly settingsError = signal<string | null>(null);
  protected readonly roleError = signal<string | null>(null);
  /** A submit is waiting for its answer: a second submit meanwhile is ignored. */
  private busy = false;
  /** The settings saves in flight: a save submitted meanwhile is written after them, in turn. */
  private saving: Promise<void> = Promise.resolve();

  private static noFroms(): Record<DatedKey, string> {
    return { 'clerk-limit': '', cap: '', floor: '', terms: '', 'terms-A': '', 'terms-B': '', 'terms-C': '', 'grace-A': '', 'grace-B': '', 'grace-C': '', 'late-pct': '', 'proof-threshold': '', 'proof-threshold-A': '', 'proof-threshold-B': '', 'proof-threshold-C': '', 'note-threshold': '', 'review-threshold': '', rounding: '' };
  }

  private static noPermissions(): Record<PermissionAction, string> {
    return { open: '', settle: '', void: '', reassign: '', close: '', reopen: '' };
  }

  constructor() {
    // Every sheet load re-reads the sheet's settings and roles from the store.
    effect(() => {
      const sheet = this.session.sheet();
      const loads = this.session.loads();
      untracked(() => {
        this.clerkLimit.set(null);
        this.proofThreshold.set(null);
        this.noteThreshold.set(null);
        this.reviewThreshold.set(null);
        this.rounding.set(null);
        this.gradeTerms.set({ A: null, B: null, C: null });
        this.gradeGrace.set({ A: null, B: null, C: null });
        this.gradeProofThreshold.set({ A: null, B: null, C: null });
        this.periodNumberingFrom.set('');
        this.entryDate.set('');
        this.graceDays.set('');
        this.escalateAfter.set('');
        this.alertThreshold.set('');
        this.permissions.set(Settings.noPermissions());
        this.roles.set([]);
        this.fromInput.set(Settings.noFroms());
        this.settingsError.set(null);
        this.roleError.set(null);
        if (sheet) void this.reload(sheet, loads);
      });
    });
    // A change to the sheet's today changes which dated values are in force.
    let shownToday: string | null = null;
    effect(() => {
      const today = this.session.today();
      untracked(() => {
        const sheet = this.session.sheet();
        const changed = shownToday !== null && today !== shownToday;
        shownToday = today;
        if (sheet && changed) void this.todayChanged(sheet);
      });
    });
  }

  /** A from-date typed: saving then records that setting from it. */
  protected fromTyped(key: DatedKey, value: string): void {
    this.fromInput.update((all) => ({ ...all, [key]: value }));
    if (key === 'clerk-limit') this.limitEdited = true;
    else if (key === 'cap') this.capEdited = true;
    else if (key === 'proof-threshold') this.proofThresholdEdited = true;
    else if (key === 'note-threshold') this.noteThresholdEdited = true;
    else if (key === 'review-threshold') this.reviewThresholdEdited = true;
    else if (key === 'rounding') this.roundingEdited = true;
    else if (key === 'floor') this.floorEdited = true;
    else if (key === 'terms') this.termsEdited = true;
    else if (key === 'late-pct') this.latePctEdited = true;
    else if (key.startsWith('proof-threshold-')) this.gradeProofThresholdEdited.add(key.slice('proof-threshold-'.length) as Grade);
    else if (key.startsWith('grace-')) this.gradeGraceEdited.add(key.slice('grace-'.length) as Grade);
    else this.gradeTermsEdited.add(key.slice('terms-'.length) as Grade);
    this.settingsError.set(null);
  }

  protected gradeKey(grade: Grade): DatedKey {
    return `terms-${grade}`;
  }

  protected graceKey(grade: Grade): DatedKey {
    return `grace-${grade}`;
  }

  protected proofKey(grade: Grade): DatedKey {
    return `proof-threshold-${grade}`;
  }

  /**
   * Delete or Backspace in an empty date field: no input follows, but clearing it again is still a
   * change, so a save sets it empty.
   */
  protected cleared(event: Event, setting: 'cap' | 'proofThreshold' | 'noteThreshold' | 'reviewThreshold' | 'rounding' | 'policyDate' | 'periodNumberingFrom' | 'entryDate' | 'graceDays' | 'escalateAfter' | 'reviewSla' | 'proofKinds' | 'pot' | 'alertThreshold' | 'parent'): void {
    if (this.value(event) !== '') return;
    if (setting === 'cap') {
      this.capInput.set('');
      this.capEdited = true;
    } else if (setting === 'proofThreshold') {
      this.proofThresholdInput.set('');
      this.proofThresholdEdited = true;
    } else if (setting === 'noteThreshold') {
      this.noteThresholdInput.set('');
      this.noteThresholdEdited = true;
    } else if (setting === 'reviewThreshold') {
      this.reviewThresholdInput.set('');
      this.reviewThresholdEdited = true;
    } else if (setting === 'rounding') {
      this.roundingInput.set('');
      this.roundingEdited = true;
    } else if (setting === 'policyDate') {
      this.policyDateInput.set('');
      this.policyDateEdited = true;
    } else if (setting === 'periodNumberingFrom') {
      this.periodNumberingFromInput.set('');
      this.periodNumberingFromEdited = true;
    } else if (setting === 'entryDate') {
      this.entryDateInput.set('');
      this.entryDateEdited = true;
    } else if (setting === 'pot') {
      this.potInput.set('');
      this.potEdited = true;
    } else if (setting === 'alertThreshold') {
      this.alertThresholdInput.set('');
      this.alertThresholdEdited = true;
    } else if (setting === 'escalateAfter') {
      this.escalateAfterInput.set('');
      this.escalateAfterEdited = true;
    } else if (setting === 'reviewSla') {
      this.reviewSlaInput.set('');
      this.reviewSlaEdited = true;
    } else if (setting === 'proofKinds') {
      this.proofKindsInput.set('');
      this.proofKindsEdited = true;
    } else if (setting === 'parent') {
      this.parentInput.set('');
      this.parentEdited = true;
    } else {
      this.graceDaysInput.set('');
      this.graceDaysEdited = true;
    }
    this.settingsError.set(null);
  }

  protected gradeTermsTyped(grade: Grade, value: string): void {
    this.gradeTermsInput.update((all) => ({ ...all, [grade]: value }));
    this.gradeTermsEdited.add(grade);
    this.settingsError.set(null);
  }

  protected gradeGraceTyped(grade: Grade, value: string): void {
    this.gradeGraceInput.update((all) => ({ ...all, [grade]: value }));
    this.gradeGraceEdited.add(grade);
    this.settingsError.set(null);
  }

  protected gradeProofThresholdTyped(grade: Grade, value: string): void {
    this.gradeProofThresholdInput.update((all) => ({ ...all, [grade]: value }));
    this.gradeProofThresholdEdited.add(grade);
    this.settingsError.set(null);
  }

  protected permissionTyped(action: PermissionAction, value: string): void {
    this.permissionInput.update((all) => ({ ...all, [action]: value }));
    this.permissionsEdited.add(action);
    this.settingsError.set(null);
  }

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected async saveSettings(event: Event): Promise<void> {
    event.preventDefault();
    this.settingsError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const limit = this.limit().trim();
    const cap = this.capInput().trim();
    const floor = this.floorInput().trim();
    const rounding = this.roundingInput().trim();
    const proofThreshold = this.proofThresholdInput().trim();
    const noteThreshold = this.noteThresholdInput().trim();
    const reviewThreshold = this.reviewThresholdInput().trim();
    // With nothing typed since the last save, saving sets the clerk limit again as it stands.
    const terms = this.termsInput().trim();
    const latePct = this.latePctInput().trim();
    const policyDate = this.policyDateInput().trim();
    const periodNumberingFrom = this.periodNumberingFromInput().trim();
    const entryDate = this.entryDateInput().trim();
    const graceDays = this.graceDaysInput().trim();
    const escalateAfter = this.escalateAfterInput().trim();
    const reviewSla = this.reviewSlaInput().trim();
    const proofKinds = this.proofKindsInput().trim();
    const pot = this.potInput().trim();
    const alertThreshold = this.alertThresholdInput().trim();
    const parent = this.parentInput().trim();
    const fiscalStartMonth = this.fiscalStartMonthInput().trim();
    const saveLimit =
      this.limitEdited ||
      (!this.capEdited && !this.proofThresholdEdited && !this.noteThresholdEdited && !this.reviewThresholdEdited && !this.roundingEdited && !this.floorEdited && !this.termsEdited && this.gradeTermsEdited.size === 0 && this.gradeGraceEdited.size === 0 && this.gradeProofThresholdEdited.size === 0 && !this.latePctEdited && !this.policyDateEdited &&
        !this.periodNumberingFromEdited && !this.entryDateEdited && !this.graceDaysEdited && !this.escalateAfterEdited && !this.reviewSlaEdited && !this.proofKindsEdited && !this.potEdited && !this.alertThresholdEdited && !this.parentEdited && !this.fiscalStartMonthEdited && this.permissionsEdited.size === 0);
    const saveCap = this.capEdited;
    const saveProofThreshold = this.proofThresholdEdited;
    const saveNoteThreshold = this.noteThresholdEdited;
    const saveReviewThreshold = this.reviewThresholdEdited;
    const saveRounding = this.roundingEdited;
    const saveFloor = this.floorEdited;
    const saveTerms = this.termsEdited;
    const saveGradeTerms = GRADES.filter((g) => this.gradeTermsEdited.has(g))
      .map((g) => ({ grade: g, value: this.gradeTermsInput()[g].trim() }));
    const saveGradeGrace = GRADES.filter((g) => this.gradeGraceEdited.has(g))
      .map((g) => ({ grade: g, value: this.gradeGraceInput()[g].trim() }));
    const saveGradeProofThresholds = GRADES.filter((g) => this.gradeProofThresholdEdited.has(g))
      .map((g) => ({ grade: g, value: this.gradeProofThresholdInput()[g].trim() }));
    const saveLatePct = this.latePctEdited;
    const savePolicyDate = this.policyDateEdited;
    const savePeriodNumberingFrom = this.periodNumberingFromEdited;
    const saveEntryDate = this.entryDateEdited;
    const saveGraceDays = this.graceDaysEdited;
    const saveEscalateAfter = this.escalateAfterEdited;
    const saveReviewSla = this.reviewSlaEdited;
    const saveProofKinds = this.proofKindsEdited;
    const savePot = this.potEdited;
    const saveAlertThreshold = this.alertThresholdEdited;
    const saveParent = this.parentEdited;
    const saveFiscalStartMonth = this.fiscalStartMonthEdited;
    const savePermissions = PERMISSION_ACTIONS.filter((a) => this.permissionsEdited.has(a))
      .map((a) => ({ action: a, value: this.permissionInput()[a].trim() }));
    const froms = this.fromInput();
    const from = (key: DatedKey) => froms[key].trim();
    const savedKeys: DatedKey[] = [
      ...(saveLimit ? (['clerk-limit'] as const) : []),
      ...(saveCap ? (['cap'] as const) : []),
      ...(saveProofThreshold ? (['proof-threshold'] as const) : []),
      ...saveGradeProofThresholds.map((t) => this.proofKey(t.grade)),
      ...(saveNoteThreshold ? (['note-threshold'] as const) : []),
      ...(saveReviewThreshold ? (['review-threshold'] as const) : []),
      ...(saveRounding ? (['rounding'] as const) : []),
      ...(saveFloor ? (['floor'] as const) : []),
      ...(saveTerms ? (['terms'] as const) : []),
      ...saveGradeTerms.map((t) => this.gradeKey(t.grade)),
      ...saveGradeGrace.map((t) => this.graceKey(t.grade)),
      ...(saveLatePct ? (['late-pct'] as const) : []),
    ];
    if (savedKeys.some((key) => !SETTING_FROM.test(from(key)))) {
      this.settingsError.set('Type a from-date as yyyy-mm-dd, or leave it empty for the sheet\'s today.');
      return;
    }
    if (saveLimit && !LIMIT.test(limit)) {
      this.settingsError.set('Type a clerk limit of 0.00 or more, with up to two decimals, or leave it empty for no limit.');
      return;
    }
    if (saveCap && !CAP.test(cap)) {
      this.settingsError.set('Type a cap of 0.00 or more, with up to two decimals, or leave it empty for no cap.');
      return;
    }
    if (saveProofThreshold && !PROOF_THRESHOLD.test(proofThreshold)) {
      this.settingsError.set('Type a proof threshold of 0.00 or more, with up to two decimals, or leave it empty for none.');
      return;
    }
    if (saveGradeProofThresholds.some((t) => !PROOF_THRESHOLD.test(t.value))) {
      this.settingsError.set('Type a grade\'s proof threshold of 0.00 or more, with up to two decimals, or leave it empty for the sheet\'s.');
      return;
    }
    if (saveNoteThreshold && !NOTE_THRESHOLD.test(noteThreshold)) {
      this.settingsError.set('Type a note threshold of 0.00 or more, with up to two decimals, or leave it empty for none.');
      return;
    }
    if (saveReviewThreshold && !NOTE_THRESHOLD.test(reviewThreshold)) {
      this.settingsError.set('Type a review threshold of 0.00 or more, with up to two decimals, or leave it empty for none.');
      return;
    }
    if (saveRounding && !ROUNDING.test(rounding)) {
      this.settingsError.set('Type a rounding mode of half-up or half-even, or leave it empty for none.');
      return;
    }
    if (saveFloor && !FLOOR.test(floor)) {
      this.settingsError.set('Type a floor of 0.00 or more, with up to two decimals.');
      return;
    }
    if (saveTerms && !TERMS.test(terms)) {
      this.settingsError.set('Type terms as a whole number of days.');
      return;
    }
    if (saveGradeTerms.some((t) => !GRADE_TERMS.test(t.value))) {
      this.settingsError.set('Type a grade\'s terms as a whole number of days, or leave them empty for the sheet\'s terms.');
      return;
    }
    if (saveGradeGrace.some((t) => !GRADE_GRACE.test(t.value))) {
      this.settingsError.set('Type a grade\'s grace as a whole number of days, or leave it empty for none.');
      return;
    }
    if (saveLatePct && !LATE_PCT.test(latePct)) {
      this.settingsError.set('Type a late percent of 0.00 or more, with up to two decimals.');
      return;
    }
    if (savePolicyDate && !POLICY_DATE.test(policyDate)) {
      this.settingsError.set('Type a policy date as yyyy-mm-dd, or leave it empty for the sheet\'s today.');
      return;
    }
    if (savePeriodNumberingFrom && !PERIOD_NUMBERING_FROM.test(periodNumberingFrom)) {
      this.settingsError.set('Type the date period numbering is from as yyyy-mm-dd, or leave it empty for off.');
      return;
    }
    if (saveEntryDate && !ENTRY_DATE.test(entryDate)) {
      this.settingsError.set('Type an entry date as yyyy-mm-dd, or leave it empty for off.');
      return;
    }
    if (saveGraceDays && !GRACE_DAYS.test(graceDays)) {
      this.settingsError.set('Type grace days as a whole number of days, or leave them empty for off.');
      return;
    }
    if (saveEscalateAfter && !GRACE_DAYS.test(escalateAfter)) {
      this.settingsError.set('Type the escalation gap as a whole number of days, or leave it empty for off.');
      return;
    }
    if (saveReviewSla && !GRACE_DAYS.test(reviewSla)) {
      this.settingsError.set('Type the review SLA as a whole number of days, or leave it empty for none.');
      return;
    }
    if (saveProofKinds && !PROOF_KIND_LIST.test(proofKinds)) {
      this.settingsError.set('Type the proof kinds as receipt or photo, comma separated, or leave them empty for none.');
      return;
    }
    if (savePot && !POT.test(pot)) {
      this.settingsError.set('Type a pot of 0.00 or more, with up to two decimals, or leave it empty for none.');
      return;
    }
    if (saveAlertThreshold && !ALERT_THRESHOLD.test(alertThreshold)) {
      this.settingsError.set('Type an alert threshold of 0.00 or more, with up to two decimals, or leave it empty for off.');
      return;
    }
    if (saveParent && !PARENT.test(parent)) {
      this.settingsError.set('Type a parent sheet id of letters, digits, dots, dashes or underscores, or leave it empty for none.');
      return;
    }
    if (saveFiscalStartMonth && !FISCAL_START_MONTH.test(fiscalStartMonth)) {
      this.settingsError.set('Type the month the fiscal year starts in as a number from 1 to 12.');
      return;
    }
    if (savePermissions.some((p) => !PERMISSION.test(p.value))) {
      this.settingsError.set('Type the roles that may take an action as clerk or steward, comma separated, or leave them empty for the standing rule.');
      return;
    }
    // What was typed is taken now: anything typed meanwhile is saved by the next submit.
    if (saveLimit) this.limitEdited = false;
    if (saveCap) this.capEdited = false;
    if (saveProofThreshold) this.proofThresholdEdited = false;
    for (const t of saveGradeProofThresholds) this.gradeProofThresholdEdited.delete(t.grade);
    if (saveNoteThreshold) this.noteThresholdEdited = false;
    if (saveReviewThreshold) this.reviewThresholdEdited = false;
    if (saveRounding) this.roundingEdited = false;
    if (saveFloor) this.floorEdited = false;
    if (saveTerms) this.termsEdited = false;
    for (const t of saveGradeTerms) this.gradeTermsEdited.delete(t.grade);
    for (const t of saveGradeGrace) this.gradeGraceEdited.delete(t.grade);
    if (saveLatePct) this.latePctEdited = false;
    if (savePolicyDate) this.policyDateEdited = false;
    if (savePeriodNumberingFrom) this.periodNumberingFromEdited = false;
    if (saveEntryDate) this.entryDateEdited = false;
    if (saveGraceDays) this.graceDaysEdited = false;
    if (saveEscalateAfter) this.escalateAfterEdited = false;
    if (saveReviewSla) this.reviewSlaEdited = false;
    if (saveProofKinds) this.proofKindsEdited = false;
    if (savePot) this.potEdited = false;
    if (saveAlertThreshold) this.alertThresholdEdited = false;
    if (saveParent) this.parentEdited = false;
    if (saveFiscalStartMonth) this.fiscalStartMonthEdited = false;
    for (const p of savePermissions) this.permissionsEdited.delete(p.action);
    this.fromInput.update((all) => {
      const next = { ...all };
      for (const key of savedKeys) next[key] = '';
      return next;
    });
    const write = async () => {
      try {
        if (saveLimit) {
          this.inForce(await this.api.setClerkLimit(sheet, limit, from('clerk-limit')));
        }
        if (saveCap) {
          const view = await this.api.setCap(sheet, cap, from('cap'));
          this.inForce(view);
          if (!this.capEdited) this.capInput.set(view.cap ?? '');
        }
        if (saveProofThreshold) {
          const view = await this.api.setProofThreshold(sheet, proofThreshold, from('proof-threshold'));
          this.inForce(view);
          if (!this.proofThresholdEdited) this.proofThresholdInput.set(view.proofThreshold ?? '');
        }
        for (const t of saveGradeProofThresholds) {
          this.inForce(await this.api.setGradeProofThreshold(sheet, t.grade, t.value, from(this.proofKey(t.grade))));
        }
        if (saveNoteThreshold) {
          const view = await this.api.setNoteThreshold(sheet, noteThreshold, from('note-threshold'));
          this.inForce(view);
          if (!this.noteThresholdEdited) this.noteThresholdInput.set(view.noteThreshold ?? '');
        }
        if (saveReviewThreshold) {
          const view = await this.api.setReviewThreshold(sheet, reviewThreshold, from('review-threshold'));
          this.inForce(view);
          if (!this.reviewThresholdEdited) this.reviewThresholdInput.set(view.reviewThreshold ?? '');
        }
        if (saveRounding) {
          const view = await this.api.setRounding(sheet, rounding, from('rounding'));
          this.inForce(view);
          if (!this.roundingEdited) this.roundingInput.set(view.rounding ?? '');
        }
        if (saveFloor) {
          this.inForce(await this.api.setFloor(sheet, floor, from('floor')));
        }
        if (saveTerms) {
          this.inForce(await this.api.setTerms(sheet, terms, from('terms')));
        }
        for (const t of saveGradeTerms) {
          this.inForce(await this.api.setGradeTerms(sheet, t.grade, t.value, from(this.gradeKey(t.grade))));
        }
        for (const t of saveGradeGrace) {
          this.inForce(await this.api.setGradeGrace(sheet, t.grade, t.value, from(this.graceKey(t.grade))));
        }
        if (saveLatePct) {
          this.inForce(await this.api.setLatePct(sheet, latePct, from('late-pct')));
        }
        if (savePolicyDate) {
          const view = await this.api.setPolicyDate(sheet, policyDate);
          this.session.policyDate.set(view.policyDate ?? '');
          if (!this.policyDateEdited) this.policyDateInput.set(view.policyDate ?? '');
        }
        if (savePeriodNumberingFrom) {
          const view = await this.api.setPeriodNumberingFrom(sheet, periodNumberingFrom);
          this.periodNumberingFrom.set(view.periodNumberingFrom ?? '');
          if (!this.periodNumberingFromEdited) this.periodNumberingFromInput.set(view.periodNumberingFrom ?? '');
        }
        if (saveEntryDate) {
          const view = await this.api.setEntryDate(sheet, entryDate);
          this.entryDate.set(view.entryDate ?? '');
          if (!this.entryDateEdited) this.entryDateInput.set(view.entryDate ?? '');
        }
        if (saveGraceDays) {
          const view = await this.api.setGraceDays(sheet, graceDays);
          this.graceDays.set(view.graceDays?.toString() ?? '');
          if (!this.graceDaysEdited) this.graceDaysInput.set(view.graceDays?.toString() ?? '');
        }
        if (saveEscalateAfter) {
          const view = await this.api.setEscalateAfter(sheet, escalateAfter);
          this.escalateAfter.set(view.escalateAfter?.toString() ?? '');
          if (!this.escalateAfterEdited) this.escalateAfterInput.set(view.escalateAfter?.toString() ?? '');
        }
        if (saveReviewSla) {
          const view = await this.api.setReviewSla(sheet, reviewSla);
          this.session.reviewSla.set(view.reviewSla?.toString() ?? '');
          if (!this.reviewSlaEdited) this.reviewSlaInput.set(view.reviewSla?.toString() ?? '');
        }
        if (saveProofKinds) {
          const view = await this.api.setProofKinds(sheet, proofKinds);
          this.session.proofKinds.set(view.proofKinds ?? '');
          if (!this.proofKindsEdited) this.proofKindsInput.set(view.proofKinds ?? '');
        }
        if (savePot) {
          const view = await this.api.setPot(sheet, pot);
          this.session.pot.set(view.pot ?? '');
          if (!this.potEdited) this.potInput.set(view.pot ?? '');
        }
        if (saveAlertThreshold) {
          const view = await this.api.setAlertThreshold(sheet, alertThreshold);
          this.alertThreshold.set(view.alertThreshold ?? '');
          if (!this.alertThresholdEdited) this.alertThresholdInput.set(view.alertThreshold ?? '');
        }
        if (saveParent) {
          const view = await this.api.setParent(sheet, parent);
          this.session.parent.set(view.parent ?? '');
          if (!this.parentEdited) this.parentInput.set(view.parent ?? '');
        }
        if (saveFiscalStartMonth) {
          const view = await this.api.setFiscalStartMonth(sheet, fiscalStartMonth);
          this.session.fiscalStartMonth.set(view.fiscalStartMonth);
          if (!this.fiscalStartMonthEdited) this.fiscalStartMonthInput.set(view.fiscalStartMonth.toString());
        }
        for (const p of savePermissions) {
          const view = await this.api.setPermission(sheet, p.action, p.value);
          this.permissions.set(view.permissions);
          if (!this.permissionsEdited.has(p.action)) {
            this.permissionInput.update((all) => ({ ...all, [p.action]: view.permissions[p.action] }));
          }
        }
      } catch {
        this.settingsError.set('The settings could not be saved.');
      }
    };
    this.saving = this.saving.then(write);
    await this.saving;
  }

  protected async saveRole(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy) return;
    this.roleError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const user = this.roleUser().trim();
    const role = this.roleName().trim();
    if (!user) {
      this.roleError.set('Type the user name to set a role for.');
      return;
    }
    if (!(ROLES as readonly string[]).includes(role)) {
      this.roleError.set(`A role is ${this.roleNames}.`);
      return;
    }
    this.busy = true;
    try {
      const saved = await this.api.setRole(sheet, { user, role: role as RoleName });
      this.roles.update((all) =>
        [...all.filter((r) => r.user !== saved.user), saved].sort((a, b) => a.user.localeCompare(b.user)),
      );
      this.roleUser.set('');
      this.roleName.set('');
    } catch {
      this.roleError.set(`The role of ${user} could not be set.`);
    } finally {
      this.busy = false;
    }
  }

  /** Show the dated settings as in force on the sheet's today, and the floor's and late percent's values by from-date. */
  private inForce(view: SettingsView): void {
    this.clerkLimit.set(view.clerkLimit);
    this.session.cap.set(view.cap ?? '');
    this.proofThreshold.set(view.proofThreshold);
    this.gradeProofThreshold.set({ A: view.gradeProofThresholds['A'], B: view.gradeProofThresholds['B'], C: view.gradeProofThresholds['C'] });
    this.noteThreshold.set(view.noteThreshold);
    this.reviewThreshold.set(view.reviewThreshold);
    this.rounding.set(view.rounding);
    this.session.roundingHistory.set(view.roundingHistory);
    this.session.floor.set(view.floor);
    this.session.floorHistory.set(view.floorHistory);
    this.session.terms.set(view.terms);
    this.gradeTerms.set({ A: view.termsA, B: view.termsB, C: view.termsC });
    this.gradeGrace.set({ A: view.graceA, B: view.graceB, C: view.graceC });
    this.session.graceHistory.set(view.graceHistory);
    this.session.latePct.set(view.latePct);
    this.session.latePctHistory.set(view.latePctHistory);
  }

  /** The sheet's today changed: re-read which values are in force on it. */
  private async todayChanged(sheet: string): Promise<void> {
    const settings = await this.api.get(sheet);
    if (this.session.sheet() === sheet) this.inForce(settings);
  }

  private async reload(sheet: string, loads: number): Promise<void> {
    const [settings, roles] = await Promise.all([this.api.get(sheet), this.api.roles(sheet)]);
    // A later load may have started while this one was in flight: only the latest is shown.
    if (this.session.sheet() === sheet && this.session.loads() === loads) {
      this.inForce(settings);
      // The cap's input starts at the cap in force, so clearing it is a change.
      this.capInput.set(settings.cap ?? '');
      this.capEdited = false;
      // So does the proof threshold's.
      this.proofThresholdInput.set(settings.proofThreshold ?? '');
      this.proofThresholdEdited = false;
      // And the note threshold's.
      this.noteThresholdInput.set(settings.noteThreshold ?? '');
      this.noteThresholdEdited = false;
      // And the review threshold's.
      this.reviewThresholdInput.set(settings.reviewThreshold ?? '');
      this.reviewThresholdEdited = false;
      // And the rounding mode's.
      this.roundingInput.set(settings.rounding ?? '');
      this.roundingEdited = false;
      this.session.policyDate.set(settings.policyDate ?? '');
      // The policy date's input starts at the date in force, so clearing it is a change.
      this.policyDateInput.set(settings.policyDate ?? '');
      this.policyDateEdited = false;
      // Period numbering from starts at the date in force too, so clearing it is a change.
      this.periodNumberingFrom.set(settings.periodNumberingFrom ?? '');
      this.periodNumberingFromInput.set(settings.periodNumberingFrom ?? '');
      this.periodNumberingFromEdited = false;
      // The entry date starts at the date in force too, so clearing it is a change.
      this.entryDate.set(settings.entryDate ?? '');
      this.entryDateInput.set(settings.entryDate ?? '');
      this.entryDateEdited = false;
      // Grace days start at those in force, so clearing them is a change.
      this.graceDays.set(settings.graceDays?.toString() ?? '');
      this.graceDaysInput.set(settings.graceDays?.toString() ?? '');
      this.graceDaysEdited = false;
      // The escalation gap starts at the one set, so clearing it is a change.
      this.escalateAfter.set(settings.escalateAfter?.toString() ?? '');
      this.escalateAfterInput.set(settings.escalateAfter?.toString() ?? '');
      this.escalateAfterEdited = false;
      // The review SLA starts at the one set, so clearing it is a change.
      this.session.reviewSla.set(settings.reviewSla?.toString() ?? '');
      this.reviewSlaInput.set(settings.reviewSla?.toString() ?? '');
      this.reviewSlaEdited = false;
      // The proof kinds start at those set, so clearing them is a change.
      this.session.proofKinds.set(settings.proofKinds ?? '');
      this.proofKindsInput.set(settings.proofKinds ?? '');
      this.proofKindsEdited = false;
      // The pot starts at the one in force, so clearing it is a change.
      this.session.pot.set(settings.pot ?? '');
      this.potInput.set(settings.pot ?? '');
      this.potEdited = false;
      // The alert threshold starts at the one set, so clearing it is a change.
      this.alertThreshold.set(settings.alertThreshold ?? '');
      this.alertThresholdInput.set(settings.alertThreshold ?? '');
      this.alertThresholdEdited = false;
      // The parent starts at the one set, so clearing it is a change.
      this.session.parent.set(settings.parent ?? '');
      this.parentInput.set(settings.parent ?? '');
      this.parentEdited = false;
      this.session.fiscalStartMonth.set(settings.fiscalStartMonth);
      this.fiscalStartMonthInput.set(settings.fiscalStartMonth.toString());
      this.fiscalStartMonthEdited = false;
      // Each action's roles start at those in force, so clearing them is a change.
      this.permissions.set(settings.permissions);
      this.permissionInput.set({ ...settings.permissions });
      this.permissionsEdited.clear();
      this.roles.set(roles);
    }
  }
}
