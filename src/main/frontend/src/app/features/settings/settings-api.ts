import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { DatedSettings, Session, otherError } from '../../core/session';

/** The roles a user name can hold on a sheet; a name without one is a clerk. A viewer sees the
 *  sheet and may change nothing on it. */
export const ROLES = ['clerk', 'steward', 'viewer'] as const;

/** The actions whose roles the permission matrix sets, each with its standing rule (used while
 *  its entry is blank). */
export const PERMISSIONS = [
  { action: 'open', standing: 'clerk,steward' },
  { action: 'settle', standing: 'clerk,steward' },
  { action: 'void', standing: 'clerk,steward' },
  { action: 'reassign', standing: 'steward' },
  { action: 'close', standing: 'clerk,steward' },
  { action: 'reopen', standing: 'steward' },
] as const;

export type PermissionAction = (typeof PERMISSIONS)[number]['action'];

/** Roles that may take an action: clerk and/or steward, comma separated. */
export const PERMISSION_PATTERN = /^\s*(clerk|steward)\s*(,\s*(clerk|steward)\s*)*$/;

/** A limit of 0.00 or more with up to two decimals. */
export const LIMIT_PATTERN = /^\d{1,13}(\.\d{1,2})?$/;

/** A late percent of 0.00 or more with up to two decimals. */
export const LATE_PCT_PATTERN = /^\d{1,3}(\.\d{1,2})?$/;

/** A policy date as yyyy-mm-dd. */
export const POLICY_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

/** Terms of a whole number of days. */
export const TERMS_PATTERN = /^\d{1,4}$/;

/** The grades a person can hold, each of which can have terms of its own. */
export const GRADES = ['A', 'B', 'C'] as const;

export interface Settings {
  /** Two decimals, as the server formats it; null when there is no limit. */
  clerkLimit: string | null;
  /** Two decimals, as the server formats it: a claim's live due is at least it. */
  floor: string;
  /** Days: a claim is due by its date plus them. */
  terms: number;
  /** Two decimals, as the server formats it: an overdue claim's due gains it for every full 30 days overdue. */
  latePct: string;
  /** yyyy-mm-dd: a claim dated before it rounds half up, one on or after it half to even; null when
   *  blank, when the sheet's today is the policy date. */
  policyDate: string | null;
  /** yyyy-mm-dd: while the sheet's today is on or after it, a claim opened is numbered within its
   *  period; null when blank (off). */
  periodNumberingFrom: string | null;
  /** yyyy-mm-dd: while set, a new claim left undated takes it instead of the sheet's today; null
   *  when blank (off). */
  entryDate: string | null;
  /** Days: when the sheet's today changes, every open period whose to-date plus them is before it is closed; null when blank (off). */
  graceDays: number | null;
  /** Days, by grade: the terms a grade has of its own; a grade not here uses the sheet's terms. */
  gradeTerms: Record<string, number>;
  /** Two decimals, as the server formats it: a person's due-total is at most it; null when there is none. */
  cap: string | null;
  /** Two decimals, as the server formats it: a claim whose due is above it cannot be settled or
   *  approved without a proof; null when there is none. */
  proofThreshold: string | null;
  /** Every value recorded for the dated settings, by key, each key's by from-date. */
  dated: DatedSettings;
  /** The roles set for each action, comma separated; an action left blank (its standing rule) is not here. */
  permissions: Partial<Record<PermissionAction, string>>;
}

export interface RoleGrant {
  user: string;
  role: string;
}

/** A steward's role delegated to a user name from a date to a date (yyyy-mm-dd), inclusive. */
export interface Delegation {
  id: number;
  user: string;
  steward: string;
  from: string;
  to: string;
}

// The sheet's settings and the roles user names hold on it, read and written through the server.
@Injectable({ providedIn: 'root' })
export class SettingsApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  get(sheetId: string): Promise<Settings> {
    return firstValueFrom(this.http.get<Settings>(`${this.url(sheetId)}/settings`, { headers: this.headers() }));
  }

  /** Save the value from a date (blank: the sheet's today). */
  setClerkLimit(sheetId: string, value: string, from = ''): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/clerk-limit`, { value, from }, { headers: this.headers() }),
    );
  }

  /** Save the value from a date (blank: the sheet's today). */
  setFloor(sheetId: string, value: string, from = ''): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/floor`, { value, from }, { headers: this.headers() }),
    );
  }

  /** Save the value from a date (blank: the sheet's today). */
  setTerms(sheetId: string, value: string, from = ''): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/terms`, { value, from }, { headers: this.headers() }),
    );
  }

  /** Save the grade's own terms from a date (blank: the sheet's today). */
  setGradeTerms(sheetId: string, grade: string, value: string, from = ''): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/terms/${encodeURIComponent(grade)}`, { value, from }, { headers: this.headers() }),
    );
  }

  /** Save the value from a date (blank: the sheet's today). */
  setLatePct(sheetId: string, value: string, from = ''): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/late-pct`, { value, from }, { headers: this.headers() }),
    );
  }

  /** Save the value from a date (blank: the sheet's today). */
  setCap(sheetId: string, value: string, from = ''): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/cap`, { value, from }, { headers: this.headers() }),
    );
  }

  /** Save the value from a date (blank: the sheet's today). */
  setProofThreshold(sheetId: string, value: string, from = ''): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/proof-threshold`, { value, from }, { headers: this.headers() }),
    );
  }

  /** Save the value from a date (blank: the sheet's today). */
  setNoteThreshold(sheetId: string, value: string, from = ''): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/note-threshold`, { value, from }, { headers: this.headers() }),
    );
  }

  /** Save the roles that may take an action (blank: its standing rule). */
  setPermission(sheetId: string, action: PermissionAction, value: string): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/perm/${action}`, { value }, { headers: this.headers() }),
    );
  }

  setPolicyDate(sheetId: string, value: string): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/policy-date`, { value }, { headers: this.headers() }),
    );
  }

  setGraceDays(sheetId: string, value: string): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/grace-days`, { value }, { headers: this.headers() }),
    );
  }

  setEntryDate(sheetId: string, value: string): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/entry-date`, { value }, { headers: this.headers() }),
    );
  }

  setPeriodNumberingFrom(sheetId: string, value: string): Promise<Settings> {
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/period-numbering-from`, { value }, { headers: this.headers() }),
    );
  }

  roles(sheetId: string): Promise<RoleGrant[]> {
    return firstValueFrom(this.http.get<RoleGrant[]>(`${this.url(sheetId)}/roles`, { headers: this.headers() }));
  }

  setRole(sheetId: string, user: string, role: string): Promise<RoleGrant> {
    return firstValueFrom(this.http.put<RoleGrant>(`${this.url(sheetId)}/roles`, { user, role }, { headers: this.headers() }));
  }

  delegations(sheetId: string): Promise<Delegation[]> {
    return firstValueFrom(this.http.get<Delegation[]>(`${this.url(sheetId)}/delegations`, { headers: this.headers() }));
  }

  delegate(sheetId: string, user: string, from: string, to: string): Promise<Delegation> {
    return firstValueFrom(
      this.http.post<Delegation>(`${this.url(sheetId)}/delegations`, { user, from, to }, { headers: this.headers() }),
    );
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}

export const SETTINGS_INVALID =
  'Enter a clerk limit of 0.00 or more with up to two decimals (blank: no limit) and a floor of 0.00 or more with up to two decimals (blank: 1.00) and terms of a whole number of days (blank: 14) and terms for each grade of a whole number of days (blank: the sheet\'s terms) and a late percent of 0.00 or more with up to two decimals (blank: 0.00) and a policy date as yyyy-mm-dd (blank: the sheet\'s today) and a period numbering from-date as yyyy-mm-dd (blank: off) and an entry date as yyyy-mm-dd (blank: off) and a cap of 0.00 or more with up to two decimals (blank: none) and a proof threshold of 0.00 or more with up to two decimals (blank: none) and grace days of a whole number of days (blank: off) and the roles for each action as clerk and/or steward, comma separated (blank: the standing rule), each from-date as yyyy-mm-dd (blank: the sheet\'s today).';

export function settingsError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return SETTINGS_INVALID;
  return otherError(e);
}

export function roleError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a user name without spaces and a role of clerk, steward or viewer.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'The role was changed at the same moment; try again.';
  return otherError(e);
}

export function delegateError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a user name without spaces and a to-date on or after the from-date, each as yyyy-mm-dd.';
  if (e instanceof HttpErrorResponse && e.status === 403) return 'Only a steward can delegate the steward role.';
  return otherError(e);
}
