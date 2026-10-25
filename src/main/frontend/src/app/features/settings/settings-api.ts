import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** The roles a sheet can assign a user name; any name not assigned one is a clerk. */
export const ROLES = ['clerk', 'steward'] as const;
export type Role = (typeof ROLES)[number];

export interface RoleView {
  user: string;
  role: Role;
}

export interface SettingsView {
  /** The highest due a clerk may settle, with two decimals, or null when there is no limit. */
  clerkLimit: string | null;
  /** The least a claim's live due can be, with two decimals. */
  floor: string;
  /** The days after a claim's date it is due by. */
  terms: number;
  /** The roles the sheet has assigned, by user name. */
  roles: RoleView[];
}

// The sheet's settings and the roles it assigns user names, as the store holds them.
@Injectable({ providedIn: 'root' })
export class SettingsApi {
  private readonly http = inject(HttpClient);

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }

  get(sheetId: string): Promise<SettingsView> {
    return firstValueFrom(this.http.get<SettingsView>(`${this.url(sheetId)}/settings`));
  }

  /** Set the clerk limit; null clears it, leaving no limit. */
  setClerkLimit(sheetId: string, value: string | null): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/clerk-limit`, { value }));
  }

  /** Set the floor. */
  setFloor(sheetId: string, value: string): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/floor`, { value }));
  }

  /** Set the terms, in days. */
  setTerms(sheetId: string, value: number): Promise<SettingsView> {
    return firstValueFrom(this.http.put<SettingsView>(`${this.url(sheetId)}/settings/terms`, { value }));
  }

  /** Assign a user name a role on the sheet. */
  setRole(sheetId: string, user: string, role: Role): Promise<RoleView> {
    return firstValueFrom(this.http.put<RoleView>(`${this.url(sheetId)}/roles`, { user, role }));
  }
}
