import { HttpClient } from '@angular/common/http';
import { Component, Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { GRADES, RateStore } from '../rates/rates';

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
  // The default date of a new claim in place of the sheet's today; null when blank: off.
  entryDate: string | null;
  // The days after its to-date an open period is left open once the sheet's today passes it; null when blank: off.
  graceDays: number | null;
  cap: string | null;
  // The pot shared among the members of each balanced pool by their shares, two decimals; null when blank: none.
  pot: string | null;
  // The due-total above which a person is alerted, two decimals; null when blank: off.
  alertThreshold: string | null;
  // The id of the parent sheet, whose rates fill in for a grade with none in force on a date here; null for none.
  parent: string | null;
  // The month (1 to 12) the sheet's fiscal years start in.
  fiscalStartMonth: number;
  // Every value recorded for the dated settings, each from its from-date.
  dated: DatedSetting[];
  // The permission matrix as set, by action: the roles that may take it, comma separated; an action not listed keeps
  // the standing rule.
  permissions: Record<string, string>;
}

/**
 * One value recorded for a dated setting, in force from its from-date until the next one recorded for its key:
 * clerk-limit, floor, terms, terms-<grade>, grace-<grade>, late-pct, cap, proof-threshold, proof-threshold-<grade> or
 * note-threshold, review-threshold or rounding. The value is null for none (a blank clerk limit, grace, cap, proof
 * threshold, note threshold, review threshold or rounding mode).
 */
interface DatedSetting {
  key: string;
  from: string;
  value: string | null;
}

/** A dated setting's key as the form and its fields name it. */
export type DatedKey = 'clerk-limit' | 'floor' | 'terms' | 'late-pct' | 'cap' | 'proof-threshold' | 'note-threshold' | 'review-threshold' | 'rounding' | `terms-${string}` | `grace-${string}` | `proof-threshold-${string}`;

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
/** The rounding modes a sheet can have in force; blank for none (claims round by the policy date). */
export const ROUNDING_MODES = ['half-up', 'half-even'];
/** How a claim's live figures round: half up or half to even. */
export type RoundingModeName = 'half-up' | 'half-even';
/** A month number, 1 to 12. */
const MONTH = /^(0?[1-9]|1[0-2])$/;
/** A sheet id: letters, digits, dots, dashes and underscores. */
const SHEET_ID = /^[A-Za-z0-9_.-]{1,100}$/;

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
  /** The review threshold in force on the sheet's today; null for none. */
  readonly reviewThreshold = computed(() => valueOn(this.dated(), 'review-threshold', this.session.sheetToday()) ?? null);
  /** The rounding mode in force on a date; null for none (blank, or none recorded): the policy date decides. */
  readonly rounding = computed(() => {
    const dated = this.dated();
    return (date: string): RoundingModeName | null => (valueOn(dated, 'rounding', date) as RoundingModeName | null) ?? null;
  });
  /** The rounding mode in force on the sheet's today; null for none. */
  readonly roundingToday = computed(() => {
    const today = this.session.sheetToday();
    return today === null ? null : this.rounding()(today);
  });
  /** The floor, the terms and the late percent in force on the sheet's today. */
  readonly floorToday = computed(() => this.floor()(this.session.sheetToday()));
  readonly termsToday = computed(() => this.termsFor('', this.session.sheetToday()));
  readonly latePctToday = computed(() => this.latePct()(this.session.sheetToday()));
  /** The policy date as set; null when blank. */
  readonly policyDate = signal<string | null>(null);
  /** The period numbering from as set; null when blank (off). */
  readonly periodNumberingFrom = signal<string | null>(null);
  /** The entry date as set; null when blank (off). */
  readonly entryDate = signal<string | null>(null);
  /** The grace days as set; null when blank (off). */
  readonly graceDays = signal<number | null>(null);
  /** The pot as set; null when blank (none). */
  readonly pot = signal<string | null>(null);
  /** The alert threshold as set; null when blank (off). */
  readonly alertThreshold = signal<string | null>(null);
  /** The parent sheet's id; null when blank (none). */
  readonly parent = signal<string | null>(null);
  /** The month (1 to 12) the sheet's fiscal years start in. */
  readonly fiscalStartMonth = signal(1);
  /** The permission matrix as set, by action; an action not listed keeps the standing rule. */
  readonly permissions = signal<Readonly<Record<string, string>>>({});
  /**
   * What an overdue claim's late charge follows: the sheet's today, the grace days a grade has in force on a claim's
   * date and the late percent in force on each 30-day boundary past its due-by plus that grace; and the policy date in force (the sheet's today when blank) and the rounding mode in force on a date, which decide how live figures round.
   */
  readonly lateness = computed(() => ({
    today: this.session.sheetToday(),
    latePct: this.latePct(),
    policyDate: this.policyDate() ?? this.session.sheetToday(),
    rounding: this.rounding(),
    grace: (grade: string | null, date: string) => this.graceFor(grade, date),
  }));

  /** Re-read the sheet's settings from the store. */
  async load(sheet: string): Promise<void> {
    this.roles.set([]);
    this.dated.set([]);
    this.policyDate.set(null);
    this.periodNumberingFrom.set(null);
    this.entryDate.set(null);
    this.graceDays.set(null);
    this.pot.set(null);
    this.alertThreshold.set(null);
    this.parent.set(null);
    this.fiscalStartMonth.set(1);
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
    this.entryDate.set(s.entryDate);
    this.graceDays.set(s.graceDays);
    this.pot.set(s.pot ?? null);
    this.alertThreshold.set(s.alertThreshold ?? null);
    this.parent.set(s.parent ?? null);
    this.fiscalStartMonth.set(s.fiscalStartMonth ?? 1);
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
    const path = key.startsWith('terms-')
      ? `grade-terms/${encodeURIComponent(key.slice('terms-'.length))}`
      : key.startsWith('grace-')
        ? `grade-grace/${encodeURIComponent(key.slice('grace-'.length))}`
        : key.startsWith('proof-threshold-')
          ? `grade-proof-threshold/${encodeURIComponent(key.slice('proof-threshold-'.length))}`
          : key;
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

  /** The grace days the grade has in force on the date; 0 with none (blank, or none recorded). */
  graceFor(grade: string | null, date: string | null): number {
    const grace = grade ? valueOn(this.dated(), `grace-${grade}`, date) : undefined;
    return grace == null ? 0 : Number(grace);
  }

  /** The grace days recorded for the grade in force on the date, as text; blank with none. */
  graceShown(grade: string, date: string | null): string {
    return valueOn(this.dated(), `grace-${grade}`, date) ?? '';
  }

  /** The proof threshold recorded for the grade in force on the date, as text; blank with none. */
  proofThresholdShown(grade: string, date: string | null): string {
    return valueOn(this.dated(), `proof-threshold-${grade}`, date) ?? '';
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

  async setEntryDate(sheet: string, value: string | null): Promise<void> {
    const s = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/entry-date`, { value }),
    );
    if (this.session.sheetId() === sheet) this.show(s);
  }

  async setGraceDays(sheet: string, value: number | null): Promise<void> {
    const s = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/grace-days`, { value }),
    );
    if (this.session.sheetId() === sheet) this.show(s);
  }

  async setParent(sheet: string, value: string | null): Promise<void> {
    const s = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/parent`, { value }),
    );
    if (this.session.sheetId() === sheet) this.show(s);
  }

  async setFiscalStartMonth(sheet: string, value: number): Promise<void> {
    const s = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/fiscal-start-month`, { value }),
    );
    if (this.session.sheetId() === sheet) this.show(s);
  }

  async setPot(sheet: string, value: string | null): Promise<void> {
    const s = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/pot`, { value }),
    );
    if (this.session.sheetId() === sheet) this.show(s);
  }

  async setAlertThreshold(sheet: string, value: string | null): Promise<void> {
    const s = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/alert-threshold`, { value }),
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
        @for (g of grades; track g) {
          <label [for]="'setting-grace-' + g">Grace days for grade {{ g }} (days past its due-by before a claim is overdue; blank for none)</label>
          <input
            [id]="'setting-grace-' + g"
            [attr.data-testid]="'setting-grace-' + g"
            inputmode="numeric"
            [value]="gradeGrace()[g] ?? ''"
            (input)="setGradeGraceField(g, value($event))"
            (keydown.delete)="gradeGraceChanged.add(g); settingsError.set(null)"
            (keydown.backspace)="gradeGraceChanged.add(g); settingsError.set(null)"
          />
          <label [for]="'setting-grace-' + g + '-from'">Grace days for grade {{ g }} from (blank for the sheet's today)</label>
          <input
            [id]="'setting-grace-' + g + '-from'"
            [attr.data-testid]="'setting-grace-' + g + '-from'"
            type="date"
            [value]="from()[graceKey(g)] ?? ''"
            (input)="setFrom(graceKey(g), value($event))"
            (change)="setFrom(graceKey(g), value($event))"
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
        <label for="setting-entry-date">Entry date (new claims with no date are dated on it instead of today; blank for off)</label>
        <input
          id="setting-entry-date"
          data-testid="setting-entry-date"
          type="date"
          [value]="entryDate()"
          (input)="entryDate.set(value($event)); entryDateChanged = true; settingsError.set(null)"
          (change)="entryDate.set(value($event)); entryDateChanged = true; settingsError.set(null)"
          (keydown.delete)="entryDateChanged = true; settingsError.set(null)"
          (keydown.backspace)="entryDateChanged = true; settingsError.set(null)"
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
        <label for="setting-pot">Pot (shared among the members of each balanced pool by their shares; blank for none)</label>
        <input
          id="setting-pot"
          data-testid="setting-pot"
          inputmode="decimal"
          [value]="pot()"
          (input)="pot.set(value($event)); potChanged = true; settingsError.set(null)"
          (keydown.delete)="potChanged = true; settingsError.set(null)"
          (keydown.backspace)="potChanged = true; settingsError.set(null)"
        />
        <label for="setting-alert-threshold">Alert threshold (a person whose due-total goes above it is alerted; blank for off)</label>
        <input
          id="setting-alert-threshold"
          data-testid="setting-alert-threshold"
          inputmode="decimal"
          [value]="alertThreshold()"
          (input)="alertThreshold.set(value($event)); alertThresholdChanged = true; settingsError.set(null)"
          (keydown.delete)="alertThresholdChanged = true; settingsError.set(null)"
          (keydown.backspace)="alertThresholdChanged = true; settingsError.set(null)"
        />
        <label for="setting-fiscal-start-month">Fiscal start month (1 to 12; fiscal years start on its first day)</label>
        <input
          id="setting-fiscal-start-month"
          data-testid="setting-fiscal-start-month"
          inputmode="numeric"
          [value]="fiscalStartMonth()"
          (input)="fiscalStartMonth.set(value($event)); fiscalStartMonthChanged = true; settingsError.set(null)"
          (keydown.delete)="fiscalStartMonthChanged = true; settingsError.set(null)"
          (keydown.backspace)="fiscalStartMonthChanged = true; settingsError.set(null)"
        />
        <label for="setting-parent">Parent sheet (its rates fill in for a grade with none in force here; blank for none)</label>
        <input
          id="setting-parent"
          data-testid="setting-parent"
          [value]="parent()"
          (input)="parent.set(value($event)); parentChanged = true; settingsError.set(null)"
          (keydown.delete)="parentChanged = true; settingsError.set(null)"
          (keydown.backspace)="parentChanged = true; settingsError.set(null)"
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
        @for (g of grades; track g) {
          <label [for]="'setting-proof-threshold-' + g">Proof threshold for grade {{ g }} (in place of the sheet's; blank for none)</label>
          <input
            [id]="'setting-proof-threshold-' + g"
            [attr.data-testid]="'setting-proof-threshold-' + g"
            inputmode="decimal"
            [value]="gradeProof()[g] ?? ''"
            (input)="setGradeProofField(g, value($event))"
            (keydown.delete)="gradeProofChanged.add(g); settingsError.set(null)"
            (keydown.backspace)="gradeProofChanged.add(g); settingsError.set(null)"
          />
          <label [for]="'setting-proof-threshold-' + g + '-from'">Proof threshold for grade {{ g }} from (blank for the sheet's today)</label>
          <input
            [id]="'setting-proof-threshold-' + g + '-from'"
            [attr.data-testid]="'setting-proof-threshold-' + g + '-from'"
            type="date"
            [value]="from()[proofKey(g)] ?? ''"
            (input)="setFrom(proofKey(g), value($event))"
            (change)="setFrom(proofKey(g), value($event))"
          />
        }
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
        <label for="setting-review-threshold">Review threshold (a claim due above it goes to review when opened; blank for none)</label>
        <input
          id="setting-review-threshold"
          data-testid="setting-review-threshold"
          inputmode="decimal"
          [value]="reviewThreshold()"
          (input)="reviewThreshold.set(value($event)); reviewThresholdChanged = true; settingsError.set(null)"
          (keydown.delete)="reviewThresholdChanged = true; settingsError.set(null)"
          (keydown.backspace)="reviewThresholdChanged = true; settingsError.set(null)"
        />
        <label for="setting-review-threshold-from">Review threshold from (blank for the sheet's today)</label>
        <input
          id="setting-review-threshold-from"
          data-testid="setting-review-threshold-from"
          type="date"
          [value]="from()['review-threshold'] ?? ''"
          (input)="setFrom('review-threshold', value($event))"
          (change)="setFrom('review-threshold', value($event))"
        />
        <label for="setting-rounding">Rounding mode (half-up or half-even; blank for none: the policy date decides)</label>
        <input
          id="setting-rounding"
          data-testid="setting-rounding"
          [value]="rounding()"
          (input)="rounding.set(value($event)); roundingChanged = true; settingsError.set(null)"
          (keydown.delete)="roundingChanged = true; settingsError.set(null)"
          (keydown.backspace)="roundingChanged = true; settingsError.set(null)"
        />
        <label for="setting-rounding-from">Rounding mode from (blank for the sheet's today)</label>
        <input
          id="setting-rounding-from"
          data-testid="setting-rounding-from"
          type="date"
          [value]="from()['rounding'] ?? ''"
          (input)="setFrom('rounding', value($event))"
          (change)="setFrom('rounding', value($event))"
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
      @for (g of grades; track g) {
        <p>
          Grace days in force for grade {{ g }}:
          <strong [attr.data-testid]="'setting-grace-' + g + '-current'">{{ store.graceShown(g, session.sheetToday()) }}</strong>
        </p>
      }
      <p>Late percent in force: <strong data-testid="setting-late-pct-current">{{ store.latePctToday() }}</strong></p>
      <p>Policy date in force: <strong data-testid="setting-policy-date-current">{{ store.policyDate() ?? '' }}</strong></p>
      <p>
        Period numbering from:
        <strong data-testid="setting-period-numbering-from-current">{{ store.periodNumberingFrom() ?? '' }}</strong>
      </p>
      <p>Entry date: <strong data-testid="setting-entry-date-current">{{ store.entryDate() ?? '' }}</strong></p>
      <p>Grace days: <strong data-testid="setting-grace-days-current">{{ store.graceDays() ?? '' }}</strong></p>
      <p>Pot: <strong data-testid="setting-pot-current">{{ store.pot() ?? '' }}</strong></p>
      <p>Alert threshold: <strong data-testid="setting-alert-threshold-current">{{ store.alertThreshold() ?? '' }}</strong></p>
      <p>Fiscal start month: <strong data-testid="setting-fiscal-start-month-current">{{ store.fiscalStartMonth() }}</strong></p>
      <p>Parent sheet: <strong data-testid="setting-parent-current">{{ store.parent() ?? '' }}</strong></p>
      <p>Cap in force: <strong data-testid="setting-cap-current">{{ store.cap() ?? '' }}</strong></p>
      <p>
        Proof threshold in force:
        <strong data-testid="setting-proof-threshold-current">{{ store.proofThreshold() ?? '' }}</strong>
      </p>
      @for (g of grades; track g) {
        <p>
          Proof threshold in force for grade {{ g }}:
          <strong [attr.data-testid]="'setting-proof-threshold-' + g + '-current'">{{ store.proofThresholdShown(g, session.sheetToday()) }}</strong>
        </p>
      }
      <p>
        Note threshold in force:
        <strong data-testid="setting-note-threshold-current">{{ store.noteThreshold() ?? '' }}</strong>
      </p>
      <p>
        Review threshold in force:
        <strong data-testid="setting-review-threshold-current">{{ store.reviewThreshold() ?? '' }}</strong>
      </p>
      <p>Rounding mode in force: <strong data-testid="setting-rounding-current">{{ store.roundingToday() ?? '' }}</strong></p>
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
  private readonly rates = inject(RateStore);

  protected readonly roles = ROLES;
  protected readonly grades = GRADES;
  protected readonly permissionActions = PERMISSIONS;
  // The roles typed for each action of the permission matrix, by action.
  protected readonly perms = signal<Readonly<Record<string, string>>>({});
  protected readonly limit = signal('');
  protected readonly floor = signal('');
  protected readonly terms = signal('');
  protected readonly gradeTerms = signal<Readonly<Record<string, string>>>({});
  protected readonly gradeGrace = signal<Readonly<Record<string, string>>>({});
  protected readonly gradeProof = signal<Readonly<Record<string, string>>>({});
  protected readonly latePct = signal('');
  protected readonly policyDate = signal('');
  protected readonly periodNumberingFrom = signal('');
  protected readonly entryDate = signal('');
  protected readonly graceDays = signal('');
  protected readonly pot = signal('');
  protected readonly alertThreshold = signal('');
  protected readonly fiscalStartMonth = signal('');
  protected readonly parent = signal('');
  protected readonly cap = signal('');
  protected readonly proofThreshold = signal('');
  protected readonly noteThreshold = signal('');
  protected readonly reviewThreshold = signal('');
  protected readonly rounding = signal('');
  // The from-date typed for each dated setting, by key; blank for the sheet's today.
  protected readonly from = signal<Readonly<Partial<Record<DatedKey, string>>>>({});
  // Which settings were typed into since the last save; a save stores only those. Erasing in a field counts, so
  // clearing one that already shows blank (as the clerk limit, for none) still saves it.
  protected limitChanged = false;
  protected floorChanged = false;
  protected termsChanged = false;
  protected readonly gradeTermsChanged = new Set<string>();
  protected readonly gradeGraceChanged = new Set<string>();
  protected readonly gradeProofChanged = new Set<string>();
  protected latePctChanged = false;
  protected policyDateChanged = false;
  protected periodNumberingFromChanged = false;
  protected entryDateChanged = false;
  protected graceDaysChanged = false;
  protected potChanged = false;
  protected alertThresholdChanged = false;
  protected fiscalStartMonthChanged = false;
  protected parentChanged = false;
  protected capChanged = false;
  protected proofThresholdChanged = false;
  protected noteThresholdChanged = false;
  protected reviewThresholdChanged = false;
  protected roundingChanged = false;
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

  protected setGradeGraceField(grade: string, v: string): void {
    this.gradeGrace.update((t) => ({ ...t, [grade]: v }));
    this.gradeGraceChanged.add(grade);
    this.settingsError.set(null);
  }

  protected setGradeProofField(grade: string, v: string): void {
    this.gradeProof.update((t) => ({ ...t, [grade]: v }));
    this.gradeProofChanged.add(grade);
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

  protected graceKey(grade: string): DatedKey {
    return `grace-${grade}`;
  }

  protected proofKey(grade: string): DatedKey {
    return `proof-threshold-${grade}`;
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
      case 'review-threshold':
        this.reviewThresholdChanged ||= this.reviewThreshold().trim() !== '';
        break;
      case 'rounding':
        this.roundingChanged ||= this.rounding().trim() !== '';
        break;
      default:
        if (key.startsWith('proof-threshold-')) {
          if ((this.gradeProof()[key.slice('proof-threshold-'.length)] ?? '').trim() !== '') {
            this.gradeProofChanged.add(key.slice('proof-threshold-'.length));
          }
        } else if (key.startsWith('grace-')) {
          if ((this.gradeGrace()[key.slice('grace-'.length)] ?? '').trim() !== '') {
            this.gradeGraceChanged.add(key.slice('grace-'.length));
          }
        } else if ((this.gradeTerms()[key.slice('terms-'.length)] ?? '').trim() !== '') {
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
    const gradeGrace = new Map<string, string>();
    for (const g of this.gradeGraceChanged) gradeGrace.set(g, (this.gradeGrace()[g] ?? '').trim());
    for (const [g, t] of gradeGrace) {
      if (t && !DAYS.test(t)) {
        this.settingsError.set(`Enter grace days for grade ${g} as a whole number of days, 0 or more, or leave it blank for none.`);
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
    const entryDate = this.entryDate().trim();
    if (this.entryDateChanged && entryDate && !DATE.test(entryDate)) {
      this.settingsError.set('Enter an entry date, or leave it blank for off.');
      return;
    }
    const graceDays = this.graceDays().trim();
    if (this.graceDaysChanged && graceDays && !DAYS.test(graceDays)) {
      this.settingsError.set('Enter grace days as a whole number of days, 0 or more, or leave it blank for off.');
      return;
    }
    const pot = this.pot().trim();
    if (this.potChanged && pot && !LIMIT.test(pot)) {
      this.settingsError.set('Enter a pot of 0.00 or more, with up to two decimals, or leave it blank for none.');
      return;
    }
    const alertThreshold = this.alertThreshold().trim();
    if (this.alertThresholdChanged && alertThreshold && !LIMIT.test(alertThreshold)) {
      this.settingsError.set('Enter an alert threshold of 0.00 or more, with up to two decimals, or leave it blank for off.');
      return;
    }
    const fiscalStartMonth = this.fiscalStartMonth().trim();
    if (this.fiscalStartMonthChanged && !MONTH.test(fiscalStartMonth)) {
      this.settingsError.set('Enter a fiscal start month from 1 to 12.');
      return;
    }
    const parent = this.parent().trim();
    if (this.parentChanged && parent && !SHEET_ID.test(parent)) {
      this.settingsError.set('Enter a parent sheet id of letters, digits, dots, dashes or underscores, or leave it blank for none.');
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
    const gradeProof = new Map<string, string>();
    for (const g of this.gradeProofChanged) gradeProof.set(g, (this.gradeProof()[g] ?? '').trim());
    for (const [g, t] of gradeProof) {
      if (t && !LIMIT.test(t)) {
        this.settingsError.set(`Enter a proof threshold for grade ${g} of 0.00 or more, with up to two decimals, or leave it blank for none.`);
        return;
      }
    }
    const noteThreshold = this.noteThreshold().trim();
    if (this.noteThresholdChanged && noteThreshold && !LIMIT.test(noteThreshold)) {
      this.settingsError.set('Enter a note threshold of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    const reviewThreshold = this.reviewThreshold().trim();
    if (this.reviewThresholdChanged && reviewThreshold && !LIMIT.test(reviewThreshold)) {
      this.settingsError.set('Enter a review threshold of 0.00 or more, with up to two decimals, or leave it blank.');
      return;
    }
    const rounding = this.rounding().trim();
    if (this.roundingChanged && rounding && !ROUNDING_MODES.includes(rounding)) {
      this.settingsError.set('Enter a rounding mode of half-up or half-even, or leave it blank for none.');
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
    for (const g of gradeGrace.keys()) keys.push(`grace-${g}`);
    if (this.latePctChanged) keys.push('late-pct');
    if (this.capChanged) keys.push('cap');
    if (this.proofThresholdChanged) keys.push('proof-threshold');
    for (const g of gradeProof.keys()) keys.push(`proof-threshold-${g}`);
    if (this.noteThresholdChanged) keys.push('note-threshold');
    if (this.reviewThresholdChanged) keys.push('review-threshold');
    if (this.roundingChanged) keys.push('rounding');
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
      for (const [g, t] of gradeGrace) {
        await this.store.setDated(sheet, `grace-${g}`, t ? Number(t) : null, from.get(`grace-${g}`) ?? null);
        if ((this.gradeGrace()[g] ?? '').trim() === t) this.gradeGraceChanged.delete(g);
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
      if (this.entryDateChanged) {
        await this.store.setEntryDate(sheet, entryDate || null);
        this.entryDateChanged = this.entryDate().trim() !== entryDate;
      }
      if (this.graceDaysChanged) {
        await this.store.setGraceDays(sheet, graceDays ? Number(graceDays) : null);
        this.graceDaysChanged = this.graceDays().trim() !== graceDays;
      }
      if (this.potChanged) {
        await this.store.setPot(sheet, pot || null);
        this.potChanged = this.pot().trim() !== pot;
      }
      if (this.alertThresholdChanged) {
        await this.store.setAlertThreshold(sheet, alertThreshold || null);
        this.alertThresholdChanged = this.alertThreshold().trim() !== alertThreshold;
      }
      if (this.fiscalStartMonthChanged) {
        await this.store.setFiscalStartMonth(sheet, Number(fiscalStartMonth));
        this.fiscalStartMonthChanged = this.fiscalStartMonth().trim() !== fiscalStartMonth;
      }
      if (this.parentChanged) {
        await this.store.setParent(sheet, parent || null);
        this.parentChanged = this.parent().trim() !== parent;
        // the rates that apply follow the parent's
        await this.rates.load(sheet);
      }
      if (this.capChanged) {
        await this.store.setDated(sheet, 'cap', cap || null, from.get('cap') ?? null);
        this.capChanged = this.cap().trim() !== cap;
      }
      if (this.proofThresholdChanged) {
        await this.store.setDated(sheet, 'proof-threshold', proofThreshold || null, from.get('proof-threshold') ?? null);
        this.proofThresholdChanged = this.proofThreshold().trim() !== proofThreshold;
      }
      for (const [g, t] of gradeProof) {
        await this.store.setDated(sheet, `proof-threshold-${g}`, t || null, from.get(`proof-threshold-${g}`) ?? null);
        if ((this.gradeProof()[g] ?? '').trim() === t) this.gradeProofChanged.delete(g);
      }
      if (this.noteThresholdChanged) {
        await this.store.setDated(sheet, 'note-threshold', noteThreshold || null, from.get('note-threshold') ?? null);
        this.noteThresholdChanged = this.noteThreshold().trim() !== noteThreshold;
      }
      if (this.reviewThresholdChanged) {
        await this.store.setDated(sheet, 'review-threshold', reviewThreshold || null, from.get('review-threshold') ?? null);
        this.reviewThresholdChanged = this.reviewThreshold().trim() !== reviewThreshold;
      }
      if (this.roundingChanged) {
        await this.store.setDated(sheet, 'rounding', rounding || null, from.get('rounding') ?? null);
        this.roundingChanged = this.rounding().trim() !== rounding;
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
