import { HttpClient } from '@angular/common/http';
import { Component, Injectable, computed, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { AMOUNT } from '../claims/claims';
import { GRADES, RateStore } from '../rates/rates';

export interface RoleView {
  user: string;
  /** clerk or steward; a user name not listed is a clerk. */
  role: string;
}

/** A steward's delegation of the steward role to a user, from-date to to-date inclusive (yyyy-mm-dd). */
export interface DelegationView {
  user: string;
  from: string;
  to: string;
  /** The steward who delegated. */
  by: string;
}

/** A value recorded for a dated setting, in force from its from-date (yyyy-mm-dd) until the next
 *  one's: two decimals, whole days for terms, null for no clerk limit, cap or proof threshold. */
export interface DatedValue {
  from: string;
  value: string | null;
}

interface SettingsView {
  roles: RoleView[];
  /** The delegations of the steward role, in the order made. */
  delegations: DelegationView[];
  /** The clerk limit (two decimals); null when there is none. */
  clerkLimit: string | null;
  /** The floor (two decimals): the least a claim's live due can be. */
  floor: string;
  /** The cap (two decimals): the most a person's due-total can be; null when there is none. */
  cap: string | null;
  /** The terms in whole days: a claim is due by the business day that many after its date. */
  terms: number;
  /** The late percent (two decimals): an overdue claim's due gains it of its figure for every full
   *  30 days it is overdue. */
  latePct: string;
  /** The policy date (yyyy-mm-dd): a claim dated before it rounds half up, one on or after it half to
   *  even; blank for the sheet's today. */
  policyDate: string;
  /** The period numbering from (yyyy-mm-dd): while the sheet's today is on or after it, a claim opened
   *  is numbered within its period; blank for off. */
  periodNumberingFrom: string;
  /** The grace days: when the sheet's today is changed, every open period whose to-date plus them is
   *  before the new today is closed; null for off. */
  graceDays: number | null;
  /** The escalation gap: reminding a claim whose previous reminder was at least this many days before
   *  the sheet's today escalates it; null for off. */
  escalateAfter: number | null;
  /** The proof kinds, comma separated: a claim above the proof threshold needs a proof of each before
   *  it can be settled or approved; blank for none. */
  proofKinds: string;
  /** The default grade, A, B or C: a person added by a quick-add, or by an import line with a blank
   *  grade, is given it; blank for A. */
  defaultGrade: string;
  /** The review SLA: a REVIEW claim whose review day plus this many days is before the sheet's
   *  today is late for review; null for none. */
  reviewSla: number | null;
  /** The fiscal year start month, 1 to 12: a fiscal year runs twelve months from the first of it. */
  fiscalStartMonth: number;
  /** The pot (two decimals): each member of a balanced pool shows a cut of it; null for none. */
  pot: string | null;
  /** The alert threshold (two decimals): a person whose due-total crosses above it is alerted; null
   *  for off. */
  alertThreshold: string | null;
  /** The parent sheet's id: a grade with no rate in force on a date takes the parent's (then its
   *  parent's, up to three levels up); blank for none. */
  parent: string;
  /** The entry date (yyyy-mm-dd): while it is set, a new claim given no date is dated it rather than
   *  the sheet's today; blank for off. */
  entryDate: string;
  /** The grades' own terms in whole days, by grade; a grade not listed takes the sheet's terms. */
  gradeTerms: Record<string, number>;
  /** Every value recorded for the dated settings, by key (floor, late-pct, terms, terms-<grade>,
   *  clerk-limit, cap, proof-threshold, note-threshold, review-threshold, rounding), each list by from-date. */
  dated: Record<string, DatedValue[]>;
  /** The permission matrix: the roles each action allows, comma separated, by action; blank keeps
   *  the standing rule. */
  permissions: Record<string, string>;
}

/** The value recorded that is in force on a date: the one with the latest from-date on or before
 *  it; undefined before every from-date (or with no date), when the starting value applies. */
function valueOn(recorded: DatedValue[] | undefined, date: string | null): string | null | undefined {
  if (date === null || recorded === undefined) return undefined;
  let found: DatedValue | undefined;
  for (const v of recorded) if (v.from <= date && (found === undefined || v.from > found.from)) found = v;
  return found?.value;
}

/** The floor a sheet starts with. */
const FLOOR = '1.00';

/** The terms a sheet starts with, in days. */
const TERMS = 14;

/** The rounding modes a sheet may set: how a claim's live figures round. */
const ROUNDING_MODES = ['half-up', 'half-even'];

/** The late percent a sheet starts with. */
const LATE_PCT = '0.00';

/** Terms of 0 or more whole days. */
const DAYS = /^\d{1,5}$/;
/** A month of the year, 1 to 12. */
const MONTH = /^(?:0?[1-9]|1[0-2])$/;

/** A date, yyyy-mm-dd. */
const DATE = /^\d{4}-\d{2}-\d{2}$/;

const ROLES = ['clerk', 'steward', 'viewer'];

/** The actions the permission matrix governs, each with the roles its standing rule allows. */
const PERMISSIONS: { action: string; label: string; standing: string }[] = [
  { action: 'open', label: 'Open', standing: 'clerk and steward' },
  { action: 'settle', label: 'Settle', standing: 'clerk and steward' },
  { action: 'void', label: 'Void', standing: 'clerk and steward' },
  { action: 'reassign', label: 'Reassign', standing: 'steward' },
  { action: 'close', label: 'Close a period', standing: 'clerk and steward' },
  { action: 'reopen', label: 'Reopen a period', standing: 'steward' },
];

/** Whether a permission entry is blank or a comma separated list of roles. */
function rolesList(typed: string): boolean {
  return typed === '' || typed.split(',').every((r) => ROLES.includes(r.trim()));
}

// The active sheet's settings, re-read whenever a sheet is loaded.
@Injectable({ providedIn: 'root' })
export class SettingStore {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);
  private readonly rates = inject(RateStore);

  readonly roles = signal<RoleView[]>([]);
  readonly delegations = signal<DelegationView[]>([]);
  /** Every value recorded for the dated settings, by key. */
  readonly dated = signal<Record<string, DatedValue[]>>({});
  /** The clerk limit in force on the sheet's today; null when there is none. */
  readonly clerkLimit = computed(() => this.onToday('clerk-limit') ?? null);
  /** The floor in force on the sheet's today. */
  readonly floor = computed(() => this.onToday('floor') ?? FLOOR);
  /** The cap in force on the sheet's today; null when there is none. */
  readonly cap = computed(() => this.onToday('cap') ?? null);
  /** The proof threshold in force on the sheet's today; null when there is none. */
  readonly proofThreshold = computed(() => this.onToday('proof-threshold') ?? null);
  /** The note threshold in force on the sheet's today; null when there is none. */
  readonly noteThreshold = computed(() => this.onToday('note-threshold') ?? null);
  /** The review threshold in force on the sheet's today; null when there is none. */
  readonly reviewThreshold = computed(() => this.onToday('review-threshold') ?? null);
  /** The rounding mode in force on the sheet's today (half-up or half-even); null when there is none
   *  (the policy date decides). */
  readonly rounding = computed(() => this.onToday('rounding') ?? null);
  /** The terms in force on the sheet's today. */
  readonly terms = computed(() => Number(this.onToday('terms') ?? TERMS));
  /** The late percent in force on the sheet's today. */
  readonly latePct = computed(() => this.onToday('late-pct') ?? LATE_PCT);
  /** The grades' own terms in force on the sheet's today, by grade; a grade not listed takes the
   *  sheet's terms. */
  readonly gradeTerms = computed(() => {
    const byGrade: Record<string, number> = {};
    for (const g of GRADES) {
      const days = this.onToday(`terms-${g}`);
      if (days !== undefined && days !== null) byGrade[g] = Number(days);
    }
    return byGrade;
  });
  /** The floor in force on a date: the least the live due of a claim of that date can be. */
  readonly floorOn = computed(() => {
    const recorded = this.dated()['floor'];
    return (date: string) => valueOn(recorded, date) ?? FLOOR;
  });
  /** The rounding mode in force on a date (half-up or half-even): how a claim of that date rounds its
   *  live figures; null when there is none (the policy date decides). */
  readonly roundingOn = computed(() => {
    const recorded = this.dated()['rounding'];
    return (date: string) => valueOn(recorded, date) ?? null;
  });
  /** The late percent in force on a date: what an overdue claim of that date gains. */
  readonly latePctOn = computed(() => {
    const recorded = this.dated()['late-pct'];
    return (date: string) => valueOn(recorded, date) ?? LATE_PCT;
  });
  /** The grace days in force on a date for a grade: a claim of that grade and date is overdue only once
   *  its due-by plus them is before the sheet's today; 0 when the grade has none then (blank). */
  readonly graceOn = computed(() => {
    const dated = this.dated();
    return (grade: string | null, date: string) => {
      if (grade === null) return 0;
      const days = valueOn(dated[`grace-${grade}`], date);
      return days === undefined || days === null ? 0 : Number(days);
    };
  });
  readonly policyDate = signal('');
  readonly periodNumberingFrom = signal('');
  readonly entryDate = signal('');
  /** The grace days as text: whole days, blank for off. */
  readonly graceDays = signal('');
  /** The escalation gap as text: whole days, blank for off. */
  readonly escalateAfter = signal('');
  /** The proof kinds, comma separated; blank for none. */
  readonly proofKinds = signal('');
  readonly defaultGrade = signal('');
  /** The review SLA as text: whole days, blank for none. */
  readonly reviewSla = signal('');
  /** The fiscal year start month, 1 to 12; 1 unless set. */
  readonly fiscalStartMonth = signal(1);
  /** The pot as text: two decimals, blank for none. */
  readonly pot = signal('');
  /** The alert threshold as text: two decimals, blank for off. */
  readonly alertThreshold = signal('');
  /** The parent sheet's id; blank for none. */
  readonly parent = signal('');
  /** The permission matrix as set, by action; blank keeps the standing rule. */
  readonly permissions = signal<Record<string, string>>({});

  private onToday(key: string): string | null | undefined {
    return valueOn(this.dated()[key], this.session.today());
  }

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      const load = this.session.loads();
      this.roles.set([]);
      this.delegations.set([]);
      this.dated.set({});
      this.policyDate.set('');
      this.periodNumberingFrom.set('');
      this.entryDate.set('');
      this.graceDays.set('');
      this.escalateAfter.set('');
      this.proofKinds.set('');
      this.defaultGrade.set('');
      this.reviewSla.set('');
      this.fiscalStartMonth.set(1);
      this.pot.set('');
      this.alertThreshold.set('');
      this.parent.set('');
      this.permissions.set({});
      if (sheet) void this.read(sheet, load);
    });
  }

  /** Assign a user name a role on the active sheet; the server refuses a role it does not know. */
  async setRole(user: string, role: string): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<RoleView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/roles`, { user, role }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.roles.update((list) =>
      [...list.filter((r) => r.user !== saved.user), saved].sort((a, b) => a.user.localeCompare(b.user)),
    );
  }

  /** Delegate the steward role to a user on the active sheet; blank dates take their defaults (the
   *  sheet's today, the from-date). The server refuses anyone but a steward, and a to-date before the
   *  from-date. */
  async delegate(user: string, from: string | null, to: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/delegations`, { user, from, to }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Record the active sheet's clerk limit from a date (null for the sheet's today); null records none. */
  async setClerkLimit(value: string | null, from: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/clerk-limit`, { value, from }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Record the active sheet's floor from a date (null for the sheet's today). */
  async setFloor(value: string, from: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/floor`, { value, from }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Record the active sheet's cap from a date (null for the sheet's today); null records none. */
  async setCap(value: string | null, from: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/cap`, { value, from }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Record the active sheet's proof threshold from a date (null for the sheet's today); null records none. */
  async setProofThreshold(value: string | null, from: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/proof-threshold`, { value, from }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Record the active sheet's note threshold from a date (null for the sheet's today); null records none. */
  async setNoteThreshold(value: string | null, from: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/note-threshold`, { value, from }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Record the active sheet's review threshold from a date (null for the sheet's today); null records none. */
  async setReviewThreshold(value: string | null, from: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/review-threshold`, { value, from }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Record the active sheet's rounding mode from a date (null for the sheet's today); null records none.
   *  The server refuses a mode other than half-up or half-even. */
  async setRounding(value: string | null, from: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/rounding`, { value, from }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Record the active sheet's terms from a date (null for the sheet's today). */
  async setTerms(value: number, from: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/terms`, { value, from }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** The terms in days a claim of the grade is added with: the grade's own, else the sheet's. */
  termsOf(grade: string): number {
    return this.gradeTerms()[grade] ?? this.terms();
  }

  /** A grade's proof threshold in force on the sheet's today; blank for none (the sheet's applies). */
  proofThresholdOf(grade: string): string {
    return this.onToday(`proof-threshold-${grade}`) ?? '';
  }

  /** Record a grade's proof threshold on the active sheet from a date (null for the sheet's today);
   *  null records none (blank). */
  async setGradeProofThreshold(grade: string, value: string | null, from: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(
        `/api/sheets/${encodeURIComponent(sheet)}/settings/proof-threshold/${encodeURIComponent(grade)}`,
        { value, from },
      ),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** A grade's grace days in force on the sheet's today as text; blank for none. */
  graceOf(grade: string): string {
    return this.onToday(`grace-${grade}`) ?? '';
  }

  /** Record a grade's grace days on the active sheet from a date (null for the sheet's today); null
   *  records none (blank). */
  async setGradeGrace(grade: string, value: number | null, from: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(
        `/api/sheets/${encodeURIComponent(sheet)}/settings/grace/${encodeURIComponent(grade)}`,
        { value, from },
      ),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Record a grade's own terms on the active sheet from a date (null for the sheet's today). */
  async setGradeTerms(grade: string, value: number, from: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(
        `/api/sheets/${encodeURIComponent(sheet)}/settings/terms/${encodeURIComponent(grade)}`,
        { value, from },
      ),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Record the active sheet's late percent from a date (null for the sheet's today). */
  async setLatePct(value: string, from: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/late-pct`, { value, from }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Change the active sheet's policy date; null leaves it blank (the sheet's today). */
  async setPolicyDate(value: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/policy-date`, { value }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Change the active sheet's period numbering from; null leaves it blank (off). */
  async setPeriodNumberingFrom(value: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/period-numbering-from`, { value }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Change the active sheet's entry date; null leaves it blank (off). */
  async setEntryDate(value: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/entry-date`, { value }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Change the active sheet's grace days; null leaves them blank (off). */
  async setGraceDays(value: number | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/grace-days`, { value }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Change the active sheet's escalation gap; null leaves it blank (off). */
  async setEscalateAfter(value: number | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/escalate-after`, { value }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Change the active sheet's proof kinds (comma separated, each receipt or photo); null leaves them
   *  blank (none). */
  async setProofKinds(value: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/proof-kinds`, { value }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Change the active sheet's default grade (A, B or C); null leaves it blank (A). */
  async setDefaultGrade(value: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/default-grade`, { value }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Change the active sheet's review SLA; null leaves it blank (none). */
  async setReviewSla(value: number | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/review-sla`, { value }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Change the active sheet's fiscal year start month (1 to 12); the server refuses any other. */
  async setFiscalStartMonth(value: number): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/fiscal-start-month`, { value }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Change the active sheet's pot; null leaves it blank (none). */
  async setPot(value: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/pot`, { value }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Change the active sheet's alert threshold; null leaves it blank (off). */
  async setAlertThreshold(value: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/alert-threshold`, { value }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  /** Change the active sheet's parent sheet; null leaves it blank (none). The sheet itself, a sheet
   *  that has it as an ancestor and a sheet never loaded are refused. */
  async setParent(value: string | null): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings/parent`, { value }),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
    await this.rates.readInherited();
  }

  /** Set the roles that may take an action on the active sheet; blank keeps the standing rule. */
  async setPermission(action: string, value: string): Promise<void> {
    const sheet = this.session.sheetId();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SettingsView>(
        `/api/sheets/${encodeURIComponent(sheet)}/settings/permissions/${encodeURIComponent(action)}`,
        { value },
      ),
    );
    if (this.session.sheetId() !== sheet) return;
    this.show(saved);
  }

  private async read(sheet: string, load: number): Promise<void> {
    const settings = await firstValueFrom(
      this.http.get<SettingsView>(`/api/sheets/${encodeURIComponent(sheet)}/settings`),
    );
    if (this.session.loads() !== load) return;
    this.show(settings);
  }

  private show(settings: SettingsView): void {
    this.roles.set(settings.roles);
    this.delegations.set(settings.delegations);
    this.dated.set(settings.dated);
    this.policyDate.set(settings.policyDate);
    this.periodNumberingFrom.set(settings.periodNumberingFrom);
    this.entryDate.set(settings.entryDate);
    this.graceDays.set(settings.graceDays === null ? '' : String(settings.graceDays));
    this.escalateAfter.set(settings.escalateAfter === null ? '' : String(settings.escalateAfter));
    this.proofKinds.set(settings.proofKinds);
    this.defaultGrade.set(settings.defaultGrade);
    this.reviewSla.set(settings.reviewSla === null ? '' : String(settings.reviewSla));
    this.fiscalStartMonth.set(settings.fiscalStartMonth);
    this.pot.set(settings.pot ?? '');
    this.alertThreshold.set(settings.alertThreshold ?? '');
    this.parent.set(settings.parent);
    this.permissions.set(settings.permissions);
  }
}

// The sheet's settings: the role given each user name (clerk, the default, steward, or viewer, who
// sees everything and can change nothing), the delegations of the steward role (a steward's, to a user
// who acts as a steward while the sheet's today is from its from-date to its to-date) and the
// clerk limit, the highest due a clerk settles outright (above it the claim goes PENDING), the floor, the least a claim's live due can be,
// the cap, the most a person's due-total can be, the proof threshold, above which a claim's due (by the threshold
// in force on its date) cannot be settled or approved until the claim has a proof (and one of each of the
// proof kinds, receipt or photo, while they are set), the note threshold, above
// which a claim's due (by the threshold in force on its date) cannot be opened while its note is empty,
// the review threshold, above which a claim's due (by the threshold in force on its date) sends it to REVIEW
// when opened, until a steward reviews it,
// the rounding mode (half-up or half-even), by which a claim whose date has one in force rounds its live
// figures (with none in force, the policy date decides),
// the terms, the days after its date a claim is due by (fixed when the claim is added), and each
// grade's own terms, which a claim of that grade takes instead, and the late percent, what an overdue claim's
// due gains of its figure for every full 30 days it is overdue, and the policy date, before which a
// claim's date makes its figures round half up rather than half to even, and the period numbering
// from, on or after which a claim opened is numbered within its period, and the grace days, past which
// (after its to-date) an open period is closed when the sheet's today is changed, and the entry date, which
// a new claim given no date (on the claim form, the quick-add bar, a pasted line or a correction) takes
// instead of the sheet's today while it is set, and the permission matrix,
// the roles that may open, settle, void, reassign, close and reopen (blank keeps the standing rule).
// The settings that carry a figure are dated: each is saved from the from-date typed beside it (blank
// for the sheet's today), keeping the values recorded before, and the value in force on the sheet's
// today is shown for each. Saving sends only the settings typed into since the last save; the clerk limit, the cap, the proof, note and review thresholds, the rounding mode, the policy date,
// the period numbering from, the entry date and the grace days count as typed into once focused, as a blank one (none, or the sheet's today) is a value of its own.
@Component({
  selector: 'app-settings',
  template: `
    <section aria-labelledby="settings-heading">
      <h2 id="settings-heading">Settings</h2>
      <form (submit)="saveRole($event)">
        <label for="role-form-user">User</label>
        <input
          id="role-form-user"
          data-testid="role-form-user"
          [value]="user()"
          (input)="user.set(value($event)); roleError.set(null)"
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
      </form>
      @if (roleError(); as message) {
        <p role="alert" data-testid="role-form-error">{{ message }}</p>
      }
      <table>
        <thead>
          <tr>
            <th scope="col">User</th>
            <th scope="col">Role</th>
          </tr>
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

      <h3 id="delegate-heading">Delegate the steward role</h3>
      <form aria-labelledby="delegate-heading" (submit)="saveDelegation($event)">
        <label for="delegate-user">User</label>
        <input
          id="delegate-user"
          data-testid="delegate-user"
          [value]="delegateUser()"
          (input)="delegateUser.set(value($event)); delegateError.set(null)"
        />
        <label for="delegate-from">From</label>
        <input
          id="delegate-from"
          data-testid="delegate-from"
          placeholder="yyyy-mm-dd"
          [value]="delegateFrom()"
          (input)="delegateFrom.set(value($event)); delegateError.set(null)"
        />
        <label for="delegate-to">To</label>
        <input
          id="delegate-to"
          data-testid="delegate-to"
          placeholder="yyyy-mm-dd"
          [value]="delegateTo()"
          (input)="delegateTo.set(value($event)); delegateError.set(null)"
        />
        <button type="submit" data-testid="delegate-submit">Delegate</button>
      </form>
      @if (delegateError(); as message) {
        <p role="alert" data-testid="delegate-error">{{ message }}</p>
      }
      <ul aria-labelledby="delegate-heading">
        @for (d of store.delegations(); track $index) {
          <li data-testid="delegate-line">{{ d.user }} acts as steward for {{ d.by }} from {{ d.from }} to {{ d.to }}</li>
        }
      </ul>

      <form (submit)="saveSettings($event)">
        <label for="setting-clerk-limit">Clerk limit</label>
        <input
          id="setting-clerk-limit"
          data-testid="setting-clerk-limit"
          inputmode="decimal"
          [value]="clerkLimit()"
          (focus)="clerkLimitTyped.set(true)"
          (input)="clerkLimit.set(value($event)); clerkLimitTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-clerk-limit-from">Clerk limit from</label>
        <input
          id="setting-clerk-limit-from"
          data-testid="setting-clerk-limit-from"
          placeholder="yyyy-mm-dd"
          [value]="clerkLimitFrom()"
          (input)="clerkLimitFrom.set(value($event)); settingsError.set(null)"
        />
        <label for="setting-floor">Floor</label>
        <input
          id="setting-floor"
          data-testid="setting-floor"
          inputmode="decimal"
          [value]="floor()"
          (input)="floor.set(value($event)); floorTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-floor-from">Floor from</label>
        <input
          id="setting-floor-from"
          data-testid="setting-floor-from"
          placeholder="yyyy-mm-dd"
          [value]="floorFrom()"
          (input)="floorFrom.set(value($event)); settingsError.set(null)"
        />
        <label for="setting-cap">Cap</label>
        <input
          id="setting-cap"
          data-testid="setting-cap"
          inputmode="decimal"
          [value]="cap()"
          (focus)="capTyped.set(true)"
          (input)="cap.set(value($event)); capTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-cap-from">Cap from</label>
        <input
          id="setting-cap-from"
          data-testid="setting-cap-from"
          placeholder="yyyy-mm-dd"
          [value]="capFrom()"
          (input)="capFrom.set(value($event)); settingsError.set(null)"
        />
        <label for="setting-proof-threshold">Proof threshold</label>
        <input
          id="setting-proof-threshold"
          data-testid="setting-proof-threshold"
          inputmode="decimal"
          [value]="proofThreshold()"
          (focus)="proofThresholdTyped.set(true)"
          (input)="proofThreshold.set(value($event)); proofThresholdTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-proof-threshold-from">Proof threshold from</label>
        <input
          id="setting-proof-threshold-from"
          data-testid="setting-proof-threshold-from"
          placeholder="yyyy-mm-dd"
          [value]="proofThresholdFrom()"
          (input)="proofThresholdFrom.set(value($event)); settingsError.set(null)"
        />
        <label for="setting-note-threshold">Note threshold</label>
        <input
          id="setting-note-threshold"
          data-testid="setting-note-threshold"
          inputmode="decimal"
          [value]="noteThreshold()"
          (focus)="noteThresholdTyped.set(true)"
          (input)="noteThreshold.set(value($event)); noteThresholdTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-note-threshold-from">Note threshold from</label>
        <input
          id="setting-note-threshold-from"
          data-testid="setting-note-threshold-from"
          placeholder="yyyy-mm-dd"
          [value]="noteThresholdFrom()"
          (input)="noteThresholdFrom.set(value($event)); settingsError.set(null)"
        />
        <label for="setting-review-threshold">Review threshold</label>
        <input
          id="setting-review-threshold"
          data-testid="setting-review-threshold"
          inputmode="decimal"
          [value]="reviewThreshold()"
          (focus)="reviewThresholdTyped.set(true)"
          (input)="reviewThreshold.set(value($event)); reviewThresholdTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-review-threshold-from">Review threshold from</label>
        <input
          id="setting-review-threshold-from"
          data-testid="setting-review-threshold-from"
          placeholder="yyyy-mm-dd"
          [value]="reviewThresholdFrom()"
          (input)="reviewThresholdFrom.set(value($event)); settingsError.set(null)"
        />
        <label for="setting-rounding">Rounding mode (half-up or half-even)</label>
        <input
          id="setting-rounding"
          data-testid="setting-rounding"
          [value]="rounding()"
          (focus)="roundingTyped.set(true)"
          (input)="rounding.set(value($event)); roundingTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-rounding-from">Rounding mode from</label>
        <input
          id="setting-rounding-from"
          data-testid="setting-rounding-from"
          placeholder="yyyy-mm-dd"
          [value]="roundingFrom()"
          (input)="roundingFrom.set(value($event)); settingsError.set(null)"
        />
        <label for="setting-terms">Terms (business days)</label>
        <input
          id="setting-terms"
          data-testid="setting-terms"
          inputmode="numeric"
          [value]="terms()"
          (input)="terms.set(value($event)); termsTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-terms-from">Terms from</label>
        <input
          id="setting-terms-from"
          data-testid="setting-terms-from"
          placeholder="yyyy-mm-dd"
          [value]="termsFrom()"
          (input)="termsFrom.set(value($event)); settingsError.set(null)"
        />
        @for (g of grades; track g) {
          <label [for]="'setting-terms-' + g">Terms for grade {{ g }} (business days)</label>
          <input
            [id]="'setting-terms-' + g"
            [attr.data-testid]="'setting-terms-' + g"
            inputmode="numeric"
            [value]="gradeTerms()[g] ?? ''"
            (input)="typeGradeTerms(g, value($event))"
          />
          <label [for]="'setting-terms-' + g + '-from'">Terms for grade {{ g }} from</label>
          <input
            [id]="'setting-terms-' + g + '-from'"
            [attr.data-testid]="'setting-terms-' + g + '-from'"
            placeholder="yyyy-mm-dd"
            [value]="gradeTermsFrom()[g] ?? ''"
            (input)="typeGradeTermsFrom(g, value($event))"
          />
        }
        @for (g of grades; track g) {
          <label [for]="'setting-proof-threshold-' + g">Proof threshold for grade {{ g }} (blank for the sheet's)</label>
          <input
            [id]="'setting-proof-threshold-' + g"
            [attr.data-testid]="'setting-proof-threshold-' + g"
            inputmode="decimal"
            [value]="gradeProofThreshold()[g] ?? ''"
            (focus)="typeGradeProofThreshold(g, gradeProofThreshold()[g] ?? '')"
            (input)="typeGradeProofThreshold(g, value($event))"
          />
          <label [for]="'setting-proof-threshold-' + g + '-from'">Proof threshold for grade {{ g }} from</label>
          <input
            [id]="'setting-proof-threshold-' + g + '-from'"
            [attr.data-testid]="'setting-proof-threshold-' + g + '-from'"
            placeholder="yyyy-mm-dd"
            [value]="gradeProofThresholdFrom()[g] ?? ''"
            (input)="typeGradeProofThresholdFrom(g, value($event))"
          />
        }
        @for (g of grades; track g) {
          <label [for]="'setting-grace-' + g">Grace for grade {{ g }} (days; blank for none)</label>
          <input
            [id]="'setting-grace-' + g"
            [attr.data-testid]="'setting-grace-' + g"
            inputmode="numeric"
            [value]="gradeGrace()[g] ?? ''"
            (focus)="typeGradeGrace(g, gradeGrace()[g] ?? '')"
            (input)="typeGradeGrace(g, value($event))"
          />
          <label [for]="'setting-grace-' + g + '-from'">Grace for grade {{ g }} from</label>
          <input
            [id]="'setting-grace-' + g + '-from'"
            [attr.data-testid]="'setting-grace-' + g + '-from'"
            placeholder="yyyy-mm-dd"
            [value]="gradeGraceFrom()[g] ?? ''"
            (input)="typeGradeGraceFrom(g, value($event))"
          />
        }
        <label for="setting-late-pct">Late percent</label>
        <input
          id="setting-late-pct"
          data-testid="setting-late-pct"
          inputmode="decimal"
          [value]="latePct()"
          (input)="latePct.set(value($event)); latePctTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-late-pct-from">Late percent from</label>
        <input
          id="setting-late-pct-from"
          data-testid="setting-late-pct-from"
          placeholder="yyyy-mm-dd"
          [value]="latePctFrom()"
          (input)="latePctFrom.set(value($event)); settingsError.set(null)"
        />
        <label for="setting-policy-date">Policy date</label>
        <input
          id="setting-policy-date"
          data-testid="setting-policy-date"
          placeholder="yyyy-mm-dd"
          [value]="policyDate()"
          (focus)="policyDateTyped.set(true)"
          (input)="policyDate.set(value($event)); policyDateTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-period-numbering-from">Period numbering from</label>
        <input
          id="setting-period-numbering-from"
          data-testid="setting-period-numbering-from"
          placeholder="yyyy-mm-dd"
          [value]="periodNumberingFrom()"
          (focus)="periodNumberingFromTyped.set(true)"
          (input)="periodNumberingFrom.set(value($event)); periodNumberingFromTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-entry-date">Entry date</label>
        <input
          id="setting-entry-date"
          data-testid="setting-entry-date"
          placeholder="yyyy-mm-dd"
          [value]="entryDate()"
          (focus)="entryDateTyped.set(true)"
          (input)="entryDate.set(value($event)); entryDateTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-grace-days">Grace days</label>
        <input
          id="setting-grace-days"
          data-testid="setting-grace-days"
          inputmode="numeric"
          [value]="graceDays()"
          (focus)="graceDaysTyped.set(true)"
          (input)="graceDays.set(value($event)); graceDaysTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-escalate-after">Escalate after (days)</label>
        <input
          id="setting-escalate-after"
          data-testid="setting-escalate-after"
          inputmode="numeric"
          [value]="escalateAfter()"
          (focus)="escalateAfterTyped.set(true)"
          (input)="escalateAfter.set(value($event)); escalateAfterTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-proof-kinds">Proof kinds (receipt, photo; comma separated)</label>
        <input
          id="setting-proof-kinds"
          data-testid="setting-proof-kinds"
          [value]="proofKinds()"
          (focus)="proofKindsTyped.set(true)"
          (input)="proofKinds.set(value($event)); proofKindsTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-default-grade">Default grade (A, B or C; blank for A)</label>
        <input
          id="setting-default-grade"
          data-testid="setting-default-grade"
          [value]="defaultGrade()"
          (focus)="defaultGradeTyped.set(true)"
          (input)="defaultGrade.set(value($event)); defaultGradeTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-review-sla">Review SLA (days)</label>
        <input
          id="setting-review-sla"
          data-testid="setting-review-sla"
          inputmode="numeric"
          [value]="reviewSla()"
          (focus)="reviewSlaTyped.set(true)"
          (input)="reviewSla.set(value($event)); reviewSlaTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-fiscal-start-month">Fiscal year start month (1 to 12)</label>
        <input
          id="setting-fiscal-start-month"
          data-testid="setting-fiscal-start-month"
          inputmode="numeric"
          [value]="fiscalStartMonth()"
          (input)="fiscalStartMonth.set(value($event)); fiscalStartMonthTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-pot">Pot</label>
        <input
          id="setting-pot"
          data-testid="setting-pot"
          inputmode="decimal"
          [value]="pot()"
          (focus)="potTyped.set(true)"
          (input)="pot.set(value($event)); potTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-alert-threshold">Alert threshold (blank: off)</label>
        <input
          id="setting-alert-threshold"
          data-testid="setting-alert-threshold"
          inputmode="decimal"
          [value]="alertThreshold()"
          (focus)="alertThresholdTyped.set(true)"
          (input)="alertThreshold.set(value($event)); alertThresholdTyped.set(true); settingsError.set(null)"
        />
        <label for="setting-parent">Parent sheet (blank: none)</label>
        <input
          id="setting-parent"
          data-testid="setting-parent"
          [value]="parent()"
          (focus)="parentTyped.set(true)"
          (input)="parent.set(value($event)); parentTyped.set(true); settingsError.set(null)"
        />
        <fieldset>
          <legend>Permissions (roles, comma separated; blank for the standing rule)</legend>
          @for (p of permissionActions; track p.action) {
            <label [for]="'setting-perm-' + p.action">{{ p.label }}</label>
            <input
              [id]="'setting-perm-' + p.action"
              [attr.data-testid]="'setting-perm-' + p.action"
              [value]="permissions()[p.action] ?? ''"
              (focus)="typePermission(p.action, permissions()[p.action] ?? '')"
              (input)="typePermission(p.action, value($event))"
            />
          }
        </fieldset>
        <button type="submit" data-testid="settings-save">Save settings</button>
      </form>
      @if (settingsError(); as message) {
        <p role="alert" data-testid="settings-error">{{ message }}</p>
      }
      <p>
        Clerk limit in force:
        <strong data-testid="setting-clerk-limit-current">{{ store.clerkLimit() ?? '' }}</strong>
        @if (store.clerkLimit() === null) {
          <span>none</span>
        }
      </p>
      <p>
        Floor in force:
        <strong data-testid="setting-floor-current">{{ store.floor() }}</strong>
      </p>
      <p>
        Cap in force:
        <strong data-testid="setting-cap-current">{{ store.cap() ?? '' }}</strong>
        @if (store.cap() === null) {
          <span>none</span>
        }
      </p>
      <p>
        Proof threshold in force:
        <strong data-testid="setting-proof-threshold-current">{{ store.proofThreshold() ?? '' }}</strong>
        @if (store.proofThreshold() === null) {
          <span>none</span>
        }
      </p>
      <p>
        Note threshold in force:
        <strong data-testid="setting-note-threshold-current">{{ store.noteThreshold() ?? '' }}</strong>
        @if (store.noteThreshold() === null) {
          <span>none</span>
        }
      </p>
      <p>
        Review threshold in force:
        <strong data-testid="setting-review-threshold-current">{{ store.reviewThreshold() ?? '' }}</strong>
        @if (store.reviewThreshold() === null) {
          <span>none</span>
        }
      </p>
      <p>
        Rounding mode in force:
        <strong data-testid="setting-rounding-current">{{ store.rounding() ?? '' }}</strong>
        @if (store.rounding() === null) {
          <span>none (the policy date decides)</span>
        }
      </p>
      <p>
        Terms in force:
        <strong data-testid="setting-terms-current">{{ store.terms() }}</strong> days
      </p>
      @for (g of grades; track g) {
        <p>
          Terms in force for grade {{ g }}:
          <strong [attr.data-testid]="'setting-terms-' + g + '-current'">{{ store.termsOf(g) }}</strong> days
          @if (store.gradeTerms()[g] === undefined) {
            <span>(the sheet's terms)</span>
          }
        </p>
      }
      @for (g of grades; track g) {
        <p>
          Proof threshold in force for grade {{ g }}:
          <strong [attr.data-testid]="'setting-proof-threshold-' + g + '-current'">{{ store.proofThresholdOf(g) }}</strong>
          @if (store.proofThresholdOf(g) === '') {
            <span>none (the sheet's)</span>
          }
        </p>
      }
      @for (g of grades; track g) {
        <p>
          Grace in force for grade {{ g }}:
          <strong [attr.data-testid]="'setting-grace-' + g + '-current'">{{ store.graceOf(g) }}</strong>
          @if (store.graceOf(g) === '') {
            <span>none</span>
          } @else {
            <span>days</span>
          }
        </p>
      }
      <p>
        Late percent in force:
        <strong data-testid="setting-late-pct-current">{{ store.latePct() }}</strong>
      </p>
      <p>
        Policy date in force:
        <strong data-testid="setting-policy-date-current">{{ store.policyDate() }}</strong>
        @if (store.policyDate() === '') {
          <span>the sheet's today</span>
        }
      </p>
      <p>
        Period numbering from:
        <strong data-testid="setting-period-numbering-from-current">{{ store.periodNumberingFrom() }}</strong>
        @if (store.periodNumberingFrom() === '') {
          <span>off</span>
        }
      </p>
      <p>
        Entry date:
        <strong data-testid="setting-entry-date-current">{{ store.entryDate() }}</strong>
        @if (store.entryDate() === '') {
          <span>off (the sheet's today)</span>
        }
      </p>
      <p>
        Grace days:
        <strong data-testid="setting-grace-days-current">{{ store.graceDays() }}</strong>
        @if (store.graceDays() === '') {
          <span>off</span>
        }
      </p>
      <p>
        Escalate after:
        <strong data-testid="setting-escalate-after-current">{{ store.escalateAfter() }}</strong>
        @if (store.escalateAfter() === '') {
          <span>off</span>
        }
      </p>
      <p>
        Proof kinds:
        <strong data-testid="setting-proof-kinds-current">{{ store.proofKinds() }}</strong>
        @if (store.proofKinds() === '') {
          <span>none</span>
        }
      </p>
      <p>
        Default grade:
        <strong data-testid="setting-default-grade-current">{{ store.defaultGrade() }}</strong>
        @if (store.defaultGrade() === '') {
          <span>A</span>
        }
      </p>
      <p>
        Review SLA:
        <strong data-testid="setting-review-sla-current">{{ store.reviewSla() }}</strong>
        @if (store.reviewSla() === '') {
          <span>none</span>
        }
      </p>
      <p>
        Fiscal year start month:
        <strong data-testid="setting-fiscal-start-month-current">{{ store.fiscalStartMonth() }}</strong>
      </p>
      <p>
        Pot:
        <strong data-testid="setting-pot-current">{{ store.pot() }}</strong>
        @if (store.pot() === '') {
          <span>none</span>
        }
      </p>
      <p>
        Alert threshold:
        <strong data-testid="setting-alert-threshold-current">{{ store.alertThreshold() }}</strong>
        @if (store.alertThreshold() === '') {
          <span>off</span>
        }
      </p>
      <p>
        Parent sheet:
        <strong data-testid="setting-parent-current">{{ store.parent() }}</strong>
        @if (store.parent() === '') {
          <span>none</span>
        }
      </p>
      @for (p of permissionActions; track p.action) {
        <p>
          {{ p.label }} allowed for:
          <strong [attr.data-testid]="'setting-perm-' + p.action + '-current'">{{ store.permissions()[p.action] ?? '' }}</strong>
          @if (!store.permissions()[p.action]) {
            <span>{{ p.standing }} (the standing rule)</span>
          }
        </p>
      }
    </section>
  `,
})
export class Settings {
  protected readonly store = inject(SettingStore);
  protected readonly roles = ROLES;
  protected readonly grades = GRADES;
  protected readonly permissionActions = PERMISSIONS;

  protected readonly user = signal('');
  protected readonly role = signal('');
  protected readonly roleError = signal<string | null>(null);
  protected readonly delegateUser = signal('');
  protected readonly delegateFrom = signal('');
  protected readonly delegateTo = signal('');
  protected readonly delegateError = signal<string | null>(null);
  protected readonly clerkLimit = signal('');
  protected readonly clerkLimitTyped = signal(false);
  protected readonly clerkLimitFrom = signal('');
  protected readonly floor = signal('');
  protected readonly floorTyped = signal(false);
  protected readonly floorFrom = signal('');
  protected readonly cap = signal('');
  protected readonly capTyped = signal(false);
  protected readonly capFrom = signal('');
  protected readonly proofThreshold = signal('');
  protected readonly proofThresholdTyped = signal(false);
  protected readonly proofThresholdFrom = signal('');
  protected readonly noteThreshold = signal('');
  protected readonly noteThresholdTyped = signal(false);
  protected readonly noteThresholdFrom = signal('');
  protected readonly reviewThreshold = signal('');
  protected readonly reviewThresholdTyped = signal(false);
  protected readonly reviewThresholdFrom = signal('');
  protected readonly rounding = signal('');
  protected readonly roundingTyped = signal(false);
  protected readonly roundingFrom = signal('');
  protected readonly terms = signal('');
  protected readonly termsTyped = signal(false);
  protected readonly termsFrom = signal('');
  /** The grades' terms typed since the last save, by grade. */
  protected readonly gradeTerms = signal<Record<string, string>>({});
  /** The from-dates typed for the grades' terms, by grade; blank for the sheet's today. */
  protected readonly gradeTermsFrom = signal<Record<string, string>>({});
  /** The grades' proof thresholds typed since the last save, by grade; blank records none. */
  protected readonly gradeProofThreshold = signal<Record<string, string>>({});
  /** The from-dates typed for the grades' proof thresholds, by grade; blank for the sheet's today. */
  protected readonly gradeProofThresholdFrom = signal<Record<string, string>>({});
  /** The grades' grace days typed since the last save, by grade; blank records none. */
  protected readonly gradeGrace = signal<Record<string, string>>({});
  /** The from-dates typed for the grades' grace days, by grade; blank for the sheet's today. */
  protected readonly gradeGraceFrom = signal<Record<string, string>>({});
  protected readonly latePct = signal('');
  protected readonly latePctTyped = signal(false);
  protected readonly latePctFrom = signal('');
  protected readonly policyDate = signal('');
  protected readonly policyDateTyped = signal(false);
  protected readonly periodNumberingFrom = signal('');
  protected readonly periodNumberingFromTyped = signal(false);
  protected readonly entryDate = signal('');
  protected readonly entryDateTyped = signal(false);
  protected readonly graceDays = signal('');
  protected readonly graceDaysTyped = signal(false);
  protected readonly escalateAfter = signal('');
  protected readonly escalateAfterTyped = signal(false);
  protected readonly proofKinds = signal('');
  protected readonly proofKindsTyped = signal(false);
  protected readonly defaultGrade = signal('');
  protected readonly defaultGradeTyped = signal(false);
  protected readonly reviewSla = signal('');
  protected readonly reviewSlaTyped = signal(false);
  protected readonly fiscalStartMonth = signal('');
  protected readonly fiscalStartMonthTyped = signal(false);
  protected readonly pot = signal('');
  protected readonly potTyped = signal(false);
  protected readonly alertThreshold = signal('');
  protected readonly alertThresholdTyped = signal(false);
  protected readonly parent = signal('');
  protected readonly parentTyped = signal(false);
  /** The permission entries typed since the last save, by action; a focused one counts as typed, as
   *  a blank one (the standing rule) is a value of its own. */
  protected readonly permissions = signal<Record<string, string>>({});
  protected readonly settingsError = signal<string | null>(null);
  /** Whether a role submit is waiting for its answer; a second one meanwhile is dropped. */
  private readonly busy = signal(false);
  /** The settings saves submitted, each run after the one before. */
  private settingsSaves: Promise<void> = Promise.resolve();

  protected value(event: Event): string {
    return (event.target as HTMLInputElement).value;
  }

  protected typeGradeTerms(grade: string, typed: string): void {
    this.gradeTerms.update((all) => ({ ...all, [grade]: typed }));
    this.settingsError.set(null);
  }

  protected typeGradeProofThreshold(grade: string, typed: string): void {
    this.gradeProofThreshold.update((all) => ({ ...all, [grade]: typed }));
    this.settingsError.set(null);
  }

  protected typeGradeProofThresholdFrom(grade: string, typed: string): void {
    this.gradeProofThresholdFrom.update((all) => ({ ...all, [grade]: typed }));
    this.settingsError.set(null);
  }

  protected typeGradeGrace(grade: string, typed: string): void {
    this.gradeGrace.update((all) => ({ ...all, [grade]: typed }));
    this.settingsError.set(null);
  }

  protected typeGradeGraceFrom(grade: string, typed: string): void {
    this.gradeGraceFrom.update((all) => ({ ...all, [grade]: typed }));
    this.settingsError.set(null);
  }

  protected typePermission(action: string, typed: string): void {
    this.permissions.update((all) => ({ ...all, [action]: typed }));
    this.settingsError.set(null);
  }

  protected typeGradeTermsFrom(grade: string, typed: string): void {
    this.gradeTermsFrom.update((all) => ({ ...all, [grade]: typed }));
    this.settingsError.set(null);
  }

  protected async saveRole(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy()) return;
    this.roleError.set(null);
    const user = this.user().trim();
    const role = this.role().trim();
    if (!user) {
      this.roleError.set('Type the user name to give a role.');
      return;
    }
    if (!ROLES.includes(role)) {
      this.roleError.set('Type a role: clerk or steward.');
      return;
    }
    this.busy.set(true);
    try {
      await this.store.setRole(user, role);
    } catch {
      this.roleError.set(`The role of ${user} could not be set.`);
    } finally {
      this.busy.set(false);
    }
  }

  /** Delegate the steward role as typed; a blank from-date is the sheet's today, a blank to-date the
   *  from-date. */
  protected async saveDelegation(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy()) return;
    this.delegateError.set(null);
    const user = this.delegateUser().trim();
    const from = this.delegateFrom().trim();
    const to = this.delegateTo().trim();
    if (!user) {
      this.delegateError.set('Type the user name to delegate the steward role to.');
      return;
    }
    if ((from && !DATE.test(from)) || (to && !DATE.test(to))) {
      this.delegateError.set('Type the dates as yyyy-mm-dd.');
      return;
    }
    this.busy.set(true);
    try {
      await this.store.delegate(user, from || null, to || null);
    } catch {
      this.delegateError.set(
        `The steward role could not be delegated to ${user}: only a steward can delegate it, and the to-date cannot be before the from-date.`,
      );
    } finally {
      this.busy.set(false);
    }
  }

  /** Save the settings typed. A submit while an earlier one is waiting for its answer is saved
   *  after it, with the values typed when it was submitted. */
  protected async saveSettings(event: Event): Promise<void> {
    event.preventDefault();
    this.settingsError.set(null);
    const limit = this.clerkLimit().trim();
    const floor = this.floor().trim();
    const saveLimit = this.clerkLimitTyped();
    const saveFloor = this.floorTyped();
    const cap = this.cap().trim();
    const saveCap = this.capTyped();
    const threshold = this.proofThreshold().trim();
    const saveThreshold = this.proofThresholdTyped();
    const noteThreshold = this.noteThreshold().trim();
    const saveNoteThreshold = this.noteThresholdTyped();
    const reviewThreshold = this.reviewThreshold().trim();
    const saveReviewThreshold = this.reviewThresholdTyped();
    const rounding = this.rounding().trim();
    const saveRounding = this.roundingTyped();
    const terms = this.terms().trim();
    const saveTerms = this.termsTyped();
    const gradeTerms = Object.entries(this.gradeTerms()).map(([grade, days]) => [grade, days.trim()] as const);
    const gradeThreshold = Object.entries(this.gradeProofThreshold()).map(([grade, amount]) => [grade, amount.trim()] as const);
    const gradeGrace = Object.entries(this.gradeGrace()).map(([grade, days]) => [grade, days.trim()] as const);
    const latePct = this.latePct().trim();
    const saveLatePct = this.latePctTyped();
    const policyDate = this.policyDate().trim();
    const savePolicyDate = this.policyDateTyped();
    const numberingFrom = this.periodNumberingFrom().trim();
    const saveNumberingFrom = this.periodNumberingFromTyped();
    const entryDate = this.entryDate().trim();
    const saveEntryDate = this.entryDateTyped();
    const graceDays = this.graceDays().trim();
    const saveGraceDays = this.graceDaysTyped();
    const escalateAfter = this.escalateAfter().trim();
    const saveEscalateAfter = this.escalateAfterTyped();
    const proofKinds = this.proofKinds()
      .split(',')
      .map((kind) => kind.trim())
      .filter((kind) => kind !== '')
      .join(',');
    const saveProofKinds = this.proofKindsTyped();
    const defaultGrade = this.defaultGrade().trim();
    const saveDefaultGrade = this.defaultGradeTyped();
    const reviewSla = this.reviewSla().trim();
    const saveReviewSla = this.reviewSlaTyped();
    const fiscalStartMonth = this.fiscalStartMonth().trim();
    const saveFiscalStartMonth = this.fiscalStartMonthTyped();
    const pot = this.pot().trim();
    const savePot = this.potTyped();
    const alertThreshold = this.alertThreshold().trim();
    const saveAlertThreshold = this.alertThresholdTyped();
    const parent = this.parent().trim();
    const saveParent = this.parentTyped();
    const permissions = Object.entries(this.permissions()).map(
      ([action, roles]) => [action, roles.split(',').map((r) => r.trim()).filter((r) => r !== '').join(',')] as const,
    );
    const limitFrom = this.clerkLimitFrom().trim();
    const floorFrom = this.floorFrom().trim();
    const capFrom = this.capFrom().trim();
    const thresholdFrom = this.proofThresholdFrom().trim();
    const noteThresholdFrom = this.noteThresholdFrom().trim();
    const reviewThresholdFrom = this.reviewThresholdFrom().trim();
    const roundingFrom = this.roundingFrom().trim();
    const termsFrom = this.termsFrom().trim();
    const latePctFrom = this.latePctFrom().trim();
    const gradeTermsFrom: Record<string, string> = Object.fromEntries(
      Object.entries(this.gradeTermsFrom()).map(([grade, from]) => [grade, from.trim()]),
    );
    const gradeThresholdFrom: Record<string, string> = Object.fromEntries(
      Object.entries(this.gradeProofThresholdFrom()).map(([grade, from]) => [grade, from.trim()]),
    );
    const gradeGraceFrom: Record<string, string> = Object.fromEntries(
      Object.entries(this.gradeGraceFrom()).map(([grade, from]) => [grade, from.trim()]),
    );
    const froms: [boolean, string, string][] = [
      [saveLimit, limitFrom, 'the clerk limit'],
      [saveFloor, floorFrom, 'the floor'],
      [saveCap, capFrom, 'the cap'],
      [saveThreshold, thresholdFrom, 'the proof threshold'],
      [saveNoteThreshold, noteThresholdFrom, 'the note threshold'],
      [saveReviewThreshold, reviewThresholdFrom, 'the review threshold'],
      [saveRounding, roundingFrom, 'the rounding mode'],
      [saveTerms, termsFrom, 'the terms'],
      ...gradeTerms.map(([grade]) => [true, gradeTermsFrom[grade] ?? '', `the terms for grade ${grade}`] as [boolean, string, string]),
      ...gradeThreshold.map(([grade]) => [true, gradeThresholdFrom[grade] ?? '', `the proof threshold for grade ${grade}`] as [boolean, string, string]),
      ...gradeGrace.map(([grade]) => [true, gradeGraceFrom[grade] ?? '', `the grace for grade ${grade}`] as [boolean, string, string]),
      [saveLatePct, latePctFrom, 'the late percent'],
    ];
    if (saveLimit && limit !== '' && !AMOUNT.test(limit)) {
      this.settingsError.set('Type a clerk limit of 0.00 or more, with up to two decimals, or leave it blank for none.');
      return;
    }
    if (saveFloor && !AMOUNT.test(floor)) {
      this.settingsError.set('Type a floor of 0.00 or more, with up to two decimals.');
      return;
    }
    if (saveCap && cap !== '' && !AMOUNT.test(cap)) {
      this.settingsError.set('Type a cap of 0.00 or more, with up to two decimals, or leave it blank for none.');
      return;
    }
    if (saveThreshold && threshold !== '' && !AMOUNT.test(threshold)) {
      this.settingsError.set('Type a proof threshold of 0.00 or more, with up to two decimals, or leave it blank for none.');
      return;
    }
    const badThreshold = gradeThreshold.find(([, amount]) => amount !== '' && !AMOUNT.test(amount));
    if (badThreshold) {
      this.settingsError.set(`Type a proof threshold for grade ${badThreshold[0]} of 0.00 or more, with up to two decimals, or leave it blank for the sheet's.`);
      return;
    }
    if (saveNoteThreshold && noteThreshold !== '' && !AMOUNT.test(noteThreshold)) {
      this.settingsError.set('Type a note threshold of 0.00 or more, with up to two decimals, or leave it blank for none.');
      return;
    }
    if (saveReviewThreshold && reviewThreshold !== '' && !AMOUNT.test(reviewThreshold)) {
      this.settingsError.set('Type a review threshold of 0.00 or more, with up to two decimals, or leave it blank for none.');
      return;
    }
    if (saveRounding && rounding !== '' && !ROUNDING_MODES.includes(rounding)) {
      this.settingsError.set('Type a rounding mode of half-up or half-even, or leave it blank for none.');
      return;
    }
    if (saveTerms && !DAYS.test(terms)) {
      this.settingsError.set('Type terms of 0 or more whole days.');
      return;
    }
    const badGrade = gradeTerms.find(([, days]) => !DAYS.test(days));
    if (badGrade) {
      this.settingsError.set(`Type terms for grade ${badGrade[0]} of 0 or more whole days.`);
      return;
    }
    const badGrace = gradeGrace.find(([, days]) => days !== '' && !DAYS.test(days));
    if (badGrace) {
      this.settingsError.set(`Type grace for grade ${badGrace[0]} of 0 or more whole days, or leave it blank for none.`);
      return;
    }
    if (saveLatePct && !AMOUNT.test(latePct)) {
      this.settingsError.set('Type a late percent of 0.00 or more, with up to two decimals.');
      return;
    }
    const badFrom = froms.find(([save, from]) => save && from !== '' && !DATE.test(from));
    if (badFrom) {
      this.settingsError.set(`Type the from-date of ${badFrom[2]} as yyyy-mm-dd, or leave it blank for the sheet's today.`);
      return;
    }
    if (savePolicyDate && policyDate !== '' && !DATE.test(policyDate)) {
      this.settingsError.set('Type a policy date as yyyy-mm-dd, or leave it blank for the sheet\'s today.');
      return;
    }
    if (saveNumberingFrom && numberingFrom !== '' && !DATE.test(numberingFrom)) {
      this.settingsError.set('Type a period numbering from date as yyyy-mm-dd, or leave it blank for off.');
      return;
    }
    if (saveEntryDate && entryDate !== '' && !DATE.test(entryDate)) {
      this.settingsError.set('Type an entry date as yyyy-mm-dd, or leave it blank for off.');
      return;
    }
    if (saveGraceDays && graceDays !== '' && !DAYS.test(graceDays)) {
      this.settingsError.set('Type grace days of 0 or more whole days, or leave them blank for off.');
      return;
    }
    if (saveEscalateAfter && escalateAfter !== '' && !DAYS.test(escalateAfter)) {
      this.settingsError.set('Type an escalation gap of 0 or more whole days, or leave it blank for off.');
      return;
    }
    if (saveProofKinds && proofKinds.split(',').some((kind) => kind !== '' && kind !== 'receipt' && kind !== 'photo')) {
      this.settingsError.set('Type proof kinds of receipt or photo, comma separated, or leave them blank for none.');
      return;
    }
    if (saveDefaultGrade && defaultGrade !== '' && !/^[ABC]$/.test(defaultGrade)) {
      this.settingsError.set('Type a default grade of A, B or C, or leave it blank for A.');
      return;
    }
    if (saveReviewSla && reviewSla !== '' && !DAYS.test(reviewSla)) {
      this.settingsError.set('Type a review SLA of 0 or more whole days, or leave it blank for none.');
      return;
    }
    if (saveFiscalStartMonth && !MONTH.test(fiscalStartMonth)) {
      this.settingsError.set('Type a fiscal year start month of 1 to 12.');
      return;
    }
    if (savePot && pot !== '' && !AMOUNT.test(pot)) {
      this.settingsError.set('Type a pot of 0.00 or more, with up to two decimals, or leave it blank for none.');
      return;
    }
    if (saveAlertThreshold && alertThreshold !== '' && !AMOUNT.test(alertThreshold)) {
      this.settingsError.set('Type an alert threshold of 0.00 or more, with up to two decimals, or leave it blank for off.');
      return;
    }
    const badPermission = Object.entries(this.permissions()).find(([, roles]) => !rolesList(roles.trim()));
    if (badPermission) {
      this.settingsError.set(`Type the roles that may ${badPermission[0]} as clerk, steward or clerk,steward, or leave it blank for the standing rule.`);
      return;
    }
    const fromOf = (from: string) => (from === '' ? null : from);
    this.clerkLimitTyped.set(false);
    this.floorTyped.set(false);
    this.capTyped.set(false);
    this.proofThresholdTyped.set(false);
    this.noteThresholdTyped.set(false);
    this.reviewThresholdTyped.set(false);
    this.roundingTyped.set(false);
    this.termsTyped.set(false);
    this.gradeTerms.set({});
    this.gradeProofThreshold.set({});
    this.gradeGrace.set({});
    this.latePctTyped.set(false);
    this.policyDateTyped.set(false);
    this.periodNumberingFromTyped.set(false);
    this.entryDateTyped.set(false);
    this.graceDaysTyped.set(false);
    this.escalateAfterTyped.set(false);
    this.proofKindsTyped.set(false);
    this.defaultGradeTyped.set(false);
    this.reviewSlaTyped.set(false);
    this.fiscalStartMonthTyped.set(false);
    this.potTyped.set(false);
    this.alertThresholdTyped.set(false);
    this.parentTyped.set(false);
    this.permissions.set({});
    const save = async () => {
      try {
        if (saveLimit) await this.store.setClerkLimit(limit === '' ? null : limit, fromOf(limitFrom));
        if (saveFloor) await this.store.setFloor(floor, fromOf(floorFrom));
        if (saveCap) await this.store.setCap(cap === '' ? null : cap, fromOf(capFrom));
        if (saveThreshold) await this.store.setProofThreshold(threshold === '' ? null : threshold, fromOf(thresholdFrom));
        for (const [grade, amount] of gradeThreshold) {
          await this.store.setGradeProofThreshold(grade, amount === '' ? null : amount, fromOf(gradeThresholdFrom[grade] ?? ''));
        }
        if (saveNoteThreshold) {
          await this.store.setNoteThreshold(noteThreshold === '' ? null : noteThreshold, fromOf(noteThresholdFrom));
        }
        if (saveReviewThreshold) {
          await this.store.setReviewThreshold(reviewThreshold === '' ? null : reviewThreshold, fromOf(reviewThresholdFrom));
        }
        if (saveRounding) await this.store.setRounding(rounding === '' ? null : rounding, fromOf(roundingFrom));
        if (saveTerms) await this.store.setTerms(Number(terms), fromOf(termsFrom));
        for (const [grade, days] of gradeTerms) {
          await this.store.setGradeTerms(grade, Number(days), fromOf(gradeTermsFrom[grade] ?? ''));
        }
        for (const [grade, days] of gradeGrace) {
          await this.store.setGradeGrace(grade, days === '' ? null : Number(days), fromOf(gradeGraceFrom[grade] ?? ''));
        }
        if (saveLatePct) await this.store.setLatePct(latePct, fromOf(latePctFrom));
        if (savePolicyDate) await this.store.setPolicyDate(policyDate === '' ? null : policyDate);
        if (saveNumberingFrom) await this.store.setPeriodNumberingFrom(numberingFrom === '' ? null : numberingFrom);
        if (saveEntryDate) await this.store.setEntryDate(entryDate === '' ? null : entryDate);
        if (saveGraceDays) await this.store.setGraceDays(graceDays === '' ? null : Number(graceDays));
        if (saveEscalateAfter) await this.store.setEscalateAfter(escalateAfter === '' ? null : Number(escalateAfter));
        if (saveProofKinds) await this.store.setProofKinds(proofKinds === '' ? null : proofKinds);
        if (saveDefaultGrade) await this.store.setDefaultGrade(defaultGrade === '' ? null : defaultGrade);
        if (saveReviewSla) await this.store.setReviewSla(reviewSla === '' ? null : Number(reviewSla));
        if (saveFiscalStartMonth) await this.store.setFiscalStartMonth(Number(fiscalStartMonth));
        if (savePot) await this.store.setPot(pot === '' ? null : pot);
        if (saveAlertThreshold) await this.store.setAlertThreshold(alertThreshold === '' ? null : alertThreshold);
        if (saveParent) await this.store.setParent(parent === '' ? null : parent);
        for (const [action, roles] of permissions) await this.store.setPermission(action, roles);
      } catch {
        // What was typed stays to be saved again.
        if (saveLimit) this.clerkLimitTyped.set(true);
        if (saveFloor) this.floorTyped.set(true);
        if (saveCap) this.capTyped.set(true);
        if (saveThreshold) this.proofThresholdTyped.set(true);
        if (saveNoteThreshold) this.noteThresholdTyped.set(true);
        if (saveReviewThreshold) this.reviewThresholdTyped.set(true);
        if (saveRounding) this.roundingTyped.set(true);
        if (saveTerms) this.termsTyped.set(true);
        this.gradeTerms.update((typed) => ({ ...Object.fromEntries(gradeTerms), ...typed }));
        this.gradeProofThreshold.update((typed) => ({ ...Object.fromEntries(gradeThreshold), ...typed }));
        this.gradeGrace.update((typed) => ({ ...Object.fromEntries(gradeGrace), ...typed }));
        if (saveLatePct) this.latePctTyped.set(true);
        if (savePolicyDate) this.policyDateTyped.set(true);
        if (saveNumberingFrom) this.periodNumberingFromTyped.set(true);
        if (saveEntryDate) this.entryDateTyped.set(true);
        if (saveGraceDays) this.graceDaysTyped.set(true);
        if (saveEscalateAfter) this.escalateAfterTyped.set(true);
        if (saveProofKinds) this.proofKindsTyped.set(true);
        if (saveDefaultGrade) this.defaultGradeTyped.set(true);
        if (saveReviewSla) this.reviewSlaTyped.set(true);
        if (saveFiscalStartMonth) this.fiscalStartMonthTyped.set(true);
        if (savePot) this.potTyped.set(true);
        if (saveAlertThreshold) this.alertThresholdTyped.set(true);
        if (saveParent) this.parentTyped.set(true);
        this.permissions.update((typed) => ({ ...Object.fromEntries(permissions), ...typed }));
        this.settingsError.set('The settings could not be saved.');
      }
    };
    this.settingsSaves = this.settingsSaves.then(save);
    await this.settingsSaves;
  }
}
