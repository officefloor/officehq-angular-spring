import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
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
  /** The permission matrix: for each action, the comma separated roles that may take it; blank when
   *  it keeps the standing rule. */
  permissions: Record<PermAction, string>;
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

export type RoleName = 'clerk' | 'steward';

export interface Role {
  user: string;
  role: RoleName;
}

/** The roles a user name can be given; a name given none is a clerk. */
export const ROLES: RoleName[] = ['clerk', 'steward'];

/** A limit with up to two decimals, 0.00 or more. */
export const LIMIT = /^\d{1,12}(\.\d{1,2})?$/;

/** Terms: a whole number of days, 0 or more. */
export const TERMS = /^\d{1,4}$/;

/** A policy date: yyyy-mm-dd. */
export const POLICY_DATE = /^\d{4}-\d{2}-\d{2}$/;

/** A late percent with up to two decimals, 0.00 or more. */
export const LATE_PCT = /^\d{1,5}(\.\d{1,2})?$/;

// The settings of the active sheet and the roles it gives user names.
@Injectable({ providedIn: 'root' })
export class SheetSettings {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

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
    return firstValueFrom(this.http.put<Settings>(`${this.url(sheetId)}/settings/cap`, { value, from }, { headers }));
  }

  /** Record the proof threshold (null for none) from a date (null for the sheet's today). */
  setProofThreshold(value: string | null, from: string | null): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/proof-threshold`, { value, from }, { headers }),
    );
  }

  /** Record the floor from a date (null for the sheet's today). */
  setFloor(value: string, from: string | null): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(this.http.put<Settings>(`${this.url(sheetId)}/settings/floor`, { value, from }, { headers }));
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

  /** Record the late percent from a date (null for the sheet's today). */
  setLatePct(value: string, from: string | null): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(this.http.put<Settings>(`${this.url(sheetId)}/settings/late-pct`, { value, from }, { headers }));
  }

  /** Set the policy date; null for blank (the sheet's today). */
  setPolicyDate(value: string | null): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(this.http.put<Settings>(`${this.url(sheetId)}/settings/policy-date`, { value }, { headers }));
  }

  /** Set the period numbering from date; null for blank (off). */
  setPeriodNumberingFrom(value: string | null): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/period-numbering-from`, { value }, { headers }),
    );
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
