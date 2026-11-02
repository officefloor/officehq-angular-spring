import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

export interface Settings {
  /** The most due a clerk may settle, as text with two decimals; null when there is no limit. */
  clerkLimit: string | null;
  /** The least live due a claim has, as text with two decimals. */
  floor: string;
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
