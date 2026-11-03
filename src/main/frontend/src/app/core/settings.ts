import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Grade } from './people';
import { Session } from './session';

// The dated settings (the clerk limit, the cap, the floor, the terms and the late percent) as in force
// on the sheet's today.
export interface Settings {
  /** The most due a clerk may settle, as text with two decimals; null when there is no limit. */
  clerkLimit: string | null;
  /** The least live due a claim has, as text with two decimals. */
  floor: string;
  /** The days after its date a claim is due by. */
  terms: number;
  /** The percent of its figure an overdue claim gains for every full 30 days, as text with two decimals. */
  latePct: string;
  /** The policy date (yyyy-mm-dd); null when blank, the sheet's today then stands in. */
  policyDate: string | null;
  /** The period numbering from date (yyyy-mm-dd); null when blank (off). */
  periodNumberingFrom: string | null;
  /** The terms each grade has of its own, by grade; a grade not in it takes the sheet's terms. */
  gradeTerms: Partial<Record<Grade, number>>;
  /** The most a person's due-total comes to, as text with two decimals; null when there is no cap. */
  cap: string | null;
  /** The floors recorded, each from its from-date, earliest first. */
  floors: DatedValue[];
  /** The late percents recorded, each from its from-date, earliest first. */
  latePcts: DatedValue[];
  /** The caps recorded (null: no cap), each from its from-date, earliest first. */
  caps: DatedValue<string | null>[];
  /** The due above which a claim needs a proof before it is settled or approved, as text with two
   *  decimals; null when there is none. */
  proofThreshold: string | null;
  /** The due above which a claim cannot be opened with an empty note, as text with two decimals;
   *  null when there is none. */
  noteThreshold: string | null;
  /** The due above which a claim goes to review when it is opened, as text with two decimals; null
   *  when there is none. */
  reviewThreshold: string | null;
  /** The permission matrix: for each action, the comma separated roles that may take it; blank when
   *  it keeps the standing rule. */
  permissions: Record<PermAction, string>;
  /** The grace days: when the sheet's today is changed, an open period whose to-date plus them is
   *  before it is closed; null when blank (off). */
  graceDays: number | null;
  /** The entry date (yyyy-mm-dd): while it is set, a new claim's date when none is given; null when
   *  blank (off, the sheet's today then stands in). */
  entryDate: string | null;
  /** The grace days each grade has in force on the sheet's today, by grade; a grade not in it has
   *  none. */
  gradeGraces: Partial<Record<Grade, number>>;
  /** The grace days recorded for each grade (null: blank, none), each from its from-date, earliest
   *  first. */
  graces: Graces;
  /** The proof threshold each grade has of its own in force on the sheet's today, as text with two
   *  decimals, by grade; a grade not in it takes the sheet's. */
  gradeProofThresholds: Partial<Record<Grade, string>>;
  /** The pot a balanced pool's members cut by their shares, as text with two decimals; null when
   *  blank (none). Not dated. */
  pot: string | null;
  /** The parent sheet's id: a grade with no rate in force on a date on this sheet takes the
   *  parent's, then its parent's, up to three levels up; null when blank (none). Not dated. */
  parent: string | null;
  /** The fiscal year start month, 1 to 12 (1 unless set): a fiscal year runs from the first day of
   *  it. Not dated. */
  fiscalStartMonth: number;
  /** The alert threshold: a person whose due-total crosses above it is alerted, as text with two
   *  decimals; null when blank (off). Not dated. */
  alertThreshold: string | null;
  /** The rounding mode in force on the sheet's today; null when blank (none: a claim rounds by the
   *  policy date). */
  rounding: RoundingMode | null;
  /** The rounding modes recorded (null: blank, none), each from its from-date, earliest first. */
  roundings: DatedValue<RoundingMode | null>[];
}

/** A rounding mode: how a claim's live figures round when one is in force on its date. */
export type RoundingMode = 'half-up' | 'half-even';

/** A rounding mode typed: half-up or half-even. */
export const ROUNDING = /^(half-up|half-even)$/;

/** The grace days recorded for each grade (null: blank, none), each from its from-date, earliest
 *  first: a claim whose person's grade on its date has grace on that date is overdue only once its
 *  due-by plus them is before the sheet's today. */
export type Graces = Partial<Record<Grade, { from: string; value: number | null }[]>>;

/** The grace days a grade has in force on a day (yyyy-mm-dd); 0 when none (blank, or never set). */
export function graceOn(graces: Graces, grade: Grade | undefined, day: string): number {
  if (grade === undefined) return 0;
  let value: number | null = null;
  for (const v of graces[grade] ?? []) {
    if (v.from <= day) value = v.value;
  }
  return value ?? 0;
}

/** The actions the permission matrix covers. */
export type PermAction = 'open' | 'settle' | 'void' | 'reassign' | 'close' | 'reopen';

export const PERM_ACTIONS: PermAction[] = ['open', 'settle', 'void', 'reassign', 'close', 'reopen'];

/** The roles that may take each action when its entry in the matrix is blank. */
export const STANDING_PERMISSIONS: Record<PermAction, string> = {
  open: 'clerk,steward', settle: 'clerk,steward', void: 'clerk,steward', close: 'clerk,steward',
  reassign: 'steward', reopen: 'steward',
};

/** A permission entry: a comma separated list of roles (clerk, steward). */
export const PERM_ROLES = /^\s*(clerk|steward)\s*(,\s*(clerk|steward)\s*)*$/;

/** A value a dated setting takes from a date on, as text with two decimals; null when blank. */
export interface DatedValue<V extends string | null = string> {
  /** yyyy-mm-dd */
  from: string;
  value: V;
}

/** The floor a sheet starts with, in force before every from-date of its own. */
export const STARTING_FLOOR = '1.00';

/** The late percent a sheet starts with, in force before every from-date of its own. */
export const STARTING_LATE_PCT = '0.00';

/** The value of a dated setting in force on a day (yyyy-mm-dd): the one with the latest from-date on
 *  or before it, or else the starting value. */
export function valueOn<V extends string | null>(recorded: DatedValue<V>[], day: string, starting: V): V {
  let value = starting;
  for (const v of recorded) {
    if (v.from <= day) value = v.value;
  }
  return value;
}

export type RoleName = 'clerk' | 'steward' | 'viewer';

export interface Role {
  user: string;
  role: RoleName;
}

/** The roles a user name can be given; a name given none is a clerk, and a viewer may change
 *  nothing. */
export const ROLES: RoleName[] = ['clerk', 'steward', 'viewer'];

/** A limit with up to two decimals, 0.00 or more. */
export const LIMIT = /^\d{1,12}(\.\d{1,2})?$/;

/** Terms: a whole number of days, 0 or more. */
export const TERMS = /^\d{1,4}$/;

/** A fiscal year start month: 1 to 12. */
export const FISCAL_MONTH = /^(0?[1-9]|1[0-2])$/;

/** A policy date: yyyy-mm-dd. */
export const POLICY_DATE = /^\d{4}-\d{2}-\d{2}$/;

/** A late percent with up to two decimals, 0.00 or more. */
export const LATE_PCT = /^\d{1,5}(\.\d{1,2})?$/;

// The settings of the active sheet and the roles it gives user names.
@Injectable({ providedIn: 'root' })
export class SheetSettings {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);
  /** The settings a save on this screen came back with last, and the sheet they belong to, so what
   *  is figured from the dated settings shows them without re-reading the sheet. */
  readonly saved = signal<{ sheetId: string; settings: Settings } | null>(null);

  get(sheetId: string): Promise<Settings> {
    return firstValueFrom(this.http.get<Settings>(`${this.url(sheetId)}/settings`));
  }

  roles(sheetId: string): Promise<Role[]> {
    return firstValueFrom(this.http.get<Role[]>(`${this.url(sheetId)}/roles`));
  }

  /** Record the clerk limit (null for no limit) from a date (null for the sheet's today). */
  setClerkLimit(value: string | null, from: string | null): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(this.http.put<Settings>(`${this.url(sheetId)}/settings/clerk-limit`, { value, from }, { headers }));
  }

  /** Record the cap (null for no cap) from a date (null for the sheet's today). */
  setCap(value: string | null, from: string | null): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(this.http.put<Settings>(`${this.url(sheetId)}/settings/cap`, { value, from }, { headers })).then((settings) => this.record(sheetId, settings));
  }

  /** Record the proof threshold (null for none) from a date (null for the sheet's today). */
  setProofThreshold(value: string | null, from: string | null): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/proof-threshold`, { value, from }, { headers }),
    );
  }

  /** Record a grade's own proof threshold (null for blank, the sheet's) from a date (null for the
   *  sheet's today). */
  setGradeProofThreshold(grade: Grade, value: string | null, from: string | null): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/proof-threshold/${encodeURIComponent(grade)}`,
        { value, from }, { headers }),
    );
  }

  /** Record the note threshold (null for none) from a date (null for the sheet's today). */
  setNoteThreshold(value: string | null, from: string | null): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/note-threshold`, { value, from }, { headers }),
    );
  }

  /** Record the review threshold (null for none) from a date (null for the sheet's today). */
  setReviewThreshold(value: string | null, from: string | null): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/review-threshold`, { value, from }, { headers }),
    );
  }

  /** Record the floor from a date (null for the sheet's today). */
  setFloor(value: string, from: string | null): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(this.http.put<Settings>(`${this.url(sheetId)}/settings/floor`, { value, from }, { headers })).then((settings) => this.record(sheetId, settings));
  }

  /** Record the rounding mode (null for blank, none) from a date (null for the sheet's today). */
  setRounding(value: string | null, from: string | null): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(this.http.put<Settings>(`${this.url(sheetId)}/settings/rounding`, { value, from }, { headers })).then((settings) => this.record(sheetId, settings));
  }

  /** Record the terms from a date (null for the sheet's today). */
  setTerms(value: string, from: string | null): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(this.http.put<Settings>(`${this.url(sheetId)}/settings/terms`, { value, from }, { headers }));
  }

  /** Record a grade's own terms from a date (null for the sheet's today). */
  setGradeTerms(grade: Grade, value: string, from: string | null): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/terms/${encodeURIComponent(grade)}`, { value, from }, { headers }),
    );
  }

  /** Record a grade's grace days (null for blank, none) from a date (null for the sheet's today). */
  setGrace(grade: Grade, value: string | null, from: string | null): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/grace/${encodeURIComponent(grade)}`, { value, from }, { headers }),
    ).then((settings) => this.record(sheetId, settings));
  }

  /** Record the late percent from a date (null for the sheet's today). */
  setLatePct(value: string, from: string | null): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(this.http.put<Settings>(`${this.url(sheetId)}/settings/late-pct`, { value, from }, { headers })).then((settings) => this.record(sheetId, settings));
  }

  /** Set the policy date; null for blank (the sheet's today). */
  setPolicyDate(value: string | null): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(this.http.put<Settings>(`${this.url(sheetId)}/settings/policy-date`, { value }, { headers })).then((settings) => this.record(sheetId, settings));
  }

  /** Set the period numbering from date; null for blank (off). */
  setPeriodNumberingFrom(value: string | null): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/period-numbering-from`, { value }, { headers }),
    );
  }

  /** Set the grace days; null for blank (off). */
  setGraceDays(value: string | null): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(this.http.put<Settings>(`${this.url(sheetId)}/settings/grace-days`, { value }, { headers }));
  }

  /** Set the entry date; null for blank (off). */
  setEntryDate(value: string | null): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(this.http.put<Settings>(`${this.url(sheetId)}/settings/entry-date`, { value }, { headers }));
  }

  /** Set the pot; null for blank (none). */
  setPot(value: string | null): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(this.http.put<Settings>(`${this.url(sheetId)}/settings/pot`, { value }, { headers }));
  }

  /** Set the alert threshold; null for blank (off). */
  setAlertThreshold(value: string | null): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(this.http.put<Settings>(`${this.url(sheetId)}/settings/alert-threshold`, { value }, { headers }));
  }

  /** Set the fiscal year start month (1 to 12). */
  setFiscalStartMonth(value: string): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/fiscal-start-month`, { value }, { headers }),
    );
  }

  /** Set the parent sheet; null for blank (none). */
  setParent(value: string | null): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(this.http.put<Settings>(`${this.url(sheetId)}/settings/parent`, { value }, { headers }));
  }

  /** Set the roles (comma separated; null for blank, the standing rule) that may take an action. */
  setPermission(action: PermAction, value: string | null): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/permissions/${action}`, { value }, { headers }),
    );
  }

  /** Give a user name a role, replacing the one it had. */
  setRole(user: string, role: RoleName): Promise<Role> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(
      this.http.put<Role>(`${this.url(sheetId)}/roles/${encodeURIComponent(user)}`, { role }, { headers }),
    );
  }

  private record(sheetId: string, settings: Settings): Settings {
    this.saved.set({ sheetId, settings });
    return settings;
  }

  private active(): [string, { 'X-User': string }] {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    return [sheetId, { 'X-User': user }];
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }
}
