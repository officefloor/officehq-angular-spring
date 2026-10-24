import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Grade } from './people.service';

/** A user's role on a sheet: every name is a clerk unless made a steward. */
export type Role = 'clerk' | 'steward';

export const ROLES: readonly Role[] = ['clerk', 'steward'];

export interface RoleGiven {
  user: string;
  role: Role;
}

/** The floor a sheet starts with, in cents. */
export const FLOOR_CENTS = 100;

/** The late percent a sheet starts with. */
export const LATE_PCT = '0.00';

export interface SheetSettings {
  /** The most a clerk may settle a claim for, with two decimals; null when there is no limit. */
  clerkLimit: string | null;
  /** The least a claim's live due can be, with two decimals. */
  floor: string;
  /** The most a person's due-total can be, with two decimals; null when there is no cap. */
  cap: string | null;
  /** How many days after its date a claim is due by. */
  terms: number;
  /** The grades given terms of their own, in days, by grade; a grade not in it uses the sheet's terms. */
  gradeTerms: Partial<Record<Grade, number>>;
  /** The percent of its figure an overdue claim's due gains for every full 30 days it is overdue,
   *  with two decimals. */
  latePct: string;
  /** The date that decides how a claim's live figures round (YYYY-MM-DD); null when it is the
   *  sheet's today. */
  policyDate: string | null;
  /** The roles given on the sheet, by user name. */
  roles: RoleGiven[];
  /** The date (YYYY-MM-DD) from which a claim opened is numbered within its period; null when off. */
  periodNumberingFrom: string | null;
}

// The settings of a sheet (its roles, its clerk limit, its floor, its cap, its terms, its grades' own terms, its late percent, its policy date and its period numbering from), read from and written to the server.
@Injectable({ providedIn: 'root' })
export class SettingsService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  get(sheet: string): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.get<SheetSettings>(`${this.url(sheet)}/settings`, { headers: this.session.headers() }),
    );
  }

  /** A null value removes the limit. */
  setClerkLimit(sheet: string, value: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(
        `${this.url(sheet)}/settings/clerk-limit`,
        { value },
        { headers: this.session.headers() },
      ),
    );
  }

  setFloor(sheet: string, value: string): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(`${this.url(sheet)}/settings/floor`, { value }, { headers: this.session.headers() }),
    );
  }

  /** A null value removes the cap. */
  setCap(sheet: string, value: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(`${this.url(sheet)}/settings/cap`, { value }, { headers: this.session.headers() }),
    );
  }

  setTerms(sheet: string, value: string): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(`${this.url(sheet)}/settings/terms`, { value }, { headers: this.session.headers() }),
    );
  }

  /** A null value removes the grade's own terms: it uses the sheet's terms. */
  setGradeTerms(sheet: string, grade: Grade, value: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(
        `${this.url(sheet)}/settings/terms/${grade}`,
        { value },
        { headers: this.session.headers() },
      ),
    );
  }

  setLatePct(sheet: string, value: string): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(`${this.url(sheet)}/settings/late-pct`, { value }, { headers: this.session.headers() }),
    );
  }

  /** A null value makes the policy date the sheet's today. */
  setPolicyDate(sheet: string, value: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(
        `${this.url(sheet)}/settings/policy-date`,
        { value },
        { headers: this.session.headers() },
      ),
    );
  }

  /** A null value turns period numbering off. */
  setPeriodNumberingFrom(sheet: string, value: string | null): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(
        `${this.url(sheet)}/settings/period-numbering-from`,
        { value },
        { headers: this.session.headers() },
      ),
    );
  }

  setRole(sheet: string, user: string, role: Role): Promise<RoleGiven> {
    return firstValueFrom(
      this.http.put<RoleGiven>(
        `${this.url(sheet)}/roles/${encodeURIComponent(user)}`,
        { role },
        { headers: this.session.headers() },
      ),
    );
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}`;
  }
}
