import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

export interface RoleView {
  user: string;
  role: string;
}

/** A steward's delegation of the steward role to a user, from a date to a date (YYYY-MM-DD), inclusive. */
export interface DelegationView {
  id: number;
  delegator: string;
  user: string;
  from: string;
  to: string;
}

/** The roles a sheet can give a user name; a name given none is a clerk. */
export const ROLES = ['clerk', 'steward', 'viewer'] as const;

// The roles the active sheet gives user names, re-read from the server each time the sheet is loaded.
// A steward settles any claim; a clerk only one whose due is within the sheet's clerk limit; a viewer
// sees the sheet and changes nothing. A steward can delegate the steward role to a user for a from-date to
// a to-date: while the sheet's today is inside them the user acts as a steward.
@Injectable({ providedIn: 'root' })
export class Roles {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** By user name. */
  readonly roles = signal<RoleView[]>([]);
  /** In the order they were made. */
  readonly delegations = signal<DelegationView[]>([]);
  private loads = 0;

  /** Re-read the active sheet's roles from the server; an error message when that failed. */
  async load(): Promise<string | null> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    const load = ++this.loads;
    this.roles.set([]);
    this.delegations.set([]);
    if (!user || !sid) return null;
    try {
      const [roles, delegations] = await Promise.all([
        firstValueFrom(this.http.get<RoleView[]>(this.url(sid), { headers: { 'X-User': user } })),
        firstValueFrom(this.http.get<DelegationView[]>(this.delegationsUrl(sid), { headers: { 'X-User': user } })),
      ]);
      if (load === this.loads) {
        this.roles.set([...roles].sort(byUser));
        this.delegations.set(delegations);
      }
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not load roles (${e.status}).` : 'Could not load roles.';
    }
  }

  /** Give a user name a role; an error message when refused. */
  async set(name: string, role: string): Promise<string | null> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    const n = name.trim();
    const r = role.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (!n) return 'Enter a user name.';
    if (!(ROLES as readonly string[]).includes(r)) return `Enter a role: ${ROLES.join(' or ')}.`;
    try {
      const saved = await firstValueFrom(
        this.http.put<RoleView>(this.url(sid), { user: n, role: r }, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) {
        this.roles.update((rs) => [...rs.filter((x) => x.user !== saved.user), saved].sort(byUser));
      }
      return null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 400) return `Enter a role: ${ROLES.join(' or ')}.`;
      return e instanceof HttpErrorResponse ? `Could not set role (${e.status}).` : 'Could not set role.';
    }
  }

  /** Delegate the steward role to a user from a date to a date (blank from: the sheet's today; blank to: the
   *  from-date); an error message when refused. */
  async delegate(name: string, from: string, to: string): Promise<string | null> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    const n = name.trim();
    const f = from.trim();
    const t = to.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (!n) return 'Enter a user name.';
    if ((f && !DATE.test(f)) || (t && !DATE.test(t))) return 'Enter the dates as YYYY-MM-DD.';
    try {
      const saved = await firstValueFrom(
        this.http.post<DelegationView>(this.delegationsUrl(sid), { user: n, from: f || null, to: t || null },
          { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) this.delegations.update((ds) => [...ds, saved]);
      return null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && typeof e.error?.message === 'string') return e.error.message;
      if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a user name and the dates as YYYY-MM-DD.';
      return e instanceof HttpErrorResponse ? `Could not delegate (${e.status}).` : 'Could not delegate.';
    }
  }

  private delegationsUrl(sid: string): string {
    return `/api/sheets/${encodeURIComponent(sid)}/delegations`;
  }

  private url(sid: string): string {
    return `/api/sheets/${encodeURIComponent(sid)}/roles`;
  }
}

const DATE = /^\d{4}-\d{2}-\d{2}$/;

function byUser(a: RoleView, b: RoleView): number {
  return a.user.localeCompare(b.user);
}
