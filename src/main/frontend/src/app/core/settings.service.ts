import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, forkJoin, of, tap } from 'rxjs';
import { SessionService } from './session.service';
import { SheetService } from './sheet.service';

export type Role = 'clerk' | 'steward';

export const ROLES: readonly Role[] = ['clerk', 'steward'];

export interface RoleEntry {
  user: string;
  role: Role;
}

interface SettingsView {
  /** Two decimals; null when there is no limit. */
  clerkLimit: string | null;
}

// The active sheet's settings and the roles it gives user names. A name with no role is a clerk; a
// clerk may settle a claim only when its due is at or below the clerk limit (none: no limit), a
// steward any claim.
@Injectable({ providedIn: 'root' })
export class SettingsService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionService);
  private readonly sheets = inject(SheetService);
  private readonly limit = signal<string | null>(null);
  private readonly list = signal<RoleEntry[]>([]);

  readonly clerkLimit = this.limit.asReadonly();
  readonly roles = this.list.asReadonly();

  /** Re-read the active sheet's settings and roles from the store. */
  reload(): Observable<unknown> {
    const id = this.sheets.sheetId();
    if (!id) {
      this.limit.set(null);
      this.list.set([]);
      return of(null);
    }
    const headers = this.session.headers();
    return forkJoin([
      this.http.get<SettingsView>(`${this.url(id)}/settings`, { headers }),
      this.http.get<RoleEntry[]>(`${this.url(id)}/roles`, { headers }),
    ]).pipe(tap(([settings, roles]) => {
      if (this.sheets.sheetId() !== id) return;
      this.limit.set(settings.clerkLimit);
      this.list.set(roles);
    }));
  }

  /** Save the clerk limit (null: no limit). */
  saveClerkLimit(clerkLimit: string | null): Observable<SettingsView> {
    const id = this.sheets.sheetId();
    if (!id) throw new Error('no sheet loaded');
    return this.http
      .put<SettingsView>(`${this.url(id)}/settings`, { clerkLimit }, { headers: this.session.headers() })
      .pipe(tap((s) => this.limit.set(s.clerkLimit)));
  }

  /** Give a user name a role, replacing the one it had; refused for any role but clerk or steward. */
  setRole(user: string, role: string): Observable<RoleEntry> {
    const id = this.sheets.sheetId();
    if (!id) throw new Error('no sheet loaded');
    return this.http
      .put<RoleEntry>(`${this.url(id)}/roles`, { user, role }, { headers: this.session.headers() })
      .pipe(tap((r) => this.list.update((all) => [...all.filter((o) => o.user !== r.user), r]
        .sort((a, b) => a.user.localeCompare(b.user)))));
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }
}
