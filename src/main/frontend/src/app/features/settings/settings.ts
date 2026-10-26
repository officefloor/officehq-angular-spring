import { HttpClient } from '@angular/common/http';
import { Component, Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { GRADES } from '../rates/rates';

export interface RoleView {
  user: string;
  role: string;
}

interface SettingsView {
  // The clerk limit, the floor, the terms, the late percent and the cap in force on the sheet's today.
  clerkLimit: string | null;
  floor: string;
  terms: number;
  latePct: string;
  // The date claims round by (before it half up, on or after it half to even); null when blank: the sheet's today.
  policyDate: string | null;
  // The date from which claims opened are numbered within their period; null when blank: off.
  periodNumberingFrom: string | null;
  // The days after its to-date an open period is left open once the sheet's today passes it; null when blank: off.
  graceDays: number | null;
  cap: string | null;
  // Every value recorded for the dated settings, each from its from-date.
  dated: DatedSetting[];
  // The permission matrix as set, by action: the roles that may take it, comma separated; an action not listed keeps
  // the standing rule.
  permissions: Record<string, string>;
}

/**
 * One value recorded for a dated setting, in force from its from-date until the next one recorded for its key:
 * clerk-limit, floor, terms, terms-<grade>, late-pct, cap, proof-threshold or note-threshold. The value is null for
 * none (a blank clerk limit, cap, proof threshold or note threshold).
 */
interface DatedSetting {
  key: string;
  from: string;
  value: string | null;
}

/** A dated setting's key as the form and its fields name it. */
export type DatedKey = 'clerk-limit' | 'floor' | 'terms' | 'late-pct' | 'cap' | 'proof-threshold' | 'note-threshold' | `terms-${string}`;

export const ROLES = ['clerk', 'steward', 'viewer'];
/** The roles the permission matrix can list; a viewer may take no action. */
export const PERMISSION_ROLES = ['clerk', 'steward'];

/** The actions of the permission matrix, with the roles that may take each while none are set (the standing rule). */
export const PERMISSIONS: readonly { action: string; standing: string }[] = [
  { action: 'open', standing: 'clerk,steward' },
  { action: 'settle', standing: 'clerk,steward' },
  { action: 'void', standing: 'clerk,steward' },
  { action: 'reassign', standing: 'steward' },
  { action: 'close', standing: 'clerk,steward' },
  { action: 'reopen', standing: 'steward' },
];

/** Roles typed as a comma separated list, trimmed and each listed once; blank for none typed. */
function normalRoles(typed: string): string {
  const roles = typed.split(',').map((r) => r.trim());
  return typed.trim() ? [...new Set(roles)].join(',') : '';
}

const LIMIT = /^\d{1,12}(\.\d{1,2})?$/;

/** The floor a sheet starts with: in force before any is recorded. */
export const DEFAULT_FLOOR = '1.00';

/** The terms a sheet starts with, in days: in force before any are recorded. */
export const DEFAULT_TERMS = 14;

const DAYS = /^\d{1,5}$/;

/** The late percent a sheet starts with: in force before any is recorded. */
export const DEFAULT_LATE_PCT = '0.00';

const PCT = /^\d{1,5}(\.\d{1,2})?$/;

const DATE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * The value recorded for the key in force on the date: the one with the latest from-date on or before it;
 * undefined when none is (the setting's starting value applies), and null when the one in force is none.
 */
function valueOn(dated: readonly DatedSetting[], key: string, date: string | null): string | null | undefined {
  let best: DatedSetting | undefined;
  for (const d of dated) {
    if (d.key !== key || date === null || d.from > date) continue;
    if (!best || d.from > best.from) best = d;
  }
  return best?.value;
}

// The active sheet's settings: the role set for each user name (any other name is a clerk); the clerk limit, the
// floor, the terms (the sheet's and each grade's), the late percent and the cap, each recorded from a from-date;
// the policy date, the period numbering from and the grace days; and the permission matrix.
@Injectable({ providedIn: 'root' })
export class SettingsStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly roles = signal<readonly RoleView[]>([]);
  /** Every value recorded for the dated settings. */
  readonly dated = signal<readonly DatedSetting[]>([]);
  /** The floor in force on a date: the least the live due of a claim dated on it can be. */
  readonly floor = computed(() => {
    const dated = this.dated();
    return (date: string | null): string => valueOn(dated, 'floor', date) ?? DEFAULT_FLOOR;
  });
  /** The late percent in force on a date: what an overdue claim gains for a 30-day boundary past its due-by falling on it. */
  readonly latePct = computed(() => {
    const dated = this.dated();
    return (date: string | null): string => valueOn(dated, 'late-pct', date) ?? DEFAULT_LATE_PCT;
  });
  /** The clerk limit in force on the sheet's today; null for no limit. */
  readonly clerkLimit = computed(() => valueOn(this.dated(), 'clerk-limit', this.session.sheetToday()) ?? null);
  /** The cap in force on the sheet's today; null for none. */
  readonly cap = computed(() => valueOn(this.dated(), 'cap', this.session.sheetToday()) ?? null);
  /** The proof threshold in force on the sheet's today; null for none. */
  readonly proofThreshold = computed(() => valueOn(this.dated(), 'proof-threshold', this.session.sheetToday()) ?? null);
  /** The note threshold in force on the sheet's today; null for none. */
  readonly noteThreshold = computed(() => valueOn(this.dated(), 'note-threshold', this.session.sheetToday()) ?? null);
  /** The floor, the terms and the late percent in force on the sheet's today. */
  readonly floorToday = computed(() => this.floor()(this.session.sheetToday()));
  readonly termsToday = computed(() => this.termsFor('', this.session.sheetToday()));
  readonly latePctToday = computed(() => this.latePct()(this.session.sheetToday()));
  /** The policy date as set; null when blank. */
  readonly policyDate = signal<string | null>(null);
  /** The period numbering from as set; null when blank (off). */
  readonly periodNumberingFrom = signal<string | null>(null);
  /** The grace days as set; null when blank (off). */
  readonly graceDays = signal<number | null>(null);
  /** The permission matrix as set, by action; an action not listed keeps the standing rule. */
  readonly permissions = signal<Readonly<Record<string, string>>>({});
  /**
   * What an overdue claim's late charge follows: the sheet's today and the late percent in force on each 30-day
   * boundary past its due-by; and the policy date in force (the sheet's today when blank), which decides how live figures round.
   */
  readonly lateness = computed(() => ({
    today: this.session.sheetToday(),
    latePct: this.latePct(),
    policyDate: this.policyDate() ?? this.session.sheetToday(),
  }));

  /** Re-read the sheet's settings from the store. */
  async load(sheet: string): Promise<void> {
    this.roles.set([]);
    this.dated.set([]);
    this.policyDate.set(null);
    this.periodNumberingFrom.set(null);
    this.graceDays.set(null);
    this.permissions.set({});
    const base = `/api/sheets/${encodeURIComponent(sheet)}`;
    const [roles, settings] = await Promise.all([
      firstValueFrom(this.http.get<RoleView[]>(`${base}/roles`)),
      firstValueFrom(this.http.get<SettingsView>(`${base}/settings`)),
    ]);
    if (this.session.sheetId() === sheet) {
      this.roles.set(roles);
      this.show(settings);
    }
  }

  private show(s: SettingsView): void {
    this.dated.set(s.dated);
    this.policyDate.set(s.policyDate);
    this.periodNumberingFrom.set(s.periodNumberingFrom);
    this.graceDays.set(s.graceDays);
    this.permissions.set(s.permissions ?? {});
  }

  async setRole(sheet: string, user: string, role: string): Promise<void> {
    const r = await firstValueFrom(
      this.http.put<RoleView>(`/api/sheets/${encodeURIComponent(sheet)}/roles/${encodeURIComponent(user)}`, { role }),
    );
    if (this.session.sheetId() === sheet) {
      this.roles.update((l) => [...l.filter((x) => x.user !== r.user), r].sort((a, b) => a.user.localeCompare(b.user)));
    }
  }

  /**
   * Record a dated setting's value (null for none) from the date, or from the sheet's today when null; a value
   * already recorded from that date is replaced.
   */
  async setDated(sheet: string, key: DatedKey, value: string | number | null, from: string | null): Promise<void> {
    const path = key.startsWith('terms-') ? `grade-terms/${encodeURIComponent(key.slice('terms-'.length))}` : key;
    const s = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/${path}`, { value, from }),
    );
    if (this.session.sheetId() === sheet) this.show(s);
  }

  /** The terms in force on the date for the grade: its own, else the sheet's (the starting 14 before any). */
  termsFor(grade: string, date: string | null): number {
    const own = grade ? valueOn(this.dated(), `terms-${grade}`, date) : undefined;
    const terms = own ?? valueOn(this.dated(), 'terms', date);
    return terms == null ? DEFAULT_TERMS : Number(terms);
  }

  async setPolicyDate(sheet: string, value: string | null): Promise<void> {
    const s = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/policy-date`, { value }),
    );
    if (this.session.sheetId() === sheet) this.show(s);
  }

  /** Set the roles that may take the action, comma separated; null (blank) for the standing rule. */
  async setPermission(sheet: string, action: string, value: string | null): Promise<void> {
    const s = await firstValueFrom(
      this.http.put<SettingsView>(
        `/api/sheets/${encodeURIComponent(sheet)}/settings/permissions/${encodeURIComponent(action)}`,
        { value },
      ),
    );
    if (this.session.sheetId() === sheet) this.show(s);
  }

  async setPeriodNumberingFrom(sheet: string, value: string | null): Promise<void> {
    const s = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/period-numbering-from`, { value }),
    );
    if (this.session.sheetId() === sheet) this.show(s);
  }

  async setGraceDays(sheet: string, value: number | null): Promise<void> {
    const s = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/grace-days`, { value }),
    );
    if (this.session.sheetId() === sheet) this.show(s);
  }
}

// The sheet's settings: the clerk limit, the floor, the terms, the late percent and the cap, each saved from a from-date
// (blank for the sheet's today) and shown as in force on the sheet's today; the policy date, the period numbering
// from, the grace days, and the role each user name has.
@Component({
  selector: 'app-settings',
  template: `
    <section aria-label="Settings">
      <h2>Settings</h2>
      <form (submit)="saveSettings($event)" aria-label="Save settings">
        <label for="setting-clerk-limit">Clerk limit (blank for none)</label>
        <input
          id="setting-clerk-limit"
          data-testid="setting-clerk-limit"
          inputmode="decimal"
          [value]="limit()"
          (input)="limit.set(value($event)); limitChanged = true; settingsError.set(null)"
          (keydown.delete)="limitChanged = true; settingsError.set(null)"
          (keydown.backspace)="limitChanged = true; settingsError.set(null)"
        />
        <label for="setting-clerk-limit-from">Clerk limit from (blank for the sheet's today)</label>
        <input
          id="setting-clerk-limit-from"
          data-testid="setting-clerk-limit-from"
          type="date"
          [value]="from()['clerk-limit'] ?? ''"
          (input)="setFrom('clerk-limit', value($event))"
          (change)="setFrom('clerk-limit', value($event))"
        />
        <label for="setting-floor">Floor (the least a live due can be)</label>
        <input
          id="setting-floor"
          data-testid="setting-floor"
          inputmode="decimal"
          [value]="floor()"
          (input)="floor.set(value($event)); floorChanged = true; settingsError.set(null)"
          (keydown.delete)="floorChanged = true; settingsError.set(null)"
          (keydown.backspace)="floorChanged = true; settingsError.set(null)"
        />
        <label for="setting-floor-from">Floor from (blank for the sheet's today)</label>
        <input
          id="setting-floor-from"
          data-testid="setting-floor-from"
          type="date"
          [value]="from()['floor'] ?? ''"
          (input)="setFrom('floor', value($event))"
          (change)="setFrom('floor', value($event))"
        />
        <label for="setting-terms">Terms (days a claim is due by after its date)</label>
        <input
          id="setting-terms"
          data-testid="setting-terms"
          inputmode="numeric"
          [value]="terms()"
          (input)="terms.set(value($event)); termsChanged = true; settingsError.set(null)"
          (keydown.delete)="termsChanged = true; settingsError.set(null)"
          (keydown.backspace)="termsChanged = true; settingsError.set(null)"
        />
        <label for="setting-terms-from">Terms from (blank for the sheet's today)</label>
        <input
          id="setting-terms-from"
          data-testid="setting-terms-from"
          type="date"
          [value]="from()['terms'] ?? ''"
          (input)="setFrom('terms', value($event))"
          (change)="setFrom('terms', value($event))"
        />
        @for (g of grades; track g) {
          <label [for]="'setting-terms-' + g">Terms for grade {{ g }} (days; the sheet's terms until set)</label>
          <input
            [id]="'setting-terms-' + g"
            [attr.data-testid]="'setting-terms-' + g"
            inputmode="numeric"
            [value]="gradeTerms()[g] ?? ''"
            (input)="setGradeTermsField(g, value($event))"
            (keydown.delete)="gradeTermsChanged.add(g); settingsError.set(null)"
            (keydown.backspace)="gradeTermsChanged.add(g); settingsError.set(null)"
          />
          <label [for]="'setting-terms-' + g + '-from'">Terms for grade {{ g }} from (blank for the sheet's today)</label>
          <input
            [id]="'setting-terms-' + g + '-from'"
            [attr.data-testid]="'setting-terms-' + g + '-from'"
            type="date"
            [value]="from()[gradeKey(g)] ?? ''"
            (input)="setFrom(gradeKey(g), value($event))"
            (change)="setFrom(gradeKey(g), value($event))"
          />
        }
        <label for="setting-late-pct">Late percent (added for every full 30 days a claim is overdue)</label>
        <input
          id="setting-late-pct"
          data-testid="setting-late-pct"
          inputmode="decimal"
          [value]="latePct()"
          (input)="latePct.set(value($event)); latePctChanged = true; settingsError.set(null)"
          (keydown.delete)="latePctChanged = true; settingsError.set(null)"
          (keydown.backspace)="latePctChanged = true; settingsError.set(null)"
        />
        <label for="setting-late-pct-from">Late percent from (blank for the sheet's today)</label>
        <input
          id="setting-late-pct-from"
          data-testid="setting-late-pct-from"
          type="date"
          [value]="from()['late-pct'] ?? ''"
          (input)="setFrom('late-pct', value($event))"
          (change)="setFrom('late-pct', value($event))"
        />
        <label for="setting-policy-date">Policy date (claims before it round half up; blank for the sheet's today)</label>
        <input
          id="setting-policy-date"
          data-testid="setting-policy-date"
          type="date"
          [value]="policyDate()"
          (input)="policyDate.set(value($event)); policyDateChanged = true; settingsError.set(null)"
          (change)="policyDate.set(value($event)); policyDateChanged = true; settingsError.set(null)"
          (keydown.delete)="policyDateChanged = true; settingsError.set(null)"
          (keydown.backspace)="policyDateChanged = true; settingsError.set(null)"
        />
        <label for="setting-period-numbering-from">Period numbering from (claims opened from it are numbered within their period; blank for off)</label>
        <input
          id="setting-period-numbering-from"
          data-testid="setting-period-numbering-from"
          type="date"
          [value]="periodNumberingFrom()"
          (input)="periodNumberingFrom.set(value($event)); periodNumberingFromChanged = true; settingsError.set(null)"
          (change)="periodNumberingFrom.set(value($event)); periodNumberingFromChanged = true; settingsError.set(null)"
          (keydown.delete)="periodNumberingFromChanged = true; settingsError.set(null)"
          (keydown.backspace)="periodNumberingFromChanged = true; settingsError.set(null)"
        />
        <label for="setting-grace-days">Grace days (open periods this many days past their end close when today moves; blank for off)</label>
        <input
          id="setting-grace-days"
          data-testid="setting-grace-days"
          inputmode="numeric"
          [value]="graceDays()"
          (input)="graceDays.set(value($event)); graceDaysChanged = true; settingsError.set(null)"
          (keydown.delete)="graceDaysChanged = true; settingsError.set(null)"
          (keydown.backspace)="graceDaysChanged = true; settingsError.set(null)"
        />
        <label for="setting-cap">Cap (the most a person's due-total can be; blank for none)</label>
        <input
          id="setting-cap"
          data-testid="setting-cap"
          inputmode="decimal"
          [value]="cap()"
          (input)="cap.set(value($event)); capChanged = true; settingsError.set(null)"
          (keydown.delete)="capChanged = true; settingsError.set(null)"
          (keydown.backspace)="capChanged = true; settingsError.set(null)"
        />
        <label for="setting-cap-from">Cap from (blank for the sheet's today)</label>
        <input
          id="setting-cap-from"
          data-testid="setting-cap-from"
          type="date"
          [value]="from()['cap'] ?? ''"
          (input)="setFrom('cap', value($event))"
          (change)="setFrom('cap', value($event))"
        />
        <label for="setting-proof-threshold">Proof threshold (a claim due above it needs a proof to settle; blank for none)</label>
        <input
          id="setting-proof-threshold"
          data-testid="setting-proof-threshold"
          inputmode="decimal"
          [value]="proofThreshold()"
          (input)="proofThreshold.set(value($event)); proofThresholdChanged = true; settingsError.set(null)"
          (keydown.delete)="proofThresholdChanged = true; settingsError.set(null)"
          (keydown.backspace)="proofThresholdChanged = true; settingsError.set(null)"
        />
        <label for="setting-proof-threshold-from">Proof threshold from (blank for the sheet's today)</label>
        <input
          id="setting-proof-threshold-from"
          data-testid="setting-proof-threshold-from"
          type="date"
          [value]="from()['proof-threshold'] ?? ''"
          (input)="setFrom('proof-threshold', value($event))"
          (change)="setFrom('proof-threshold', value($event))"
        />
        <label for="setting-note-threshold">Note threshold (a claim due above it needs a note to open; blank for none)</label>
        <input
          id="setting-note-threshold"
          data-testid="setting-note-threshold"
          inputmode="decimal"
          [value]="noteThreshold()"
          (input)="noteThreshold.set(value($event)); noteThresholdChanged = true; settingsError.set(null)"
          (keydown.delete)="noteThresholdChanged = true; settingsError.set(null)"
          (keydown.backspace)="noteThresholdChanged = true; settingsError.set(null)"
        />
        <label for="setting-note-threshold-from">Note threshold from (blank for the sheet's today)</label>
        <input
          id="setting-note-threshold-from"
          data-testid="setting-note-threshold-from"
          type="date"
          [value]="from()['note-threshold'] ?? ''"
          (input)="setFrom('note-threshold', value($event))"
          (change)="setFrom('note-threshold', value($event))"
        />
        <fieldset>
          <legend>Permissions (roles that may take each action, comma separated; blank for the standing rule)</legend>
          @for (p of permissionActions; track p.action) {
            <label [for]="'setting-perm-' + p.action">{{ p.action }} (standing: {{ p.standing }})</label>
            <input
              [id]="'setting-perm-' + p.action"
              [attr.data-testid]="'setting-perm-' + p.action"
              [value]="perms()[p.action] ?? ''"
              (input)="setPermField(p.action, value($event))"
              (keydown.delete)="permsChanged.add(p.action); settingsError.set(null)"
              (keydown.backspace)="permsChanged.add(p.action); settingsError.set(null)"
            />
          }
        </fieldset>
        <button type="submit" data-testid="settings-save">Save settings</button>
        @if (settingsError(); as err) {
          <p role="alert" data-testid="settings-error">{{ err }}</p>
        }
      </form>
      <p>Clerk limit in force: <strong data-testid="setting-clerk-limit-current">{{ store.clerkLimit() ?? '' }}</strong></p>
      <p>Floor in force: <strong data-testid="setting-floor-current">{{ store.floorToday() }}</strong></p>
      <p>Terms in force: <strong data-testid="setting-terms-current">{{ store.termsToday() }}</strong> days</p>
      @for (g of grades; track g) {
        <p>
          Terms in force for grade {{ g }}:
          <strong [attr.data-testid]="'setting-terms-' + g + '-current'">{{ store.termsFor(g, session.sheetToday()) }}</strong> days
        </p>
      }
      <p>Late percent in force: <strong data-testid="setting-late-pct-current">{{ store.latePctToday() }}</strong></p>
      <p>Policy date in force: <strong data-testid="setting-policy-date-current">{{ store.policyDate() ?? '' }}</strong></p>
      <p>
        Period numbering from:
        <strong data-testid="setting-period-numbering-from-current">{{ store.periodNumberingFrom() ?? '' }}</strong>
      </p>
      <p>Grace days: <strong data-testid="setting-grace-days-current">{{ store.graceDays() ?? '' }}</strong></p>
      <p>Cap in force: <strong data-testid="setting-cap-current">{{ store.cap() ?? '' }}</strong></p>
      <p>
        Proof threshold in force:
        <strong data-testid="setting-proof-threshold-current">{{ store.proofThreshold() ?? '' }}</strong>
      </p>
      <p>
        Note threshold in force:
        <strong data-testid="setting-note-threshold-current">{{ store.noteThreshold() ?? '' }}</strong>
      </p>
      @for (p of permissionActions; track p.action) {
        <p>
          Roles that may {{ p.action }}:
          <strong [attr.data-testid]="'setting-perm-' + p.action + '-current'">{{ store.permissions()[p.action] ?? '' }}</strong>
          @if (store.permissions()[p.action] === undefined) {
            (standing rule: {{ p.standing }})
          }
        </p>
      }

      <form (submit)="saveRole($event)" aria-label="Set role">
        <label for="role-user">User name</label>
        <input
          id="role-user"
          data-testid="role-form-user"
          [value]="user()"
          (input)="user.set(value($event)); roleError.set(null)"
        />
        <label for="role-role">Role (clerk, steward or viewer)</label>
        <input
          id="role-role"
          data-testid="role-form-role"
          list="role-options"
          [value]="role()"
          (input)="role.set(value($event)); roleError.set(null)"
        />
        <datalist id="role-options">
          @for (r of roles; track r) {
            <option [value]="r"></option>
          }
        </datalist>
        <button type="submit" data-testid="role-form-submit">Set role</button>
        @if (roleError(); as err) {
          <p role="alert" data-testid="role-form-error">{{ err }}</p>
        }
      </form>

      <table>
        <caption>Roles (any other name is a clerk)</caption>
        <thead>
          <tr><th scope="col">User</th><th scope="col">Role</th></tr>
        </thead>
        <tbody>
          @for (r of store.roles(); track r.user) {
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
  protected readonly session = inject(Session);
  protected readonly store = inject(SettingsStore);

  protected readonly roles = ROLES;
  protected readonly grades = GRADES;
  protected readonly permissionActions = PERMISSIONS;
  // The roles typed for each action of the permission matrix, by action.
  protected readonly perms = signal<Readonly<Record<string, string>>>({});
  protected readonly limit = signal('');
  protected readonly floor = signal('');
  protected readonly terms = signal('');
  protected readonly gradeTerms = signal<Readonly<Record<string, string>>>({});
  protected readonly latePct = signal('');
  protected readonly policyDate = signal('');
  protected readonly periodNumberingFrom = signal('');
  protected readonly graceDays = signal('');
  protected readonly cap = signal('');
  protected readonly proofThreshold = signal('');
  protected readonly noteThreshold = signal('');
  // The from-date typed for each dated setting, by key; blank for the sheet's today.
  protected readonly from = signal<Readonly<Partial<Record<DatedKey, string>>>>({});
  // Which settings were typed into since the last save; a save stores only those. Erasing in a field counts, so
  // clearing one that already shows blank (as the clerk limit, for none) still saves it.
  protected limitChanged = false;
  protected floorChanged = false;
  protected termsChanged = false;
  protected readonly gradeTermsChanged = new Set<string>();
  protected latePctChanged = false;
  protected policyDateChanged = false;
  protected periodNumberingFromChanged = false;
  protected graceDaysChanged = false;
  protected capChanged = false;
  protected proofThresholdChanged = false;
  protected noteThresholdChanged = false;
  protected readonly permsChanged = new Set<string>();
  protected readonly user = signal('');
  protected readonly role = signal('');
  protected readonly settingsError = signal<string | null>(null);
  protected readonly roleError = signal<string | null>(null);
  // A settings submit in flight, and whether another came before it answered: that one runs once it has.
  private savingSettings = false;
  private resubmit = false;
  // A role submit in flight: a second one before it answers is ignored.
  private submitting = false;

  constructor() {
    // Re-read the settings from the store each time a sheet is loaded.
    effect(() => {
      const id = this.session.sheetId();
      this.session.loads();
      untracked(() => {
        this.settingsError.set(null);
        this.roleError.set(null);
        if (id) this.store.load(id).catch(() => this.settingsError.set('Could not load the settings.'));
      });
    });
  }

  protected value(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  protected setGradeTermsField(grade: string, v: string): void {
    this.gradeTerms.update((t) => ({ ...t, [grade]: v }));
    this.gradeTermsChanged.add(grade);
    this.settingsError.set(null);
  }

  protected setPermField(action: string, v: string): void {
    this.perms.update((p) => ({ ...p, [action]: v }));
    this.permsChanged.add(action);
    this.settingsError.set(null);
  }

  protected gradeKey(grade: string): DatedKey {
    return `terms-${grade}`;
  }

  // A from-date typed for a setting whose value field holds a value saves it from that date.
  protected setFrom(key: DatedKey, v: string): void {
    this.from.update((f) => ({ ...f, [key]: v }));
    this.settingsError.set(null);
    switch (key) {
      case 'clerk-limit':
        this.limitChanged ||= this.limit().trim() !== '';
        break;
      case 'floor':
        this.floorChanged ||= this.floor().trim() !== '';
        break;
      case 'terms':
        this.termsChanged ||= this.terms().trim() !== '';
        break;
      case 'late-pct':
        this.latePctChanged ||= this.latePct().trim() !== '';
        break;
      case 'cap':
        this.capChanged ||= this.cap().trim() !== '';
        break;
      case 'proof-threshold':
        this.proofThresholdChanged ||= this.proofThreshold().trim() !== '';
        break;
      case 'note-threshold':
        this.noteThresholdChanged ||= this.noteThreshold().trim() !== '';
        break;
      default:
        if ((this.gradeTerms()[key.slice('terms-'.length)] ?? '').trim() !== '') {
          this.gradeTermsChanged.add(key.slice('terms-'.length));
        }
    }
  }

  /** The from-date typed for the key, or null when blank (the sheet's today). */
  private fromOf(key: DatedKey): string | null {
    return (this.from()[key] ?? '').trim() || null;
  }

  protected async saveSettings(e: Event): Promise<void> {
    e.preventDefault();
    if (this.savingSettings) {
      // saved once the one in flight answers, so a value typed meanwhile is not lost
      this.resubmit = true;
      return;
    }
    this.settingsError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const limit = this.limit().trim();
    if (this.limitChanged && limit && !LIMIT.test(limit)) {
      this.settingsError.set('Enter a clerk limit of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    const floor = this.floor().trim();
    if (this.floorChanged && !LIMIT.test(floor)) {
      this.settingsError.set('Enter a floor of 0.00 or more, with up to two decimals.');
      return;
    }
    const terms = this.terms().trim();
    if (this.termsChanged && !DAYS.test(terms)) {
      this.settingsError.set('Enter terms as a whole number of days, 0 or more.');
      return;
    }
    const gradeTerms = new Map<string, string>();
    for (const g of this.gradeTermsChanged) gradeTerms.set(g, (this.gradeTerms()[g] ?? '').trim());
    for (const [g, t] of gradeTerms) {
      if (!DAYS.test(t)) {
        this.settingsError.set(`Enter terms for grade ${g} as a whole number of days, 0 or more.`);
        return;
      }
    }
    const latePct = this.latePct().trim();
    if (this.latePctChanged && !PCT.test(latePct)) {
      this.settingsError.set('Enter a late percent of 0.00 or more, with up to two decimals.');
      return;
    }
    const policyDate = this.policyDate().trim();
    if (this.policyDateChanged && policyDate && !DATE.test(policyDate)) {
      this.settingsError.set('Enter a policy date, or leave it blank for the sheet\'s today.');
      return;
    }
    const periodNumberingFrom = this.periodNumberingFrom().trim();
    if (this.periodNumberingFromChanged && periodNumberingFrom && !DATE.test(periodNumberingFrom)) {
      this.settingsError.set('Enter a period numbering from date, or leave it blank for off.');
      return;
    }
    const graceDays = this.graceDays().trim();
    if (this.graceDaysChanged && graceDays && !DAYS.test(graceDays)) {
      this.settingsError.set('Enter grace days as a whole number of days, 0 or more, or leave it blank for off.');
      return;
    }
    const cap = this.cap().trim();
    if (this.capChanged && cap && !LIMIT.test(cap)) {
      this.settingsError.set('Enter a cap of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    const proofThreshold = this.proofThreshold().trim();
    if (this.proofThresholdChanged && proofThreshold && !LIMIT.test(proofThreshold)) {
      this.settingsError.set('Enter a proof threshold of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    const noteThreshold = this.noteThreshold().trim();
    if (this.noteThresholdChanged && noteThreshold && !LIMIT.test(noteThreshold)) {
      this.settingsError.set('Enter a note threshold of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    const perms = new Map<string, string>();
    for (const a of this.permsChanged) {
      const typed = (this.perms()[a] ?? '').trim();
      if (typed && typed.split(',').some((r) => !PERMISSION_ROLES.includes(r.trim()))) {
        this.settingsError.set(`List the roles that may ${a} as clerk, steward or both, separated by commas, or leave it blank.`);
        return;
      }
      perms.set(a, normalRoles(typed));
    }
    const keys: DatedKey[] = [];
    if (this.limitChanged) keys.push('clerk-limit');
    if (this.floorChanged) keys.push('floor');
    if (this.termsChanged) keys.push('terms');
    for (const g of gradeTerms.keys()) keys.push(`terms-${g}`);
    if (this.latePctChanged) keys.push('late-pct');
    if (this.capChanged) keys.push('cap');
    if (this.proofThresholdChanged) keys.push('proof-threshold');
    if (this.noteThresholdChanged) keys.push('note-threshold');
    const from = new Map<DatedKey, string | null>();
    for (const k of keys) {
      const f = this.fromOf(k);
      if (f !== null && !DATE.test(f)) {
        this.settingsError.set('Enter a from-date, or leave it blank for the sheet\'s today.');
        return;
      }
      from.set(k, f);
    }
    this.savingSettings = true;
    try {
      if (this.limitChanged) {
        await this.store.setDated(sheet, 'clerk-limit', limit || null, from.get('clerk-limit') ?? null);
        // a value typed while it was saving stays to be saved
        this.limitChanged = this.limit().trim() !== limit;
      }
      if (this.floorChanged) {
        await this.store.setDated(sheet, 'floor', floor, from.get('floor') ?? null);
        this.floorChanged = this.floor().trim() !== floor;
      }
      if (this.termsChanged) {
        await this.store.setDated(sheet, 'terms', Number(terms), from.get('terms') ?? null);
        this.termsChanged = this.terms().trim() !== terms;
      }
      for (const [g, t] of gradeTerms) {
        await this.store.setDated(sheet, `terms-${g}`, Number(t), from.get(`terms-${g}`) ?? null);
        if ((this.gradeTerms()[g] ?? '').trim() === t) this.gradeTermsChanged.delete(g);
      }
      if (this.latePctChanged) {
        await this.store.setDated(sheet, 'late-pct', latePct, from.get('late-pct') ?? null);
        this.latePctChanged = this.latePct().trim() !== latePct;
      }
      if (this.policyDateChanged) {
        await this.store.setPolicyDate(sheet, policyDate || null);
        this.policyDateChanged = this.policyDate().trim() !== policyDate;
      }
      if (this.periodNumberingFromChanged) {
        await this.store.setPeriodNumberingFrom(sheet, periodNumberingFrom || null);
        this.periodNumberingFromChanged = this.periodNumberingFrom().trim() !== periodNumberingFrom;
      }
      if (this.graceDaysChanged) {
        await this.store.setGraceDays(sheet, graceDays ? Number(graceDays) : null);
        this.graceDaysChanged = this.graceDays().trim() !== graceDays;
      }
      if (this.capChanged) {
        await this.store.setDated(sheet, 'cap', cap || null, from.get('cap') ?? null);
        this.capChanged = this.cap().trim() !== cap;
      }
      if (this.proofThresholdChanged) {
        await this.store.setDated(sheet, 'proof-threshold', proofThreshold || null, from.get('proof-threshold') ?? null);
        this.proofThresholdChanged = this.proofThreshold().trim() !== proofThreshold;
      }
      if (this.noteThresholdChanged) {
        await this.store.setDated(sheet, 'note-threshold', noteThreshold || null, from.get('note-threshold') ?? null);
        this.noteThresholdChanged = this.noteThreshold().trim() !== noteThreshold;
      }
      for (const [a, roles] of perms) {
        await this.store.setPermission(sheet, a, roles || null);
        // roles typed while it was saving stay to be saved
        if (normalRoles(this.perms()[a] ?? '') === roles) this.permsChanged.delete(a);
      }
    } catch {
      this.resubmit = false;
      this.settingsError.set('Could not save the settings.');
    } finally {
      this.savingSettings = false;
    }
    if (this.resubmit) {
      this.resubmit = false;
      await this.saveSettings(e);
    }
  }

  protected async saveRole(e: Event): Promise<void> {
    e.preventDefault();
    if (this.submitting) return;
    this.roleError.set(null);
    const sheet = this.session.sheetId();
    if (!sheet) return;
    const user = this.user().trim();
    const role = this.role().trim();
    if (!user) {
      this.roleError.set('Enter a user name.');
      return;
    }
    if (!ROLES.includes(role)) {
      this.roleError.set('Choose a role of clerk, steward or viewer.');
      return;
    }
    this.submitting = true;
    try {
      await this.store.setRole(sheet, user, role);
    } catch {
      this.roleError.set('Could not set that role.');
    } finally {
      this.submitting = false;
    }
  }
}
