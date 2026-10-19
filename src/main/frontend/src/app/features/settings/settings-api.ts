import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { Session } from '../../core/session';

/** The roles a user name can have on a sheet; any name without one is a clerk. */
export const ROLES = ['clerk', 'steward'] as const;
export type Role = (typeof ROLES)[number];

export interface RoleGiven {
  user: string;
  role: Role;
}

export interface Settings {
  /** The highest due a clerk may settle, with two decimals; null for no limit. */
  clerkLimit: string | null;
}

// A sheet's settings: the role each user name has, and its clerk limit, read and changed through the
// server.
@Injectable({ providedIn: 'root' })
export class SettingsApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  roles(sheetId: string): Observable<RoleGiven[]> {
    return this.http.get<RoleGiven[]>(`${this.url(sheetId)}/roles`, { headers: this.headers() });
  }

  setRole(sheetId: string, user: string, role: string): Observable<RoleGiven> {
    return this.http.put<RoleGiven>(`${this.url(sheetId)}/roles`, { user, role }, { headers: this.headers() });
  }

  get(sheetId: string): Observable<Settings> {
    return this.http.get<Settings>(`${this.url(sheetId)}/settings`, { headers: this.headers() });
  }

  save(sheetId: string, settings: { clerkLimit: string }): Observable<Settings> {
    return this.http.put<Settings>(`${this.url(sheetId)}/settings`, settings, { headers: this.headers() });
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}
