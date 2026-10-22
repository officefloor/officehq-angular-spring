import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** The roles a user name can have on a sheet; a name with none set is a clerk. */
export const ROLES = ['clerk', 'steward'] as const;
export type RoleName = (typeof ROLES)[number];

export interface RoleView {
  user: string;
  role: RoleName;
}

export interface SettingsView {
  /** The clerk limit with two decimals, e.g. "1.50"; null when there is no limit. */
  clerkLimit: string | null;
}

/** A clerk limit as the form takes it: up to two decimals, or blank for no limit. */
export const LIMIT = /^(\d{1,12}(\.\d{1,2})?)?$/;

// The sheet's settings and the roles its user names have, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class SettingsApi {
  private readonly http = inject(HttpClient);

  get(sheet: string): Promise<SettingsView> {
    return firstValueFrom(this.http.get<SettingsView>(`${this.url(sheet)}/settings`));
  }

  /** Set the clerk limit; a blank value means no limit. */
  setClerkLimit(sheet: string, value: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheet)}/settings/clerk-limit`, { value }));
  }

  /** The roles set on the sheet, by user name. */
  roles(sheet: string): Promise<RoleView[]> {
    return firstValueFrom(this.http.get<RoleView[]>(`${this.url(sheet)}/roles`));
  }

  /** Set a user name's role on the sheet. */
  setRole(sheet: string, form: RoleView): Promise<RoleView> {
    return firstValueFrom(this.http.put<RoleView>(`${this.url(sheet)}/roles`, form));
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}`;
  }
}
