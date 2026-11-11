import { HttpClient } from '@angular/common/http';
import { Component, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { DatedValue, Session } from '../../core/session';

interface SettingsView {
  /** The highest due a clerk may settle in force on the sheet's today, two decimals; null when there is no limit. */
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
  /** The date (yyyy-mm-dd) a new claim is dated by default in place of the sheet's today; null: off. */
  entryDate: string | null;
  /** The most a person's due-total can be, two decimals; null when there is no cap. */
  cap: string | null;
  /** The days past its to-date after which a change of today closes an open period; null: off. */
  graceDays: number | null;
  /** The due above which a claim cannot be settled or approved without a proof, two decimals; null when there is none. */
  proofThreshold: string | null;
  /** The due above which a claim cannot be opened with an empty note, two decimals; null when there is none. */
  noteThreshold: string | null;
  /** The permission matrix, by action: the roles that may take it, comma separated; null: the standing rule. */
  permissions: Record<string, string | null>;
  /** Every value recorded of each dated setting, by key, from-date first to last. */
  dated: Record<string, DatedValue[]>;
}

/** A delegation of the steward role to a user by a steward, from and to dates (yyyy-mm-dd) inclusive. */
interface DelegationView {
  id: number;
  user: string;
  by: string;
  from: string;
  to: string;
}

interface RoleView {
  user: string;
  /** clerk, steward or viewer; a user name not listed is a clerk. */
  role: string;
}

const ROLES = ['clerk', 'steward', 'viewer'];
const GRADES = ['A', 'B', 'C'];
/** The actions the permission matrix decides. */
const ACTIONS = ['open', 'settle', 'void', 'reassign', 'close', 'reopen'];
/** Roles typed for an action: clerk and/or steward, comma separated; blank keeps the standing rule. */
const PERM = /^((clerk|steward)(\s*,\s*(clerk|steward))*)?$/;
/** A date typed as yyyy-mm-dd. */
const DATE = /^\d{4}-\d{2}-\d{2}$/;

// The sheet's settings (dated, each saved from a from-date: its clerk limit, its floor, its terms and each grade's own, its late percent, its cap, its proof threshold and its note threshold; not dated: its policy date, its period numbering from, its entry date, its grace days and its permission matrix) and the roles of its user names, each with the form that sets it.
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
          (focus)="lastFocused = 'clerkLimit'"
          (input)="clerkLimit.set(value($event)); clerkLimitEdited = true; settingsError.set(null)"
        />
        <label for="setting-clerk-limit-from">From (blank: the sheet's today)</label>
        <input
          id="setting-clerk-limit-from"
          data-testid="setting-clerk-limit-from"
          type="date"
          [value]="froms()['clerk-limit'] ?? ''"
          (input)="setFrom('clerk-limit', value($event))"
        />
        <label for="setting-floor">Floor (the least a live due can be)</label>
        <input
          id="setting-floor"
          data-testid="setting-floor"
          inputmode="decimal"
          [value]="floor()"
          (focus)="lastFocused = 'floor'"
          (input)="floor.set(value($event)); floorEdited = true; settingsError.set(null)"
        />
        <label for="setting-floor-from">From (blank: the sheet's today)</label>
        <input
          id="setting-floor-from"
          data-testid="setting-floor-from"
          type="date"
          [value]="froms()['floor'] ?? ''"
          (input)="setFrom('floor', value($event))"
        />
        <label for="setting-terms">Terms (days after its date a claim is due by)</label>
        <input
          id="setting-terms"
          data-testid="setting-terms"
          inputmode="numeric"
          [value]="terms()"
          (focus)="lastFocused = 'terms'"
          (input)="terms.set(value($event)); termsEdited = true; settingsError.set(null)"
        />
        <label for="setting-terms-from">From (blank: the sheet's today)</label>
        <input
          id="setting-terms-from"
          data-testid="setting-terms-from"
          type="date"
          [value]="froms()['terms'] ?? ''"
          (input)="setFrom('terms', value($event))"
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
          <label [for]="'setting-terms-' + g + '-from'">From (blank: the sheet's today)</label>
          <input
            [id]="'setting-terms-' + g + '-from'"
            [attr.data-testid]="'setting-terms-' + g + '-from'"
            type="date"
            [value]="froms()['terms-' + g] ?? ''"
            (input)="setFrom('terms-' + g, value($event))"
          />
        }
        <label for="setting-late-pct">Late percent (of an overdue claim's due, every full 30 days)</label>
        <input
          id="setting-late-pct"
          data-testid="setting-late-pct"
          inputmode="decimal"
          [value]="latePct()"
          (focus)="lastFocused = 'latePct'"
          (input)="latePct.set(value($event)); latePctEdited = true; settingsError.set(null)"
        />
        <label for="setting-late-pct-from">From (blank: the sheet's today)</label>
        <input
          id="setting-late-pct-from"
          data-testid="setting-late-pct-from"
          type="date"
          [value]="froms()['late-pct'] ?? ''"
          (input)="setFrom('late-pct', value($event))"
        />
        <label for="setting-policy-date">Policy date (claims before it round half up, others half to even; blank: today)</label>
        <input
          id="setting-policy-date"
          data-testid="setting-policy-date"
          type="date"
          [value]="policyDate()"
          (focus)="lastFocused = 'policyDate'"
          (input)="policyDate.set(value($event)); policyDateEdited = true; settingsError.set(null)"
        />
        <label for="setting-period-numbering-from">Period numbering from (claims opened from then are numbered within their period; blank: off)</label>
        <input
          id="setting-period-numbering-from"
          data-testid="setting-period-numbering-from"
          type="date"
          [value]="periodNumberingFrom()"
          (focus)="lastFocused = 'periodNumberingFrom'"
          (input)="periodNumberingFrom.set(value($event)); periodNumberingFromEdited = true; settingsError.set(null)"
        />
        <label for="setting-entry-date">Entry date (the default date of a new claim; blank: off, the sheet's today)</label>
        <input
          id="setting-entry-date"
          data-testid="setting-entry-date"
          type="date"
          [value]="entryDate()"
          (focus)="lastFocused = 'entryDate'"
          (input)="entryDate.set(value($event)); entryDateEdited = true; settingsError.set(null)"
        />
        <label for="setting-grace-days">Grace days (an open period closes once today is past its end by more; blank: off)</label>
        <input
          id="setting-grace-days"
          data-testid="setting-grace-days"
          inputmode="numeric"
          [value]="graceDays()"
          (focus)="lastFocused = 'graceDays'"
          (input)="graceDays.set(value($event)); graceDaysEdited = true; settingsError.set(null)"
        />
        <label for="setting-cap">Cap (the most a person's due-total can be; blank: no cap)</label>
        <input
          id="setting-cap"
          data-testid="setting-cap"
          inputmode="decimal"
          [value]="cap()"
          (focus)="lastFocused = 'cap'"
          (input)="cap.set(value($event)); capEdited = true; settingsError.set(null)"
        />
        <label for="setting-cap-from">From (blank: the sheet's today)</label>
        <input
          id="setting-cap-from"
          data-testid="setting-cap-from"
          type="date"
          [value]="froms()['cap'] ?? ''"
          (input)="setFrom('cap', value($event))"
        />
        <label for="setting-proof-threshold">Proof threshold (a claim due above it needs a proof to settle; blank: none)</label>
        <input
          id="setting-proof-threshold"
          data-testid="setting-proof-threshold"
          inputmode="decimal"
          [value]="proofThreshold()"
          (focus)="lastFocused = 'proofThreshold'"
          (input)="proofThreshold.set(value($event)); proofThresholdEdited = true; settingsError.set(null)"
        />
        <label for="setting-proof-threshold-from">From (blank: the sheet's today)</label>
        <input
          id="setting-proof-threshold-from"
          data-testid="setting-proof-threshold-from"
          type="date"
          [value]="froms()['proof-threshold'] ?? ''"
          (input)="setFrom('proof-threshold', value($event))"
        />
        <label for="setting-note-threshold">Note threshold (a claim due above it needs a note to open; blank: none)</label>
        <input
          id="setting-note-threshold"
          data-testid="setting-note-threshold"
          inputmode="decimal"
          [value]="noteThreshold()"
          (focus)="lastFocused = 'noteThreshold'"
          (input)="noteThreshold.set(value($event)); noteThresholdEdited = true; settingsError.set(null)"
        />
        <label for="setting-note-threshold-from">From (blank: the sheet's today)</label>
        <input
          id="setting-note-threshold-from"
          data-testid="setting-note-threshold-from"
          type="date"
          [value]="froms()['note-threshold'] ?? ''"
          (input)="setFrom('note-threshold', value($event))"
        />
        <fieldset>
          <legend>Permissions (roles that may take each action, comma separated; blank: the standing rule)</legend>
          @for (a of actions; track a) {
            <label [for]="'setting-perm-' + a">Who may {{ a }} (clerk, steward)</label>
            <input
              [id]="'setting-perm-' + a"
              [attr.data-testid]="'setting-perm-' + a"
              [value]="perms()[a]"
              (focus)="focusPerm(a)"
              (input)="setPerm(a, value($event)); settingsError.set(null)"
            />
          }
        </fieldset>
        <button type="submit" data-testid="settings-save">Save settings</button>
        @if (settingsError(); as message) {
          <p role="alert" data-testid="settings-error">{{ message }}</p>
        }
      </form>
      <p>Clerk limit in force: <strong data-testid="setting-clerk-limit-current">{{ session.settingOn('clerk-limit', session.today()) ?? '' }}</strong></p>
      <p>Floor in force: <strong data-testid="setting-floor-current">{{ session.floor() ?? '' }}</strong></p>
      <p>Terms in force: <strong data-testid="setting-terms-current">{{ session.terms() ?? '' }}</strong></p>
      @for (g of grades; track g) {
        <p>Terms for grade {{ g }} in force: <strong [attr.data-testid]="'setting-terms-' + g + '-current'">{{ session.settingOn('terms-' + g, session.today()) ?? '' }}</strong></p>
      }
      <p>Late percent in force: <strong data-testid="setting-late-pct-current">{{ session.latePct() ?? '' }}</strong></p>
      <p>Policy date in force: <strong data-testid="setting-policy-date-current">{{ session.policyDate() ?? '' }}</strong></p>
      <p>Period numbering from: <strong data-testid="setting-period-numbering-from-current">{{ periodNumberingFromCurrent() ?? '' }}</strong></p>
      <p>Entry date: <strong data-testid="setting-entry-date-current">{{ session.entryDate() ?? '' }}</strong></p>
      <p>Grace days: <strong data-testid="setting-grace-days-current">{{ graceDaysCurrent() ?? '' }}</strong></p>
      <p>Cap in force: <strong data-testid="setting-cap-current">{{ session.cap() ?? '' }}</strong></p>
      @for (a of actions; track a) {
        <p>Who may {{ a }}: <strong [attr.data-testid]="'setting-perm-' + a + '-current'">{{ permsCurrent()[a] ?? '' }}</strong></p>
      }
      <p>Proof threshold in force: <strong data-testid="setting-proof-threshold-current">{{ session.settingOn('proof-threshold', session.today()) ?? '' }}</strong></p>
      <p>Note threshold in force: <strong data-testid="setting-note-threshold-current">{{ session.settingOn('note-threshold', session.today()) ?? '' }}</strong></p>
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
        <label for="role-form-role">Role (clerk, steward or viewer)</label>
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
      <h3 id="delegate-heading">Delegate the steward role</h3>
      <form (submit)="saveDelegation($event)" aria-labelledby="delegate-heading">
        <label for="delegate-user">User name</label>
        <input
          id="delegate-user"
          data-testid="delegate-user"
          [value]="delegateUser()"
          (input)="delegateUser.set(value($event)); delegateError.set(null)"
        />
        <label for="delegate-from">From (blank: today)</label>
        <input
          id="delegate-from"
          type="date"
          data-testid="delegate-from"
          [value]="delegateFrom()"
          (input)="delegateFrom.set(value($event)); delegateError.set(null)"
        />
        <label for="delegate-to">To</label>
        <input
          id="delegate-to"
          type="date"
          data-testid="delegate-to"
          [value]="delegateTo()"
          (input)="delegateTo.set(value($event)); delegateError.set(null)"
        />
        <button type="submit" data-testid="delegate-submit">Delegate</button>
        @if (delegateError(); as message) {
          <p role="alert" data-testid="delegate-error">{{ message }}</p>
        }
      </form>
      <ul aria-label="Delegations">
        @for (d of delegations(); track d.id) {
          <li data-testid="delegate-line">{{ d.user }} acts as steward for {{ d.by }} from {{ d.from }} to {{ d.to }}</li>
        }
      </ul>
    </section>
  `,
})
export class Settings {
  private readonly http = inject(HttpClient);
  protected readonly session = inject(Session);

  protected readonly roles = ROLES;
  protected readonly grades = GRADES;
  protected readonly actions = ACTIONS;
  /** Each action's roles as typed. */
  protected readonly perms = signal<Record<string, string>>({});
  /** Each action's roles as saved; null: the standing rule. */
  protected readonly permsCurrent = signal<Record<string, string | null>>({});
  /** The actions whose roles were typed in since the form was last saved or read. */
  protected permsEdited = new Set<string>();
  /** Each grade's terms as typed. */
  protected readonly gradeTerms = signal<Record<string, string>>({});
  /** The grades whose terms were typed in since the form was last saved or read. */
  protected gradeTermsEdited = new Set<string>();
  protected readonly clerkLimit = signal('');
  protected readonly floor = signal('');
  protected readonly terms = signal('');
  protected readonly latePct = signal('');
  protected readonly policyDate = signal('');
  protected readonly periodNumberingFrom = signal('');
  protected readonly periodNumberingFromCurrent = signal<string | null>(null);
  protected readonly entryDate = signal('');
  protected readonly graceDays = signal('');
  protected readonly graceDaysCurrent = signal<string | null>(null);
  protected readonly cap = signal('');
  protected readonly proofThreshold = signal('');
  protected readonly noteThreshold = signal('');
  /** The from-date typed for each dated setting, by key; blank: the sheet's today. */
  protected readonly froms = signal<Record<string, string>>({});
  /** Which settings were typed in since the form was last saved or read: a save sends only those. */
  protected clerkLimitEdited = false;
  protected floorEdited = false;
  protected termsEdited = false;
  protected latePctEdited = false;
  protected policyDateEdited = false;
  protected periodNumberingFromEdited = false;
  protected entryDateEdited = false;
  protected graceDaysEdited = false;
  protected capEdited = false;
  protected proofThresholdEdited = false;
  protected noteThresholdEdited = false;
  /** The setting whose field was focused last: a save with nothing typed saves it as it stands. */
  protected lastFocused: 'clerkLimit' | 'floor' | 'terms' | 'latePct' | 'policyDate' | 'periodNumberingFrom' | 'entryDate' | 'graceDays' | 'cap' | 'proofThreshold' | 'noteThreshold' | `perm-${string}` | null =
    null;
  protected readonly settingsError = signal<string | null>(null);
  protected readonly roleList = signal<RoleView[]>([]);
  protected readonly roleUser = signal('');
  protected readonly roleName = signal('');
  protected readonly roleError = signal<string | null>(null);
  protected readonly delegations = signal<DelegationView[]>([]);
  protected readonly delegateUser = signal('');
  protected readonly delegateFrom = signal('');
  protected readonly delegateTo = signal('');
  protected readonly delegateError = signal<string | null>(null);
  private savingDelegation = false;
  /** Whether a submit is waiting for its answer: a second submit meanwhile is ignored. */
  private savingSettings = false;
  private savingRole = false;
  private reads = 0;

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loaded();
      untracked(() => {
        this.session.dated.set(null);
        this.session.policyDate.set(null);
        this.periodNumberingFromCurrent.set(null);
        this.session.entryDate.set(null);
        this.graceDaysCurrent.set(null);
        this.permsCurrent.set({});
        this.roleList.set([]);
        this.settingsError.set(null);
        this.roleError.set(null);
        this.delegations.set([]);
        this.delegateError.set(null);
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

  protected focusPerm(action: string): void {
    this.lastFocused = `perm-${action}`;
  }

  protected setPerm(action: string, roles: string): void {
    this.perms.update((all) => ({ ...all, [action]: roles }));
    this.permsEdited.add(action);
  }

  /** A from-date typed for a dated setting: a save then saves that setting from it. */
  protected setFrom(key: string, from: string): void {
    this.froms.update((all) => ({ ...all, [key]: from }));
    switch (key) {
      case 'clerk-limit': this.clerkLimitEdited = true; break;
      case 'floor': this.floorEdited = true; break;
      case 'terms': this.termsEdited = true; break;
      case 'late-pct': this.latePctEdited = true; break;
      case 'cap': this.capEdited = true; break;
      case 'proof-threshold': this.proofThresholdEdited = true; break;
      case 'note-threshold': this.noteThresholdEdited = true; break;
      default: this.gradeTermsEdited.add(key.slice('terms-'.length));
    }
    this.settingsError.set(null);
  }

  protected async saveSettings(event: Event): Promise<void> {
    event.preventDefault();
    if (this.savingSettings) return;
    this.settingsError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    // Saving with nothing typed saves the setting last focused as it stands (clearing an already blank field types
    // nothing), else the clerk limit.
    const nothingTyped = !this.clerkLimitEdited && !this.floorEdited && !this.termsEdited && this.gradeTermsEdited.size === 0 && !this.latePctEdited && !this.policyDateEdited && !this.periodNumberingFromEdited && !this.entryDateEdited && !this.graceDaysEdited && !this.capEdited && !this.proofThresholdEdited && !this.noteThresholdEdited && this.permsEdited.size === 0;
    if (nothingTyped) {
      switch (this.lastFocused) {
        case 'clerkLimit': this.clerkLimitEdited = true; break;
        case 'floor': this.floorEdited = true; break;
        case 'terms': this.termsEdited = true; break;
        case 'latePct': this.latePctEdited = true; break;
        case 'policyDate': this.policyDateEdited = true; break;
        case 'periodNumberingFrom': this.periodNumberingFromEdited = true; break;
        case 'entryDate': this.entryDateEdited = true; break;
        case 'graceDays': this.graceDaysEdited = true; break;
        case 'cap': this.capEdited = true; break;
        case 'proofThreshold': this.proofThresholdEdited = true; break;
        case 'noteThreshold': this.noteThresholdEdited = true; break;
        default: if (this.lastFocused) this.permsEdited.add(this.lastFocused.slice('perm-'.length));
      }
    }
    const saveLimit = this.clerkLimitEdited || (nothingTyped && !this.lastFocused);
    const saveFloor = this.floorEdited;
    const saveTerms = this.termsEdited;
    const saveGradeTerms = GRADES.filter((g) => this.gradeTermsEdited.has(g));
    const saveLatePct = this.latePctEdited;
    const savePolicyDate = this.policyDateEdited;
    const savePeriodNumberingFrom = this.periodNumberingFromEdited;
    const saveEntryDate = this.entryDateEdited;
    const saveGraceDays = this.graceDaysEdited;
    const saveCap = this.capEdited;
    const saveProofThreshold = this.proofThresholdEdited;
    const saveNoteThreshold = this.noteThresholdEdited;
    const savePerms = ACTIONS.filter((a) => this.permsEdited.has(a));
    const limit = this.clerkLimit().trim();
    const floor = this.floor().trim();
    const terms = this.terms().trim();
    const latePct = this.latePct().trim();
    const policyDate = this.policyDate().trim();
    const periodNumberingFrom = this.periodNumberingFrom().trim();
    const entryDate = this.entryDate().trim();
    const graceDays = this.graceDays().trim();
    const cap = this.cap().trim();
    const proofThreshold = this.proofThreshold().trim();
    const noteThreshold = this.noteThreshold().trim();
    const froms = this.froms();
    const fromOf = (key: string): string | null => (froms[key] ?? '').trim() || null;
    const saving = [
      ...(saveLimit ? ['clerk-limit'] : []),
      ...(saveFloor ? ['floor'] : []),
      ...(saveTerms ? ['terms'] : []),
      ...saveGradeTerms.map((g) => 'terms-' + g),
      ...(saveLatePct ? ['late-pct'] : []),
      ...(saveCap ? ['cap'] : []),
      ...(saveProofThreshold ? ['proof-threshold'] : []),
      ...(saveNoteThreshold ? ['note-threshold'] : []),
    ];
    if (saving.some((key) => { const from = fromOf(key); return from !== null && !DATE.test(from); })) {
      this.settingsError.set('Type a from-date as yyyy-mm-dd, or leave it blank for the sheet\'s today.');
      return;
    }
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
    if (saveEntryDate && entryDate !== '' && !DATE.test(entryDate)) {
      this.settingsError.set('Type an entry date as yyyy-mm-dd, or leave it blank for off.');
      return;
    }
    if (saveGraceDays && graceDays !== '' && !/^\d{1,5}$/.test(graceDays)) {
      this.settingsError.set('Type grace days of 0 or more whole days, or leave it blank for off.');
      return;
    }
    if (saveCap && cap !== '' && !/^\d{1,10}(\.\d{1,2})?$/.test(cap)) {
      this.settingsError.set('Type a cap of 0.00 or more with at most two decimals, or leave it blank for no cap.');
      return;
    }
    if (saveProofThreshold && proofThreshold !== '' && !/^\d{1,10}(\.\d{1,2})?$/.test(proofThreshold)) {
      this.settingsError.set('Type a proof threshold of 0.00 or more with at most two decimals, or leave it blank for none.');
      return;
    }
    if (saveNoteThreshold && noteThreshold !== '' && !/^\d{1,10}(\.\d{1,2})?$/.test(noteThreshold)) {
      this.settingsError.set('Type a note threshold of 0.00 or more with at most two decimals, or leave it blank for none.');
      return;
    }
    if (savePerms.some((a) => !PERM.test((this.perms()[a] ?? '').trim()))) {
      this.settingsError.set('Type the roles allowed as clerk and/or steward, comma separated, or leave it blank for the standing rule.');
      return;
    }
    this.savingSettings = true;
    const url = `/api/sheets/${encodeURIComponent(sheet)}/settings`;
    try {
      let saved: SettingsView | null = null;
      if (saveLimit) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(url, { clerkLimit: limit === '' ? null : limit, from: fromOf('clerk-limit') }, this.session.headers()),
        );
        this.clerkLimitEdited = false;
      }
      if (saveFloor) {
        saved = await firstValueFrom(this.http.put<SettingsView>(`${url}/floor`, { floor, from: fromOf('floor') }, this.session.headers()));
        this.floorEdited = false;
      }
      if (saveTerms) {
        saved = await firstValueFrom(this.http.put<SettingsView>(`${url}/terms`, { terms, from: fromOf('terms') }, this.session.headers()));
        this.termsEdited = false;
      }
      for (const g of saveGradeTerms) {
        const gradeTerms = (this.gradeTerms()[g] ?? '').trim();
        saved = await firstValueFrom(
          this.http.put<SettingsView>(`${url}/terms/${g}`, { terms: gradeTerms, from: fromOf('terms-' + g) }, this.session.headers()),
        );
        this.gradeTermsEdited.delete(g);
      }
      if (saveLatePct) {
        saved = await firstValueFrom(this.http.put<SettingsView>(`${url}/late-pct`, { latePct, from: fromOf('late-pct') }, this.session.headers()));
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
      if (saveEntryDate) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(`${url}/entry-date`, { entryDate: entryDate === '' ? null : entryDate }, this.session.headers()),
        );
        this.entryDateEdited = false;
      }
      if (saveGraceDays) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(`${url}/grace-days`, { graceDays: graceDays === '' ? null : graceDays }, this.session.headers()),
        );
        this.graceDaysEdited = false;
      }
      if (saveCap) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(`${url}/cap`, { cap: cap === '' ? null : cap, from: fromOf('cap') }, this.session.headers()),
        );
        this.capEdited = false;
      }
      if (saveProofThreshold) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(
            `${url}/proof-threshold`,
            { proofThreshold: proofThreshold === '' ? null : proofThreshold, from: fromOf('proof-threshold') },
            this.session.headers(),
          ),
        );
        this.proofThresholdEdited = false;
      }
      if (saveNoteThreshold) {
        saved = await firstValueFrom(
          this.http.put<SettingsView>(
            `${url}/note-threshold`,
            { noteThreshold: noteThreshold === '' ? null : noteThreshold, from: fromOf('note-threshold') },
            this.session.headers(),
          ),
        );
        this.noteThresholdEdited = false;
      }
      for (const a of savePerms) {
        const roles = (this.perms()[a] ?? '').trim();
        saved = await firstValueFrom(
          this.http.put<SettingsView>(`${url}/perm/${a}`, { roles: roles === '' ? null : roles }, this.session.headers()),
        );
        this.permsEdited.delete(a);
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
      this.roleError.set('A role is clerk, steward or viewer.');
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

  protected async saveDelegation(event: Event): Promise<void> {
    event.preventDefault();
    if (this.savingDelegation) return;
    this.delegateError.set(null);
    const sheet = this.session.sheet();
    if (!sheet) return;
    const user = this.delegateUser().trim();
    const from = this.delegateFrom().trim();
    const to = this.delegateTo().trim();
    if (!user) {
      this.delegateError.set('Type the user name to delegate to.');
      return;
    }
    if (!to) {
      this.delegateError.set('Type the date the delegation ends.');
      return;
    }
    if (to < (from || this.session.today() || '')) {
      this.delegateError.set('The to-date cannot be before the from-date.');
      return;
    }
    this.savingDelegation = true;
    try {
      const saved = await firstValueFrom(
        this.http.post<DelegationView>(
          `/api/sheets/${encodeURIComponent(sheet)}/delegations`,
          { user, from, to },
          this.session.headers(),
        ),
      );
      if (this.session.sheet() === sheet) {
        this.delegations.update((list) => [...list, saved]);
        this.delegateUser.set('');
        this.delegateFrom.set('');
        this.delegateTo.set('');
      }
    } catch {
      this.delegateError.set('The steward role could not be delegated: only a steward may delegate it.');
    } finally {
      this.savingDelegation = false;
    }
  }

  private async read(sheet: string): Promise<void> {
    const read = ++this.reads;
    const url = `/api/sheets/${encodeURIComponent(sheet)}`;
    const [settings, roles, delegations] = await Promise.all([
      firstValueFrom(this.http.get<SettingsView>(`${url}/settings`, this.session.headers())),
      firstValueFrom(this.http.get<RoleView[]>(`${url}/roles`, this.session.headers())),
      firstValueFrom(this.http.get<DelegationView[]>(`${url}/delegations`, this.session.headers())),
    ]).catch(() => [null, null, null]);
    // A later read wins over a slower earlier one.
    if (settings && roles && delegations && read === this.reads && this.session.sheet() === sheet) {
      this.show(settings);
      this.roleList.set(roles);
      this.delegations.set(delegations);
    }
  }

  /** Show the settings in force on the sheet's today, and put them back in the form with no from-dates. */
  private show(settings: SettingsView): void {
    this.session.dated.set(settings.dated);
    this.clerkLimit.set(settings.clerkLimit ?? '');
    this.floor.set(settings.floor);
    this.terms.set(String(settings.terms));
    const gradeTerms: Record<string, string> = {};
    for (const [g, t] of Object.entries(settings.gradeTerms)) gradeTerms[g] = String(t);
    this.gradeTerms.set(gradeTerms);
    this.latePct.set(settings.latePct);
    this.froms.set({});
    this.session.policyDate.set(settings.policyDate ?? '');
    this.policyDate.set(settings.policyDate ?? '');
    this.periodNumberingFromCurrent.set(settings.periodNumberingFrom ?? '');
    this.periodNumberingFrom.set(settings.periodNumberingFrom ?? '');
    this.session.entryDate.set(settings.entryDate ?? '');
    this.entryDate.set(settings.entryDate ?? '');
    this.graceDaysCurrent.set(settings.graceDays === null ? '' : String(settings.graceDays));
    this.graceDays.set(settings.graceDays === null ? '' : String(settings.graceDays));
    this.cap.set(settings.cap ?? '');
    this.proofThreshold.set(settings.proofThreshold ?? '');
    this.noteThreshold.set(settings.noteThreshold ?? '');
    this.permsCurrent.set(settings.permissions);
    const perms: Record<string, string> = {};
    for (const a of ACTIONS) perms[a] = settings.permissions[a] ?? '';
    this.perms.set(perms);
    this.permsEdited.clear();
    this.clerkLimitEdited = false;
    this.floorEdited = false;
    this.termsEdited = false;
    this.gradeTermsEdited.clear();
    this.latePctEdited = false;
    this.policyDateEdited = false;
    this.periodNumberingFromEdited = false;
    this.entryDateEdited = false;
    this.graceDaysEdited = false;
    this.capEdited = false;
    this.proofThresholdEdited = false;
    this.noteThresholdEdited = false;
  }
}
