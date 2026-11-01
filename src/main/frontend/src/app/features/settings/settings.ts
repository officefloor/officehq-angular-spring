import { HttpClient } from '@angular/common/http';
import { Component, computed, effect, inject, signal } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';
import { Dated, Session } from '../../core/session';

/** A role a user name can be given; any name not given one is a clerk. A viewer can change nothing. */
export const ROLES = ['clerk', 'steward', 'viewer'];

/** The actions of the permission matrix, each with the roles that may take it while none are set (its standing rule). */
export const PERMISSIONS = [
  { action: 'open', standing: 'clerk,steward' },
  { action: 'settle', standing: 'clerk,steward' },
  { action: 'void', standing: 'clerk,steward' },
  { action: 'reassign', standing: 'steward' },
  { action: 'close', standing: 'clerk,steward' },
  { action: 'reopen', standing: 'steward' },
] as const;
type PermAction = (typeof PERMISSIONS)[number]['action'];

/** The grades a person can have; each can have its own terms. */
export const GRADES = ['A', 'B', 'C'] as const;
type Grade = (typeof GRADES)[number];

/** A real calendar date written yyyy-mm-dd. */
function isDate(value: string): boolean {
  const at = Date.parse(`${value}T00:00:00Z`);
  return /^\d{4}-\d{2}-\d{2}$/.test(value) && !Number.isNaN(at) && new Date(at).toISOString().slice(0, 10) === value;
}

interface RoleView {
  user: string;
  role: string;
}

/** A steward's role delegated to a user, by the steward, from and to a date, inclusive. */
interface DelegationView {
  id: number;
  user: string;
  by: string;
  from: string;
  to: string;
}

interface SettingsView {
  /** The highest due a clerk can settle, two decimals; null when there is no limit. */
  clerkLimit: string | null;
  /** The least a claim's live due can be, two decimals. */
  floor: string;
  /** The business days after its date by which a claim is due, for a grade without its own. */
  terms: number;
  /** The terms set for each grade that has its own, by grade. */
  gradeTerms: Partial<Record<Grade, number>>;
  /** The percent an overdue claim's due gains of its figure for every full 30 days overdue, two decimals. */
  latePct: string;
  /** Before it a claim's live figures round half up, else half to even (yyyy-mm-dd); null when blank: the sheet's today. */
  policyDate: string | null;
  /** From it (yyyy-mm-dd), a claim opened is numbered within its period; null when blank: off. */
  periodNumberingFrom: string | null;
  /** The most a person's due-total can be, two decimals; null when blank: none. */
  cap: string | null;
  roles: RoleView[];
  /** The values recorded for each dated setting, by key; those above are the ones in force on the sheet's today. */
  dated: Dated;
  /** The roles set for each action of the permission matrix, comma separated; an action not in it keeps its standing rule. */
  permissions: Partial<Record<PermAction, string>>;
  /** Past them, an open period closes when the sheet's today is changed; null when blank: off. */
  graceDays: number | null;
  /** While set (yyyy-mm-dd), a new claim given no date takes it rather than the sheet's today; null when blank: off. */
  entryDate: string | null;
}

/** A from-date field (yyyy-mm-dd) for a dated setting; blank: the sheet's today. */
const FROM = 'From (yyyy-mm-dd; blank: the sheet\'s today)';

// The sheet's settings: the clerk limit (blank: no limit), the floor of a claim's live due, the terms
// (the business days after its date by which a claim is due, fixed when it is added), the terms of each grade (overriding the
// sheet's for a person of that grade on the claim's date), the late percent (charged on an overdue claim's
// figure for every full 30 days overdue, the one in force on each 30-day boundary), the policy date (blank: the sheet's today; a claim dated before it
// rounds half up, else half to even), the period numbering from (blank: off; while the sheet's today is on or after it,
// a claim opened is numbered within its period), the entry date (blank: off; not dated; while set, a new claim given no date,
// from the claim form, the quick-add bar, a paste or a correction, takes it rather than the sheet's today), the cap (blank: none; a person's due-total is at most it), the grace days (blank: off; not dated; when the
// sheet's today is changed, an open period whose to-date plus them is before it is closed), and the role given to each user name. Saving stores the settings whose
// field was changed since the last save; clearing the clerk limit, the policy date, the period numbering from, the cap, the proof threshold or the note threshold (Delete or
// Backspace) counts as a change even when it was already blank. The clerk limit, the floor, the terms, each grade's terms, the late
// percent, the cap, the proof threshold and the note threshold (blank: none; a claim with an empty note whose due is above the one in force on its date
// cannot be opened) are dated: a save records the value from its from-date (blank: the sheet's today), keeping those recorded from
// other dates; a claim takes the floor and terms in force on its date, the late percent in force on each 30-day boundary, and the clerk limit and cap are those in force on
// the sheet's today. Each shows the value in force on the sheet's today. The permission matrix (not dated) gives, for each
// of open, settle, void, reassign, close and reopen, the roles that may take it, comma separated; blank keeps its standing rule.
@Component({
  selector: 'app-settings',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="settings-heading">
      <h2 id="settings-heading">Settings</h2>
      <form [formGroup]="form" (ngSubmit)="save()" aria-label="Settings">
        <label for="setting-clerk-limit">Clerk limit (blank: no limit)</label>
        <input id="setting-clerk-limit" data-testid="setting-clerk-limit" formControlName="clerkLimit" inputmode="decimal"
               (keydown.delete)="form.controls.clerkLimit.markAsDirty()" (keydown.backspace)="form.controls.clerkLimit.markAsDirty()" />
        <label for="setting-clerk-limit-from">Clerk limit {{ from }}</label>
        <input id="setting-clerk-limit-from" data-testid="setting-clerk-limit-from" formControlName="clerkLimitFrom" placeholder="yyyy-mm-dd" />
        <label for="setting-floor">Floor (least live due)</label>
        <input id="setting-floor" data-testid="setting-floor" formControlName="floor" inputmode="decimal" />
        <label for="setting-floor-from">Floor {{ from }}</label>
        <input id="setting-floor-from" data-testid="setting-floor-from" formControlName="floorFrom" placeholder="yyyy-mm-dd" />
        <label for="setting-terms">Terms (business days)</label>
        <input id="setting-terms" data-testid="setting-terms" formControlName="terms" inputmode="numeric" />
        <label for="setting-terms-from">Terms {{ from }}</label>
        <input id="setting-terms-from" data-testid="setting-terms-from" formControlName="termsFrom" placeholder="yyyy-mm-dd" />
        @for (g of grades; track g) {
          <label [for]="'setting-terms-' + g">Terms for grade {{ g }} (business days)</label>
          <input [id]="'setting-terms-' + g" [attr.data-testid]="'setting-terms-' + g" [formControl]="gradeTermsForm.controls[g]"
                 inputmode="numeric" />
          <label [for]="'setting-terms-' + g + '-from'">Terms for grade {{ g }} {{ from }}</label>
          <input [id]="'setting-terms-' + g + '-from'" [attr.data-testid]="'setting-terms-' + g + '-from'"
                 [formControl]="gradeTermsFromForm.controls[g]" placeholder="yyyy-mm-dd" />
        }
        <label for="setting-late-pct">Late percent (per full 30 days overdue)</label>
        <input id="setting-late-pct" data-testid="setting-late-pct" formControlName="latePct" inputmode="decimal" />
        <label for="setting-late-pct-from">Late percent {{ from }}</label>
        <input id="setting-late-pct-from" data-testid="setting-late-pct-from" formControlName="latePctFrom" placeholder="yyyy-mm-dd" />
        <label for="setting-policy-date">Policy date (blank: the sheet's today; before it rounds half up)</label>
        <input id="setting-policy-date" data-testid="setting-policy-date" formControlName="policyDate" placeholder="yyyy-mm-dd"
               (keydown.delete)="form.controls.policyDate.markAsDirty()" (keydown.backspace)="form.controls.policyDate.markAsDirty()" />
        <label for="setting-period-numbering-from">Period numbering from (blank: off)</label>
        <input id="setting-period-numbering-from" data-testid="setting-period-numbering-from" formControlName="periodNumberingFrom"
               placeholder="yyyy-mm-dd"
               (keydown.delete)="form.controls.periodNumberingFrom.markAsDirty()"
               (keydown.backspace)="form.controls.periodNumberingFrom.markAsDirty()" />
        <label for="setting-grace-days">Grace days (a period closes when today passes its to-date by more; blank: off)</label>
        <input id="setting-grace-days" data-testid="setting-grace-days" formControlName="graceDays" inputmode="numeric"
               (keydown.delete)="form.controls.graceDays.markAsDirty()"
               (keydown.backspace)="form.controls.graceDays.markAsDirty()" />
        <label for="setting-entry-date">Entry date (a new claim's date when none is given; blank: off)</label>
        <input id="setting-entry-date" data-testid="setting-entry-date" formControlName="entryDate" placeholder="yyyy-mm-dd"
               (keydown.delete)="form.controls.entryDate.markAsDirty()"
               (keydown.backspace)="form.controls.entryDate.markAsDirty()" />
        <label for="setting-cap">Cap (most a person's due-total can be; blank: none)</label>
        <input id="setting-cap" data-testid="setting-cap" formControlName="cap" inputmode="decimal"
               (keydown.delete)="form.controls.cap.markAsDirty()" (keydown.backspace)="form.controls.cap.markAsDirty()" />
        <label for="setting-cap-from">Cap {{ from }}</label>
        <input id="setting-cap-from" data-testid="setting-cap-from" formControlName="capFrom" placeholder="yyyy-mm-dd" />
        <label for="setting-proof-threshold">Proof threshold (a claim due above it needs a proof to settle; blank: none)</label>
        <input id="setting-proof-threshold" data-testid="setting-proof-threshold" formControlName="proofThreshold" inputmode="decimal"
               (keydown.delete)="form.controls.proofThreshold.markAsDirty()"
               (keydown.backspace)="form.controls.proofThreshold.markAsDirty()" />
        <label for="setting-proof-threshold-from">Proof threshold {{ from }}</label>
        <input id="setting-proof-threshold-from" data-testid="setting-proof-threshold-from" formControlName="proofThresholdFrom"
               placeholder="yyyy-mm-dd" />
        <label for="setting-note-threshold">Note threshold (a claim due above it needs a note to open; blank: none)</label>
        <input id="setting-note-threshold" data-testid="setting-note-threshold" formControlName="noteThreshold" inputmode="decimal"
               (keydown.delete)="form.controls.noteThreshold.markAsDirty()"
               (keydown.backspace)="form.controls.noteThreshold.markAsDirty()" />
        <label for="setting-note-threshold-from">Note threshold {{ from }}</label>
        <input id="setting-note-threshold-from" data-testid="setting-note-threshold-from" formControlName="noteThresholdFrom"
               placeholder="yyyy-mm-dd" />
        <fieldset>
          <legend>Permissions (roles, comma separated: clerk, steward; blank: the standing rule)</legend>
          @for (p of permissions; track p.action) {
            <label [for]="'setting-perm-' + p.action">Who may {{ p.action }} (blank: {{ p.standing }})</label>
            <input [id]="'setting-perm-' + p.action" [attr.data-testid]="'setting-perm-' + p.action"
                   [formControl]="permForm.controls[p.action]"
                   (keydown.delete)="permForm.controls[p.action].markAsDirty()"
                   (keydown.backspace)="permForm.controls[p.action].markAsDirty()" />
          }
        </fieldset>
        <button type="submit" data-testid="settings-save">Save settings</button>
        @if (error()) {
          <p role="alert" class="error" data-testid="settings-error">{{ error() }}</p>
        }
      </form>
      <p>Clerk limit now: <span data-testid="setting-clerk-limit-current">{{ session.clerkLimit() ?? '' }}</span></p>
      <p>Floor now: <span data-testid="setting-floor-current">{{ session.floor() }}</span></p>
      <p>Terms now: <span data-testid="setting-terms-current">{{ session.terms() }}</span></p>
      @for (g of grades; track g) {
        <p>Terms for grade {{ g }} now (blank: the sheet's terms):
          <span [attr.data-testid]="'setting-terms-' + g + '-current'">{{ gradeTerms()[g] ?? '' }}</span></p>
      }
      <p>Late percent now: <span data-testid="setting-late-pct-current">{{ session.latePct() }}</span></p>
      <p>Policy date now: <span data-testid="setting-policy-date-current">{{ session.policyDate() ?? '' }}</span></p>
      <p>Period numbering from now: <span data-testid="setting-period-numbering-from-current">{{ periodNumberingFrom() ?? '' }}</span></p>
      <p>Grace days now: <span data-testid="setting-grace-days-current">{{ graceDays() ?? '' }}</span></p>
      <p>Entry date now: <span data-testid="setting-entry-date-current">{{ entryDate() ?? '' }}</span></p>
      <p>Cap now: <span data-testid="setting-cap-current">{{ session.cap() ?? '' }}</span></p>
      <p>Proof threshold now: <span data-testid="setting-proof-threshold-current">{{ session.proofThreshold() ?? '' }}</span></p>
      <p>Note threshold now: <span data-testid="setting-note-threshold-current">{{ session.noteThreshold() ?? '' }}</span></p>
      @for (p of permissions; track p.action) {
        <p>Who may {{ p.action }} now (blank: {{ p.standing }}):
          <span [attr.data-testid]="'setting-perm-' + p.action + '-current'">{{ permissionsSet()[p.action] ?? '' }}</span></p>
      }
      <form [formGroup]="roleForm" (ngSubmit)="setRole()" aria-label="Set role">
        <label for="role-form-user">User</label>
        <input id="role-form-user" data-testid="role-form-user" formControlName="user" />
        <label for="role-form-role">Role (clerk, steward or viewer)</label>
        <input id="role-form-role" data-testid="role-form-role" formControlName="role" list="role-options" />
        <datalist id="role-options">
          @for (r of roles; track r) {
            <option [value]="r"></option>
          }
        </datalist>
        <button type="submit" data-testid="role-form-submit">Set role</button>
        @if (roleError()) {
          <p role="alert" class="error" data-testid="role-form-error">{{ roleError() }}</p>
        }
      </form>
      <table>
        <caption>Roles (anyone else is a clerk)</caption>
        <thead>
          <tr><th scope="col">User</th><th scope="col">Role</th></tr>
        </thead>
        <tbody>
          @for (r of userRoles(); track r.user) {
            <tr [attr.data-testid]="'role-row-' + r.user">
              <td data-testid="role-user">{{ r.user }}</td>
              <td data-testid="role-value">{{ r.role }}</td>
            </tr>
          }
        </tbody>
      </table>
      <form [formGroup]="delegateForm" (ngSubmit)="delegate()" aria-label="Delegate the steward role">
        <label for="delegate-user">Delegate the steward role to</label>
        <input id="delegate-user" data-testid="delegate-user" formControlName="user" />
        <label for="delegate-from">From (blank: today)</label>
        <input id="delegate-from" data-testid="delegate-from" formControlName="from" placeholder="yyyy-mm-dd" />
        <label for="delegate-to">To</label>
        <input id="delegate-to" data-testid="delegate-to" formControlName="to" placeholder="yyyy-mm-dd" />
        <button type="submit" data-testid="delegate-submit">Delegate</button>
        @if (delegateError()) {
          <p role="alert" class="error" data-testid="delegate-error">{{ delegateError() }}</p>
        }
      </form>
      <ul aria-label="Delegations">
        @for (d of delegations(); track d.id) {
          <li data-testid="delegate-line">{{ d.by }} delegated the steward role to {{ d.user }} from {{ d.from }} to {{ d.to }}</li>
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
  protected readonly permissions = PERMISSIONS;
  /** The roles set for each action of the permission matrix; an action not in it keeps its standing rule. */
  protected readonly permissionsSet = signal<Partial<Record<PermAction, string>>>({});
  protected readonly from = FROM;
  /** The terms in force on the sheet's today for each grade that has its own then. */
  protected readonly gradeTerms = computed(() => {
    const today = this.session.today();
    const byGrade: Partial<Record<Grade, string>> = {};
    for (const g of GRADES) {
      const terms = this.session.valueOn(`terms-${g}`, today);
      if (terms !== null) byGrade[g] = terms;
    }
    return byGrade;
  });
  protected readonly periodNumberingFrom = signal<string | null>(null);
  protected readonly graceDays = signal<number | null>(null);
  protected readonly entryDate = signal<string | null>(null);
  protected readonly userRoles = signal<RoleView[]>([]);
  protected readonly error = signal('');
  protected readonly roleError = signal('');
  protected readonly delegations = signal<DelegationView[]>([]);
  protected readonly delegateError = signal('');
  /** The forms with a submit under way; another submit of one waits for its answer. */
  private readonly submitting = new Set<string>();
  protected readonly form = new FormGroup({
    clerkLimit: new FormControl('', { nonNullable: true }),
    floor: new FormControl('', { nonNullable: true }),
    terms: new FormControl('', { nonNullable: true }),
    latePct: new FormControl('', { nonNullable: true }),
    policyDate: new FormControl('', { nonNullable: true }),
    periodNumberingFrom: new FormControl('', { nonNullable: true }),
    graceDays: new FormControl('', { nonNullable: true }),
    entryDate: new FormControl('', { nonNullable: true }),
    cap: new FormControl('', { nonNullable: true }),
    clerkLimitFrom: new FormControl('', { nonNullable: true }),
    floorFrom: new FormControl('', { nonNullable: true }),
    termsFrom: new FormControl('', { nonNullable: true }),
    latePctFrom: new FormControl('', { nonNullable: true }),
    capFrom: new FormControl('', { nonNullable: true }),
    proofThreshold: new FormControl('', { nonNullable: true }),
    proofThresholdFrom: new FormControl('', { nonNullable: true }),
    noteThreshold: new FormControl('', { nonNullable: true }),
    noteThresholdFrom: new FormControl('', { nonNullable: true }),
  });
  protected readonly gradeTermsForm = new FormGroup({
    A: new FormControl('', { nonNullable: true }),
    B: new FormControl('', { nonNullable: true }),
    C: new FormControl('', { nonNullable: true }),
  });
  protected readonly gradeTermsFromForm = new FormGroup({
    A: new FormControl('', { nonNullable: true }),
    B: new FormControl('', { nonNullable: true }),
    C: new FormControl('', { nonNullable: true }),
  });
  protected readonly permForm = new FormGroup({
    open: new FormControl('', { nonNullable: true }),
    settle: new FormControl('', { nonNullable: true }),
    void: new FormControl('', { nonNullable: true }),
    reassign: new FormControl('', { nonNullable: true }),
    close: new FormControl('', { nonNullable: true }),
    reopen: new FormControl('', { nonNullable: true }),
  });
  protected readonly roleForm = new FormGroup({
    user: new FormControl('', { nonNullable: true }),
    role: new FormControl('', { nonNullable: true }),
  });
  protected readonly delegateForm = new FormGroup({
    user: new FormControl('', { nonNullable: true }),
    from: new FormControl('', { nonNullable: true }),
    to: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.form.valueChanges.subscribe(() => this.error.set(''));
    this.gradeTermsForm.valueChanges.subscribe(() => this.error.set(''));
    this.gradeTermsFromForm.valueChanges.subscribe(() => this.error.set(''));
    this.permForm.valueChanges.subscribe(() => this.error.set(''));
    this.roleForm.valueChanges.subscribe(() => this.roleError.set(''));
    this.delegateForm.valueChanges.subscribe(() => this.delegateError.set(''));
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      this.periodNumberingFrom.set(null);
      this.graceDays.set(null);
      this.entryDate.set(null);
      this.userRoles.set([]);
      this.permissionsSet.set({});
      this.error.set('');
      this.roleError.set('');
      this.delegations.set([]);
      this.delegateError.set('');
      if (id) void this.load(id);
    });
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }

  private base(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}`;
  }

  private async load(sheet: string): Promise<void> {
    try {
      const view = await firstValueFrom(this.http.get<SettingsView>(`${this.base(sheet)}/settings`, { headers: this.headers() }));
      if (this.session.sheetId() !== sheet) return;
      this.show(view);
      const delegations = await firstValueFrom(
        this.http.get<DelegationView[]>(`${this.base(sheet)}/delegations`, { headers: this.headers() }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.delegations.set(delegations);
    } catch {
      if (this.session.sheetId() === sheet) this.error.set('Could not load the settings of this sheet.');
    }
  }

  private show(view: SettingsView): void {
    this.periodNumberingFrom.set(view.periodNumberingFrom);
    this.graceDays.set(view.graceDays);
    this.entryDate.set(view.entryDate);
    this.userRoles.set(view.roles);
    this.permissionsSet.set(view.permissions);
    this.session.dated.set(view.dated);
    this.session.policyDate.set(view.policyDate);
  }

  protected async save(): Promise<void> {
    if (this.submitting.has('save')) return;
    this.error.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const { clerkLimit, floor, terms, latePct, policyDate, periodNumberingFrom, graceDays, entryDate, cap, proofThreshold, noteThreshold } = this.form.controls;
    const { clerkLimitFrom, floorFrom, termsFrom, latePctFrom, capFrom, proofThresholdFrom, noteThresholdFrom } = this.form.getRawValue();
    const value = clerkLimit.value.trim();
    const floorValue = floor.value.trim();
    const termsValue = terms.value.trim();
    const latePctValue = latePct.value.trim();
    const policyDateValue = policyDate.value.trim();
    const periodNumberingFromValue = periodNumberingFrom.value.trim();
    const graceDaysValue = graceDays.value.trim();
    const entryDateValue = entryDate.value.trim();
    const capValue = cap.value.trim();
    const proofThresholdValue = proofThreshold.value.trim();
    const noteThresholdValue = noteThreshold.value.trim();
    // a dated setting saved with a from-date takes it; blank: the sheet's today
    const dated = (from: string): string | null => from.trim() || null;
    const froms = [
      clerkLimit.dirty ? clerkLimitFrom : '', floor.dirty ? floorFrom : '', terms.dirty ? termsFrom : '',
      latePct.dirty ? latePctFrom : '', cap.dirty ? capFrom : '', proofThreshold.dirty ? proofThresholdFrom : '',
      noteThreshold.dirty ? noteThresholdFrom : '',
      ...GRADES.filter((g) => this.gradeTermsForm.controls[g].dirty).map((g) => this.gradeTermsFromForm.controls[g].value),
    ];
    if (froms.some((from) => from.trim() && !isDate(from.trim()))) {
      this.error.set('Enter a from-date as yyyy-mm-dd, or leave it blank for the sheet\'s today.');
      return;
    }
    if (clerkLimit.dirty && value && !/^\d{1,12}(\.\d{1,2})?$/.test(value)) {
      this.error.set('Enter a clerk limit of 0.00 or more, with at most two decimals, or leave it blank for none.');
      return;
    }
    if (floor.dirty && !/^\d{1,12}(\.\d{1,2})?$/.test(floorValue)) {
      this.error.set('Enter a floor of 0.00 or more, with at most two decimals.');
      return;
    }
    if (terms.dirty && !/^\d{1,4}$/.test(termsValue)) {
      this.error.set('Enter terms as a whole number of days, 0 or more.');
      return;
    }
    const gradeTermsChanged = GRADES.filter((g) => this.gradeTermsForm.controls[g].dirty);
    if (gradeTermsChanged.some((g) => !/^\d{1,4}$/.test(this.gradeTermsForm.controls[g].value.trim()))) {
      this.error.set('Enter a grade\'s terms as a whole number of days, 0 or more.');
      return;
    }
    if (latePct.dirty && !/^\d{1,5}(\.\d{1,2})?$/.test(latePctValue)) {
      this.error.set('Enter a late percent of 0.00 or more, with at most two decimals.');
      return;
    }
    if (policyDate.dirty && policyDateValue && !isDate(policyDateValue)) {
      this.error.set('Enter a policy date as yyyy-mm-dd, or leave it blank for the sheet\'s today.');
      return;
    }
    if (periodNumberingFrom.dirty && periodNumberingFromValue && !isDate(periodNumberingFromValue)) {
      this.error.set('Enter a period numbering from date as yyyy-mm-dd, or leave it blank for off.');
      return;
    }
    if (graceDays.dirty && graceDaysValue && !/^\d{1,4}$/.test(graceDaysValue)) {
      this.error.set('Enter grace days as a whole number of days, 0 or more, or leave it blank for off.');
      return;
    }
    if (entryDate.dirty && entryDateValue && !isDate(entryDateValue)) {
      this.error.set('Enter an entry date as yyyy-mm-dd, or leave it blank for off.');
      return;
    }
    if (cap.dirty && capValue && !/^\d{1,12}(\.\d{1,2})?$/.test(capValue)) {
      this.error.set('Enter a cap of 0.00 or more, with at most two decimals, or leave it blank for none.');
      return;
    }
    if (proofThreshold.dirty && proofThresholdValue && !/^\d{1,12}(\.\d{1,2})?$/.test(proofThresholdValue)) {
      this.error.set('Enter a proof threshold of 0.00 or more, with at most two decimals, or leave it blank for none.');
      return;
    }
    if (noteThreshold.dirty && noteThresholdValue && !/^\d{1,12}(\.\d{1,2})?$/.test(noteThresholdValue)) {
      this.error.set('Enter a note threshold of 0.00 or more, with at most two decimals, or leave it blank for none.');
      return;
    }
    const permsChanged = PERMISSIONS.map((p) => p.action).filter((a) => this.permForm.controls[a].dirty);
    const rolesList = /^\s*((clerk|steward)\s*(,\s*(clerk|steward)\s*)*)?$/i;
    if (permsChanged.some((a) => !rolesList.test(this.permForm.controls[a].value))) {
      this.error.set('Enter the roles that may take an action as clerk, steward or both, comma separated, or leave it blank for the standing rule.');
      return;
    }
    this.submitting.add('save');
    try {
      if (clerkLimit.dirty) {
        const view = await firstValueFrom(
          this.http.put<SettingsView>(`${this.base(sheet)}/settings/clerk-limit`, { value: value || null, from: dated(clerkLimitFrom) }, { headers: this.headers() }),
        );
        clerkLimit.markAsPristine();
        if (this.session.sheetId() === sheet) this.show(view);
      }
      if (floor.dirty) {
        const view = await firstValueFrom(
          this.http.put<SettingsView>(`${this.base(sheet)}/settings/floor`, { value: floorValue, from: dated(floorFrom) }, { headers: this.headers() }),
        );
        floor.markAsPristine();
        if (this.session.sheetId() === sheet) this.show(view);
      }
      if (terms.dirty) {
        const view = await firstValueFrom(
          this.http.put<SettingsView>(`${this.base(sheet)}/settings/terms`, { value: termsValue, from: dated(termsFrom) }, { headers: this.headers() }),
        );
        terms.markAsPristine();
        if (this.session.sheetId() === sheet) this.show(view);
      }
      for (const g of gradeTermsChanged) {
        const control = this.gradeTermsForm.controls[g];
        const view = await firstValueFrom(
          this.http.put<SettingsView>(`${this.base(sheet)}/settings/terms/${g}`, { value: control.value.trim(), from: dated(this.gradeTermsFromForm.controls[g].value) }, { headers: this.headers() }),
        );
        control.markAsPristine();
        if (this.session.sheetId() === sheet) this.show(view);
      }
      if (latePct.dirty) {
        const view = await firstValueFrom(
          this.http.put<SettingsView>(`${this.base(sheet)}/settings/late-pct`, { value: latePctValue, from: dated(latePctFrom) }, { headers: this.headers() }),
        );
        latePct.markAsPristine();
        if (this.session.sheetId() === sheet) this.show(view);
      }
      if (policyDate.dirty) {
        const view = await firstValueFrom(
          this.http.put<SettingsView>(`${this.base(sheet)}/settings/policy-date`, { value: policyDateValue || null }, { headers: this.headers() }),
        );
        policyDate.markAsPristine();
        if (this.session.sheetId() === sheet) this.show(view);
      }
      if (periodNumberingFrom.dirty) {
        const view = await firstValueFrom(
          this.http.put<SettingsView>(`${this.base(sheet)}/settings/period-numbering-from`, { value: periodNumberingFromValue || null },
            { headers: this.headers() }),
        );
        periodNumberingFrom.markAsPristine();
        if (this.session.sheetId() === sheet) this.show(view);
      }
      if (graceDays.dirty) {
        const view = await firstValueFrom(
          this.http.put<SettingsView>(`${this.base(sheet)}/settings/grace-days`, { value: graceDaysValue || null },
            { headers: this.headers() }),
        );
        graceDays.markAsPristine();
        if (this.session.sheetId() === sheet) this.show(view);
      }
      if (entryDate.dirty) {
        const view = await firstValueFrom(
          this.http.put<SettingsView>(`${this.base(sheet)}/settings/entry-date`, { value: entryDateValue || null },
            { headers: this.headers() }),
        );
        entryDate.markAsPristine();
        if (this.session.sheetId() === sheet) this.show(view);
      }
      if (cap.dirty) {
        const view = await firstValueFrom(
          this.http.put<SettingsView>(`${this.base(sheet)}/settings/cap`, { value: capValue || null, from: dated(capFrom) }, { headers: this.headers() }),
        );
        cap.markAsPristine();
        if (this.session.sheetId() === sheet) this.show(view);
      }
      if (proofThreshold.dirty) {
        const view = await firstValueFrom(
          this.http.put<SettingsView>(`${this.base(sheet)}/settings/proof-threshold`,
            { value: proofThresholdValue || null, from: dated(proofThresholdFrom) }, { headers: this.headers() }),
        );
        proofThreshold.markAsPristine();
        if (this.session.sheetId() === sheet) this.show(view);
      }
      if (noteThreshold.dirty) {
        const view = await firstValueFrom(
          this.http.put<SettingsView>(`${this.base(sheet)}/settings/note-threshold`,
            { value: noteThresholdValue || null, from: dated(noteThresholdFrom) }, { headers: this.headers() }),
        );
        noteThreshold.markAsPristine();
        if (this.session.sheetId() === sheet) this.show(view);
      }
      for (const a of permsChanged) {
        const control = this.permForm.controls[a];
        const view = await firstValueFrom(
          this.http.put<SettingsView>(`${this.base(sheet)}/settings/perm/${a}`, { value: control.value.trim() }, { headers: this.headers() }),
        );
        control.markAsPristine();
        if (this.session.sheetId() === sheet) this.show(view);
      }
    } catch {
      if (this.session.sheetId() === sheet) this.error.set('Could not save the settings.');
    } finally {
      this.submitting.delete('save');
    }
  }

  protected async setRole(): Promise<void> {
    if (this.submitting.has('role')) return;
    this.roleError.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const raw = this.roleForm.getRawValue();
    const user = raw.user.trim();
    const role = raw.role.trim().toLowerCase();
    if (!user) {
      this.roleError.set('Enter a user name.');
      return;
    }
    if (!ROLES.includes(role)) {
      this.roleError.set('Choose a role: clerk, steward or viewer.');
      return;
    }
    this.submitting.add('role');
    try {
      const saved = await firstValueFrom(
        this.http.put<RoleView>(`${this.base(sheet)}/roles/${encodeURIComponent(user)}`, { role }, { headers: this.headers() }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.userRoles.update((list) =>
        [...list.filter((r) => r.user !== saved.user), saved].sort((a, b) => (a.user < b.user ? -1 : 1)),
      );
    } catch {
      if (this.session.sheetId() === sheet) this.roleError.set('Could not set that role.');
    } finally {
      this.submitting.delete('role');
    }
  }

  /** A steward delegates the steward role to a user from the from-date (blank: today) to the to-date, inclusive. */
  protected async delegate(): Promise<void> {
    if (this.submitting.has('delegate')) return;
    this.delegateError.set('');
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const raw = this.delegateForm.getRawValue();
    const user = raw.user.trim();
    const from = raw.from.trim();
    const to = raw.to.trim();
    if (!user) {
      this.delegateError.set('Enter a user name.');
      return;
    }
    if ((from && !isDate(from)) || !isDate(to)) {
      this.delegateError.set('Enter the dates as yyyy-mm-dd; the to-date is needed.');
      return;
    }
    if (from && to < from) {
      this.delegateError.set('The to-date cannot be before the from-date.');
      return;
    }
    this.submitting.add('delegate');
    try {
      const saved = await firstValueFrom(
        this.http.post<DelegationView>(`${this.base(sheet)}/delegations`, { user, from, to }, { headers: this.headers() }),
      );
      if (this.session.sheetId() !== sheet) return;
      this.delegations.update((list) => [...list.filter((d) => d.id !== saved.id), saved]);
    } catch {
      if (this.session.sheetId() === sheet) this.delegateError.set('Could not delegate: only a steward can, for a to-date on or after the from-date.');
    } finally {
      this.submitting.delete('delegate');
    }
  }
}
