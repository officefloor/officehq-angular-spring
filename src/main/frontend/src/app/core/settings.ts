import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Grade } from './people';
import { Session } from './session';

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

  /** Set the clerk limit; null for no limit. */
  setClerkLimit(value: string | null): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(this.http.put<Settings>(`${this.url(sheetId)}/settings/clerk-limit`, { value }, { headers }));
  }

  /** Set the floor. */
  setFloor(value: string): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(this.http.put<Settings>(`${this.url(sheetId)}/settings/floor`, { value }, { headers }));
  }

  /** Set the terms. */
  setTerms(value: string): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(this.http.put<Settings>(`${this.url(sheetId)}/settings/terms`, { value }, { headers }));
  }

  /** Set a grade's own terms. */
  setGradeTerms(grade: Grade, value: string): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(
      this.http.put<Settings>(`${this.url(sheetId)}/settings/terms/${encodeURIComponent(grade)}`, { value }, { headers }),
    );
  }

  /** Set the late percent. */
  setLatePct(value: string): Promise<Settings> {
    const [sheetId, headers] = this.active();
    return firstValueFrom(this.http.put<Settings>(`${this.url(sheetId)}/settings/late-pct`, { value }, { headers }));
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
