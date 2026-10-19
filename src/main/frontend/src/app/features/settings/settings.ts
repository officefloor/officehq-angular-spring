import { Component, DestroyRef, inject, signal } from '@angular/core';
import { toObservable } from '@angular/core/rxjs-interop';
import { FormControl, FormGroup, ReactiveFormsModule } from '@angular/forms';
import { Subject, catchError, concatMap, forkJoin, map, of, switchMap } from 'rxjs';
import { Session } from '../../core/session';
import { ROLES, RoleGiven, Settings as SheetSettings, SettingsApi } from './settings-api';
import { DEFAULT_FLOOR, SheetFloor } from './sheet-floor';
import { DEFAULT_LATE_PCT, SheetLatePct } from './sheet-late-pct';
import { SheetPolicyDate } from './sheet-policy-date';

/** The terms a sheet starts with, in days. */
const DEFAULT_TERMS = 14;
/** The grades that can have terms of their own. */
const GRADES = ['A', 'B', 'C'] as const;
type GradeTermsKey = `terms${(typeof GRADES)[number]}`;
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
};

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
};

// The sheet's settings: the role each user name has (clerk unless given another) with the form that
// gives one, the clerk limit, the highest due a clerk may settle (blank for no limit), and the floor, the
// least a claim's live due comes to, the terms, the days after its date a claim is due by (fixed when it is
// added), and each grade's own terms (blank for the sheet's), and the late
// percent, what an overdue claim gains of its figure for every full 30 days overdue, and the policy date (blank
// for the sheet's today), before which a claim's date rounds half up, on or after half to even, and the period
// numbering date (blank for off), from which a claim opened is numbered within its period. Saving sends
// only the settings changed in the form (the clerk limit when none was).
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
        <label for="setting-floor">Floor</label>
        <input id="setting-floor" data-testid="setting-floor" inputmode="decimal" formControlName="floor" />
        <label for="setting-terms">Terms (days)</label>
        <input id="setting-terms" data-testid="setting-terms" inputmode="numeric" formControlName="terms" />
        @for (g of grades; track g) {
          <label [for]="'setting-terms-' + g">Terms for grade {{ g }} (days, blank for the sheet's)</label>
          <input
            [id]="'setting-terms-' + g"
            [attr.data-testid]="'setting-terms-' + g"
            inputmode="numeric"
            [formControlName]="'terms' + g"
          />
        }
        <label for="setting-late-pct">Late percent (per full 30 days overdue)</label>
        <input id="setting-late-pct" data-testid="setting-late-pct" inputmode="decimal" formControlName="latePct" />
        <label for="setting-policy-date">Policy date (yyyy-mm-dd, blank for the sheet's today)</label>
        <input id="setting-policy-date" data-testid="setting-policy-date" formControlName="policyDate" />
        <label for="setting-period-numbering-from">Period numbering from (yyyy-mm-dd, blank for off)</label>
        <input
          id="setting-period-numbering-from"
          data-testid="setting-period-numbering-from"
          formControlName="periodNumberingFrom"
        />
        <button type="submit" data-testid="settings-save">Save settings</button>
      </form>
      @if (settingsError(); as e) {
        <p role="alert" data-testid="settings-error">{{ e }}</p>
      }
      <p>
        Clerk limit in force:
        <span data-testid="setting-clerk-limit-current">{{ settings().clerkLimit ?? '' }}</span>
      </p>
      <p>
        Floor in force:
        <span data-testid="setting-floor-current">{{ settings().floor }}</span>
      </p>
      <p>
        Terms in force:
        <span data-testid="setting-terms-current">{{ settings().terms }}</span>
      </p>
      @for (g of grades; track g) {
        <p>
          Terms for grade {{ g }} in force:
          <span [attr.data-testid]="'setting-terms-' + g + '-current'">{{ settings()[gradeKey(g)] ?? '' }}</span>
        </p>
      }
      <p>
        Late percent in force:
        <span data-testid="setting-late-pct-current">{{ settings().latePct }}</span>
      </p>
      <p>
        Policy date in force:
        <span data-testid="setting-policy-date-current">{{ settings().policyDate ?? '' }}</span>
      </p>
      <p>
        Period numbering from:
        <span data-testid="setting-period-numbering-from-current">{{ settings().periodNumberingFrom ?? '' }}</span>
      </p>
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

  protected readonly roleNames = ROLES.join(' or ');
  protected readonly grades = GRADES;
  protected readonly roles = signal<RoleGiven[]>([]);
  protected readonly settings = signal<SheetSettings>(NO_SETTINGS);
  protected readonly roleError = signal<string | null>(null);
  protected readonly settingsError = signal<string | null>(null);
  private readonly busy = signal(false);
  /** The setting a person went into last; the clerk limit until they go into one. */
  private lastField: keyof SettingsFormValue = 'clerkLimit';
  /** Settings saves, sent one after another in the order they were made, so none is lost while another is
   * waiting on the server. */
  private readonly saves = new Subject<{ sheetId: string; changed: Parameters<SettingsApi['save']>[1]; sent: SettingsFormValue }>();
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

  /** Note the setting a person went into last: a save with nothing changed saves that one again. */
  protected focused(event: FocusEvent): void {
    const name = (event.target as Element | null)?.getAttribute('formcontrolname');
    if (name && name in this.settingsForm.controls) this.lastField = name as keyof SettingsFormValue;
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
    const send = (key: keyof SettingsFormValue) => controls[key].dirty || (unchanged && this.lastField === key);
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
    const changed = {
      ...(sendLimit ? { clerkLimit } : {}),
      ...(sendFloor ? { floor } : {}),
      ...(sendTerms ? { terms } : {}),
      ...gradeTerms,
      ...(sendLatePct ? { latePct } : {}),
      ...(sendPolicyDate ? { policyDate } : {}),
      ...(sendPeriodNumbering ? { periodNumberingFrom } : {}),
    };
    this.saves.next({ sheetId, changed, sent: this.settingsForm.getRawValue() });
  }

  /** Show the settings in force, and put them back in the form as unchanged; after a save (`sent` the form as
   * it was saved), a setting changed in the form since is left as it was typed. */
  private showSettings(s: SheetSettings, sent?: SettingsFormValue): void {
    this.settings.set(s);
    this.floor.set(s.floor);
    this.latePct.set(s.latePct);
    this.policyDate.set(s.policyDate);
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
    };
    const controls = this.settingsForm.controls;
    for (const key of Object.keys(shown) as (keyof SettingsFormValue)[]) {
      if (!sent || controls[key].value === sent[key]) controls[key].reset(shown[key], { emitEvent: false });
    }
  }
}
