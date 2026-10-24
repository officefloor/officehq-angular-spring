import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A user's role on a sheet: every name is a clerk unless made a steward. */
export type Role = 'clerk' | 'steward';

export const ROLES: readonly Role[] = ['clerk', 'steward'];

export interface RoleGiven {
  user: string;
  role: Role;
}

/** The floor a sheet starts with, in cents. */
export const FLOOR_CENTS = 100;

/** The terms a sheet starts with, in days. */
export const TERMS_DAYS = 14;

/** The late percent a sheet starts with. */
export const LATE_PCT = '0.00';

export interface SheetSettings {
  /** The most a clerk may settle a claim for, with two decimals; null when there is no limit. */
  clerkLimit: string | null;
  /** The least a claim's live due can be, with two decimals. */
  floor: string;
  /** How many days after its date a claim is due by. */
  terms: number;
  /** The percent of its figure an overdue claim's due gains for every full 30 days it is overdue,
   *  with two decimals. */
  latePct: string;
  /** The roles given on the sheet, by user name. */
  roles: RoleGiven[];
}

// The settings of a sheet (its roles, its clerk limit, its floor, its terms and its late percent), read from and written to the server.
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

  setTerms(sheet: string, value: string): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(`${this.url(sheet)}/settings/terms`, { value }, { headers: this.session.headers() }),
    );
  }

  setLatePct(sheet: string, value: string): Promise<SheetSettings> {
    return firstValueFrom(
      this.http.put<SheetSettings>(`${this.url(sheet)}/settings/late-pct`, { value }, { headers: this.session.headers() }),
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
