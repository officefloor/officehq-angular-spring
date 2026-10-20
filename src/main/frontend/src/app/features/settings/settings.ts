import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Subject, catchError, concatMap, forkJoin, map, of, switchMap } from 'rxjs';
import { Session } from '../../core/session';
import { DATED_KEYS, DatedKey, PERMISSIONS, Permission, ROLES, RoleGiven, Settings as SheetSettings, SettingsApi } from './settings-api';
import { valueOn } from './dated';
import { DEFAULT_FLOOR, SheetFloor } from './sheet-floor';
import { DEFAULT_LATE_PCT, SheetLatePct } from './sheet-late-pct';
import { SheetCap } from './sheet-cap';
import { SheetPolicyDate } from './sheet-policy-date';

/** The terms a sheet starts with, in days. */
const DEFAULT_TERMS = 14;
/** The grades that can have terms of their own. */
const GRADES = ['A', 'B', 'C'] as const;
type GradeTermsKey = `terms${(typeof GRADES)[number]}`;
/** The settings in the form that carry a figure, each saved from a from-date, by the key the sheet keeps them by. */
const DATED_FIELDS = {
  clerkLimit: 'clerk-limit',
  floor: 'floor',
  terms: 'terms',
  termsA: 'terms-A',
  termsB: 'terms-B',
  termsC: 'terms-C',
  latePct: 'late-pct',
  cap: 'cap',
  proofThreshold: 'proof-threshold',
} as const satisfies Record<string, DatedKey>;
type DatedField = keyof typeof DATED_FIELDS;
type FromField = `${DatedField}From`;
/** The setting holding each action's roles in the form. */
const PERMISSION_FIELDS = {
  open: 'permOpen',
  settle: 'permSettle',
  void: 'permVoid',
  reassign: 'permReassign',
  close: 'permClose',
  reopen: 'permReopen',
} as const satisfies Record<Permission, string>;
type PermissionField = (typeof PERMISSION_FIELDS)[Permission];
const LIMIT = /^\d{1,10}(\.\d{1,2})?$/;
const DAYS = /^\d{1,5}$/;
const PCT = /^\d{1,5}(\.\d{1,2})?$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;
type SettingsFormValue = {
  clerkLimit: string;
  floor: string;
  terms: string;
  termsA: string;
  termsB: string;
  termsC: string;
  latePct: string;
  policyDate: string;
  periodNumberingFrom: string;
  cap: string;
  proofThreshold: string;
  graceDays: string;
} & Record<PermissionField, string>;

const NO_SETTINGS: SheetSettings = {
  clerkLimit: null,
  floor: DEFAULT_FLOOR,
  terms: DEFAULT_TERMS,
  termsA: null,
  termsB: null,
  termsC: null,
  latePct: DEFAULT_LATE_PCT,
  policyDate: null,
  periodNumberingFrom: null,
  cap: null,
  proofThreshold: null,
  graceDays: null,
  dated: Object.fromEntries(DATED_KEYS.map((k) => [k, []])) as unknown as SheetSettings['dated'],
  permissions: Object.fromEntries(PERMISSIONS.map((p) => [p, ''])) as Record<Permission, string>,
};

// The sheet's settings: the role each user name has (clerk unless given another) with the form that
// gives one, the clerk limit, the highest due a clerk may settle (blank for no limit), and the floor, the
// least a claim's live due comes to, the terms, the days after its date a claim is due by (fixed when it is
// added), and each grade's own terms (blank for the sheet's), and the late
// percent, what an overdue claim gains of its figure for every full 30 days overdue, and the policy date (blank
// for the sheet's today), before which a claim's date rounds half up, on or after half to even, and the period
// numbering date (blank for off), from which a claim opened is numbered within its period, and the cap (blank
// for none), the most a person's due-total comes to, and the proof threshold (blank for none), above which a
// claim's due needs a proof before it is settled or approved, and the grace days (blank for off), after which an open period
// past its to-date is closed when the sheet's today is changed, and the permission matrix: for each action, the
// roles that may take it, comma separated (blank for the standing rule). The settings that carry a figure are dated: each is saved
// from its from-date (blank for the sheet's today), and the value in force on the sheet's today is shown. Saving
// sends only the settings changed in the form (the clerk limit when none was).
@Component({
  selector: 'app-settings',
  imports: [ReactiveFormsModule],
  template: `
    <section aria-labelledby="settings-heading" data-testid="settings">
      <h2 id="settings-heading">Settings</h2>
      <form [formGroup]="settingsForm" (ngSubmit)="save()" (focusin)="focused($event)" data-testid="settings-form">
        <label for="setting-clerk-limit">Clerk limit (blank for none)</label>
        <input
          id="setting-clerk-limit"
          data-testid="setting-clerk-limit"
          inputmode="decimal"
          formControlName="clerkLimit"
        />
        <label for="setting-clerk-limit-from">Clerk limit from (yyyy-mm-dd, blank for the sheet's today)</label>
        <input id="setting-clerk-limit-from" data-testid="setting-clerk-limit-from" formControlName="clerkLimitFrom" />
        <label for="setting-floor">Floor</label>
        <input id="setting-floor" data-testid="setting-floor" inputmode="decimal" formControlName="floor" />
        <label for="setting-floor-from">Floor from (yyyy-mm-dd, blank for the sheet's today)</label>
        <input id="setting-floor-from" data-testid="setting-floor-from" formControlName="floorFrom" />
        <label for="setting-terms">Terms (days)</label>
        <input id="setting-terms" data-testid="setting-terms" inputmode="numeric" formControlName="terms" />
        <label for="setting-terms-from">Terms from (yyyy-mm-dd, blank for the sheet's today)</label>
        <input id="setting-terms-from" data-testid="setting-terms-from" formControlName="termsFrom" />
        @for (g of grades; track g) {
          <label [for]="'setting-terms-' + g">Terms for grade {{ g }} (days, blank for the sheet's)</label>
          <input
            [id]="'setting-terms-' + g"
            [attr.data-testid]="'setting-terms-' + g"
            inputmode="numeric"
            [formControlName]="'terms' + g"
          />
          <label [for]="'setting-terms-' + g + '-from'">Terms for grade {{ g }} from (yyyy-mm-dd, blank for the sheet's today)</label>
          <input
            [id]="'setting-terms-' + g + '-from'"
            [attr.data-testid]="'setting-terms-' + g + '-from'"
            [formControlName]="'terms' + g + 'From'"
          />
        }
        <label for="setting-late-pct">Late percent (per full 30 days overdue)</label>
        <input id="setting-late-pct" data-testid="setting-late-pct" inputmode="decimal" formControlName="latePct" />
        <label for="setting-late-pct-from">Late percent from (yyyy-mm-dd, blank for the sheet's today)</label>
        <input id="setting-late-pct-from" data-testid="setting-late-pct-from" formControlName="latePctFrom" />
        <label for="setting-policy-date">Policy date (yyyy-mm-dd, blank for the sheet's today)</label>
        <input id="setting-policy-date" data-testid="setting-policy-date" formControlName="policyDate" />
        <label for="setting-period-numbering-from">Period numbering from (yyyy-mm-dd, blank for off)</label>
        <input
          id="setting-period-numbering-from"
          data-testid="setting-period-numbering-from"
          formControlName="periodNumberingFrom"
        />
        <label for="setting-cap">Cap (blank for none)</label>
        <input id="setting-cap" data-testid="setting-cap" inputmode="decimal" formControlName="cap" />
        <label for="setting-cap-from">Cap from (yyyy-mm-dd, blank for the sheet's today)</label>
        <input id="setting-cap-from" data-testid="setting-cap-from" formControlName="capFrom" />
        <label for="setting-proof-threshold">Proof threshold (blank for none)</label>
        <input
          id="setting-proof-threshold"
          data-testid="setting-proof-threshold"
          inputmode="decimal"
          formControlName="proofThreshold"
        />
        <label for="setting-proof-threshold-from">Proof threshold from (yyyy-mm-dd, blank for the sheet's today)</label>
        <input
          id="setting-proof-threshold-from"
          data-testid="setting-proof-threshold-from"
          formControlName="proofThresholdFrom"
        />
        <label for="setting-grace-days">Grace days (blank for off)</label>
        <input id="setting-grace-days" data-testid="setting-grace-days" inputmode="numeric" formControlName="graceDays" />
        @for (p of permissions; track p) {
          <label [for]="'setting-perm-' + p">Roles that may {{ p }} (comma separated, blank for the standing rule)</label>
          <input [id]="'setting-perm-' + p" [attr.data-testid]="'setting-perm-' + p" [formControlName]="permissionField(p)" />
        }
        <button type="submit" data-testid="settings-save">Save settings</button>
      </form>
      @if (settingsError(); as e) {
        <p role="alert" data-testid="settings-error">{{ e }}</p>
      }
      <p>
        Clerk limit in force:
        <span data-testid="setting-clerk-limit-current">{{ inForce().clerkLimit ?? '' }}</span>
      </p>
      <p>
        Floor in force:
        <span data-testid="setting-floor-current">{{ inForce().floor }}</span>
      </p>
      <p>
        Terms in force:
        <span data-testid="setting-terms-current">{{ inForce().terms }}</span>
      </p>
      @for (g of grades; track g) {
        <p>
          Terms for grade {{ g }} in force:
          <span [attr.data-testid]="'setting-terms-' + g + '-current'">{{ inForce()[gradeKey(g)] ?? '' }}</span>
        </p>
      }
      <p>
        Late percent in force:
        <span data-testid="setting-late-pct-current">{{ inForce().latePct }}</span>
      </p>
      <p>
        Policy date in force:
        <span data-testid="setting-policy-date-current">{{ settings().policyDate ?? '' }}</span>
      </p>
      <p>
        Period numbering from:
        <span data-testid="setting-period-numbering-from-current">{{ settings().periodNumberingFrom ?? '' }}</span>
      </p>
      <p>
        Cap in force:
        <span data-testid="setting-cap-current">{{ inForce().cap ?? '' }}</span>
      </p>
      <p>
        Proof threshold in force:
        <span data-testid="setting-proof-threshold-current">{{ inForce().proofThreshold ?? '' }}</span>
      </p>
      <p>
        Grace days:
        <span data-testid="setting-grace-days-current">{{ settings().graceDays ?? '' }}</span>
      </p>
      @for (p of permissions; track p) {
        <p>
          Roles that may {{ p }}:
          <span [attr.data-testid]="'setting-perm-' + p + '-current'">{{ settings().permissions[p] }}</span>
        </p>
      }
      <form [formGroup]="roleForm" (ngSubmit)="setRole()" data-testid="role-form">
        <label for="role-form-user">User</label>
        <input id="role-form-user" data-testid="role-form-user" formControlName="user" />
        <label for="role-form-role">Role ({{ roleNames }})</label>
        <input id="role-form-role" data-testid="role-form-role" formControlName="role" />
        <button type="submit" data-testid="role-form-submit">Set role</button>
      </form>
      @if (roleError(); as e) {
        <p role="alert" data-testid="role-form-error">{{ e }}</p>
      }
      <table data-testid="role-list">
        <caption>Roles (anyone else is a clerk)</caption>
        <thead>
          <tr>
            <th scope="col">User</th>
            <th scope="col">Role</th>
          </tr>
        </thead>
        <tbody>
          @for (r of roles(); track r.user) {
            <tr [attr.data-testid]="'role-row-' + r.user">
              <td data-testid="role-user">{{ r.user }}</td>
              <td data-testid="role-value">{{ r.role }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Settings {
  private readonly session = inject(Session);
  private readonly api = inject(SettingsApi);
  private readonly floor = inject(SheetFloor);
  private readonly latePct = inject(SheetLatePct);
  private readonly policyDate = inject(SheetPolicyDate);
  private readonly cap = inject(SheetCap);

  protected readonly roleNames = `${ROLES.slice(0, -1).join(', ')} or ${ROLES[ROLES.length - 1]}`;
  protected readonly grades = GRADES;
  protected readonly permissions = PERMISSIONS;
  protected readonly roles = signal<RoleGiven[]>([]);
  protected readonly settings = signal<SheetSettings>(NO_SETTINGS);
  /** The value each setting that carries a figure has in force on the sheet's today. */
  protected readonly inForce = computed(() => {
    const s = this.settings();
    const today = this.session.today();
    const on = (key: DatedKey, starting: string | null) => (today === null ? starting : valueOn(s.dated[key], today, starting));
    return {
      clerkLimit: on('clerk-limit', null),
      floor: on('floor', DEFAULT_FLOOR),
      terms: on('terms', String(DEFAULT_TERMS)),
      termsA: on('terms-A', null),
      termsB: on('terms-B', null),
      termsC: on('terms-C', null),
      latePct: on('late-pct', DEFAULT_LATE_PCT),
      cap: on('cap', null),
      proofThreshold: on('proof-threshold', null),
    };
  });
  protected readonly roleError = signal<string | null>(null);
  protected readonly settingsError = signal<string | null>(null);
  private readonly busy = signal(false);
  /** The setting a person went into last; the clerk limit until they go into one. */
  private lastField: keyof SettingsFormValue | FromField = 'clerkLimit';
  /** Settings saves, sent one after another in the order they were made, so none is lost while another is
   * waiting on the server. */
  private readonly saves = new Subject<{
    sheetId: string;
    changed: Parameters<SettingsApi['save']>[1];
    sent: SettingsFormValue & Record<FromField, string>;
  }>();
  protected readonly roleForm = new FormGroup({
    user: new FormControl('', { nonNullable: true }),
    role: new FormControl('', { nonNullable: true }),
  });
  protected readonly settingsForm = new FormGroup({
    clerkLimit: new FormControl('', { nonNullable: true }),
    floor: new FormControl(DEFAULT_FLOOR, { nonNullable: true }),
    terms: new FormControl(String(DEFAULT_TERMS), { nonNullable: true }),
    termsA: new FormControl('', { nonNullable: true }),
    termsB: new FormControl('', { nonNullable: true }),
    termsC: new FormControl('', { nonNullable: true }),
    latePct: new FormControl(DEFAULT_LATE_PCT, { nonNullable: true }),
    policyDate: new FormControl('', { nonNullable: true }),
    periodNumberingFrom: new FormControl('', { nonNullable: true }),
    cap: new FormControl('', { nonNullable: true }),
    proofThreshold: new FormControl('', { nonNullable: true }),
    graceDays: new FormControl('', { nonNullable: true }),
    clerkLimitFrom: new FormControl('', { nonNullable: true }),
    floorFrom: new FormControl('', { nonNullable: true }),
    termsFrom: new FormControl('', { nonNullable: true }),
    termsAFrom: new FormControl('', { nonNullable: true }),
    termsBFrom: new FormControl('', { nonNullable: true }),
    termsCFrom: new FormControl('', { nonNullable: true }),
    latePctFrom: new FormControl('', { nonNullable: true }),
    capFrom: new FormControl('', { nonNullable: true }),
    proofThresholdFrom: new FormControl('', { nonNullable: true }),
    permOpen: new FormControl('', { nonNullable: true }),
    permSettle: new FormControl('', { nonNullable: true }),
    permVoid: new FormControl('', { nonNullable: true }),
    permReassign: new FormControl('', { nonNullable: true }),
    permClose: new FormControl('', { nonNullable: true }),
    permReopen: new FormControl('', { nonNullable: true }),
  });

  constructor() {
    this.roleForm.valueChanges.subscribe(() => this.roleError.set(null));
    this.settingsForm.valueChanges.subscribe(() => this.settingsError.set(null));
    this.saves
      .pipe(
        concatMap(({ sheetId, changed, sent }) =>
          this.api.save(sheetId, changed).pipe(
            map((s) => ({ sheetId, sent, s })),
            catchError(() => of({ sheetId, sent, s: null })),
          ),
        ),
      )
      .subscribe(({ sheetId, sent, s }) => {
        if (s === null) {
          this.settingsError.set('Could not save the settings.');
        } else if (this.session.sheetId() === sheetId) {
          this.showSettings(s, sent);
        }
      });
    // Re-read the sheet's settings from the store each time a sheet is loaded.
    const loaded = this.session.registerLoader(inject(DestroyRef));
    toObservable(this.session.sheetId)
      .pipe(
        switchMap((id) =>
          id
            ? forkJoin({ id: of(id), roles: this.api.roles(id), settings: this.api.get(id) })
            : of({ id, roles: [] as RoleGiven[], settings: NO_SETTINGS }),
        ),
      )
      .subscribe(({ id, roles, settings }) => {
        this.roles.set(roles);
        this.showSettings(settings);
        this.roleError.set(null);
        this.settingsError.set(null);
        loaded(id);
      });
  }

  protected setRole(): void {
    if (this.busy()) return;
    this.roleError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) {
      this.roleError.set('Load a sheet first.');
      return;
    }
    const user = this.roleForm.getRawValue().user.trim();
    const role = this.roleForm.getRawValue().role.trim();
    if (!user) {
      this.roleError.set('Type a user name.');
      return;
    }
    if (!(ROLES as readonly string[]).includes(role)) {
      this.roleError.set(`A role is ${this.roleNames}.`);
      return;
    }
    this.busy.set(true);
    this.api.setRole(sheetId, user, role).subscribe({
      next: (given) => {
        this.busy.set(false);
        if (this.session.sheetId() !== sheetId) return;
        this.roles.update((rs) =>
          rs.some((r) => r.user === given.user)
            ? rs.map((r) => (r.user === given.user ? given : r))
            : [...rs, given],
        );
        this.roleForm.reset({ user: '', role: '' }, { emitEvent: false });
      },
      error: () => {
        this.busy.set(false);
        this.roleError.set('Could not set that role.');
      },
    });
  }

  /** The setting holding a grade's own terms. */
  protected gradeKey(grade: (typeof GRADES)[number]): GradeTermsKey {
    return `terms${grade}`;
  }

  /** The setting holding an action's roles. */
  protected permissionField(action: Permission): PermissionField {
    return PERMISSION_FIELDS[action];
  }

  /** Note the setting a person went into last: a save with nothing changed saves that one again. */
  protected focused(event: FocusEvent): void {
    const name = (event.target as Element | null)?.getAttribute('formcontrolname');
    if (name && name in this.settingsForm.controls) this.lastField = name as keyof SettingsFormValue | FromField;
  }

  protected save(): void {
    this.settingsError.set(null);
    const sheetId = this.session.sheetId();
    if (!sheetId) {
      this.settingsError.set('Load a sheet first.');
      return;
    }
    const controls = this.settingsForm.controls;
    // With nothing changed, the setting last gone into is saved again as it is.
    const unchanged = !this.settingsForm.dirty;
    // A setting that carries a figure is sent too when a from-date is typed for it, or it was last gone into.
    const fromOf = (key: keyof SettingsFormValue) =>
      key in DATED_FIELDS ? controls[`${key as DatedField}From`].value.trim() : '';
    const send = (key: keyof SettingsFormValue) =>
      controls[key].dirty || fromOf(key) !== '' || (unchanged && (this.lastField === key || this.lastField === `${key}From`));
    const clerkLimit = controls.clerkLimit.value.trim();
    const floor = controls.floor.value.trim();
    const terms = controls.terms.value.trim();
    const sendFloor = send('floor');
    const latePct = controls.latePct.value.trim();
    const sendTerms = send('terms');
    const sendLatePct = send('latePct');
    const policyDate = controls.policyDate.value.trim();
    const sendPolicyDate = send('policyDate');
    const periodNumberingFrom = controls.periodNumberingFrom.value.trim();
    const sendPeriodNumbering = send('periodNumberingFrom');
    const sendLimit = send('clerkLimit');
    const cap = controls.cap.value.trim();
    const sendCap = send('cap');
    const proofThreshold = controls.proofThreshold.value.trim();
    const sendProofThreshold = send('proofThreshold');
    const graceDays = controls.graceDays.value.trim();
    const sendGraceDays = send('graceDays');
    if (sendLimit && clerkLimit !== '' && !LIMIT.test(clerkLimit)) {
      this.settingsError.set('Type a clerk limit of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    if (sendFloor && !LIMIT.test(floor)) {
      this.settingsError.set('Type a floor of 0.00 or more, with up to two decimals.');
      return;
    }
    if (sendTerms && !DAYS.test(terms)) {
      this.settingsError.set('Type terms of 0 or more whole days.');
      return;
    }
    const gradeTerms: Partial<Record<GradeTermsKey, string>> = {};
    for (const g of GRADES) {
      const key = this.gradeKey(g);
      if (!send(key)) continue;
      const days = controls[key].value.trim();
      if (days !== '' && !DAYS.test(days)) {
        this.settingsError.set(`Type terms for grade ${g} of 0 or more whole days, or leave them blank.`);
        return;
      }
      gradeTerms[key] = days;
    }
    if (sendLatePct && !PCT.test(latePct)) {
      this.settingsError.set('Type a late percent of 0.00 or more, with up to two decimals.');
      return;
    }
    if (sendPolicyDate && policyDate !== '' && !(DATE.test(policyDate) && !isNaN(Date.parse(policyDate)))) {
      this.settingsError.set('Type a policy date as yyyy-mm-dd, or leave it blank.');
      return;
    }
    if (
      sendPeriodNumbering &&
      periodNumberingFrom !== '' &&
      !(DATE.test(periodNumberingFrom) && !isNaN(Date.parse(periodNumberingFrom)))
    ) {
      this.settingsError.set('Type a period numbering date as yyyy-mm-dd, or leave it blank.');
      return;
    }
    if (sendCap && cap !== '' && !LIMIT.test(cap)) {
      this.settingsError.set('Type a cap of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    if (sendProofThreshold && proofThreshold !== '' && !LIMIT.test(proofThreshold)) {
      this.settingsError.set('Type a proof threshold of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    if (sendGraceDays && graceDays !== '' && !DAYS.test(graceDays)) {
      this.settingsError.set('Type grace days of 0 or more whole days, or leave them blank.');
      return;
    }
    const permissions: Partial<Record<PermissionField, string>> = {};
    for (const p of PERMISSIONS) {
      const key = PERMISSION_FIELDS[p];
      if (!send(key)) continue;
      const roles = controls[key].value.trim();
      if (roles !== '' && !roles.split(',').every((r) => (ROLES as readonly string[]).includes(r.trim()))) {
        this.settingsError.set(`Type the roles that may ${p} (${this.roleNames}), comma separated, or leave it blank.`);
        return;
      }
      permissions[key] = roles;
    }
    const froms: Partial<Record<FromField, string>> = {};
    for (const key of Object.keys(DATED_FIELDS) as DatedField[]) {
      const from = fromOf(key);
      if (!send(key) || from === '') continue;
      if (!(DATE.test(from) && !isNaN(Date.parse(from)))) {
        this.settingsError.set('Type a from-date as yyyy-mm-dd, or leave it blank for the sheet\'s today.');
        return;
      }
      froms[`${key}From`] = from;
    }
    const changed = {
      ...(sendLimit ? { clerkLimit } : {}),
      ...(sendFloor ? { floor } : {}),
      ...(sendTerms ? { terms } : {}),
      ...gradeTerms,
      ...(sendLatePct ? { latePct } : {}),
      ...(sendPolicyDate ? { policyDate } : {}),
      ...(sendPeriodNumbering ? { periodNumberingFrom } : {}),
      ...(sendCap ? { cap } : {}),
      ...(sendProofThreshold ? { proofThreshold } : {}),
      ...(sendGraceDays ? { graceDays } : {}),
      ...permissions,
      ...froms,
    };
    this.saves.next({ sheetId, changed, sent: this.settingsForm.getRawValue() });
  }

  /** Show the settings in force, and put them back in the form as unchanged; after a save (`sent` the form as
   * it was saved), a setting changed in the form since is left as it was typed. */
  private showSettings(s: SheetSettings, sent?: SettingsFormValue & Record<FromField, string>): void {
    this.settings.set(s);
    this.floor.set(s.dated.floor);
    this.latePct.set(s.dated['late-pct']);
    this.policyDate.set(s.policyDate);
    this.cap.set(s.dated.cap);
    const shown: SettingsFormValue = {
      clerkLimit: s.clerkLimit ?? '',
      floor: s.floor,
      terms: String(s.terms),
      termsA: s.termsA === null ? '' : String(s.termsA),
      termsB: s.termsB === null ? '' : String(s.termsB),
      termsC: s.termsC === null ? '' : String(s.termsC),
      latePct: s.latePct,
      policyDate: s.policyDate ?? '',
      periodNumberingFrom: s.periodNumberingFrom ?? '',
      cap: s.cap ?? '',
      proofThreshold: s.proofThreshold ?? '',
      graceDays: s.graceDays === null ? '' : String(s.graceDays),
      ...(Object.fromEntries(PERMISSIONS.map((p) => [PERMISSION_FIELDS[p], s.permissions[p]])) as Record<
        PermissionField,
        string
      >),
    };
    const controls = this.settingsForm.controls;
    for (const key of Object.keys(shown) as (keyof SettingsFormValue)[]) {
      if (!sent || controls[key].value === sent[key]) controls[key].reset(shown[key], { emitEvent: false });
    }
    // A from-date goes with the save it was typed for: it is cleared, unless typed again since.
    for (const key of Object.keys(DATED_FIELDS) as DatedField[]) {
      const from = controls[`${key}From`];
      if (!sent || from.value === sent[`${key}From`]) from.reset('', { emitEvent: false });
    }
  }
}
