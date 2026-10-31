import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

/** The roles a sheet's settings can assign; a name with none assigned is a clerk. */
export const ROLES = ['clerk', 'steward'] as const;
export type RoleName = (typeof ROLES)[number];

export interface RoleView {
  user: string;
  role: RoleName;
}

// The roles assigned to user names on the active sheet.
@Injectable({ providedIn: 'root' })
export class Roles {
  private readonly http = inject(HttpClient);
  private readonly list = signal<RoleView[]>([]);
  readonly all = this.list.asReadonly();

  /** Re-read a sheet's roles from the store; null clears them. */
  load(sheetId: string | null): Observable<RoleView[]> | null {
    this.list.set([]);
    if (sheetId === null) return null;
    return this.http.get<RoleView[]>(this.base(sheetId)).pipe(tap((roles) => this.list.set(roles)));
  }

  /** Assign a role to a user name, replacing the one it had. */
  set(sheetId: string, user: string, role: RoleName): Observable<RoleView> {
    return this.http
      .put<RoleView>(`${this.base(sheetId)}/${encodeURIComponent(user)}`, { role })
      .pipe(
        tap((saved) =>
          this.list.update((list) =>
            [...list.filter((r) => r.user !== saved.user), saved].sort((a, b) => (a.user < b.user ? -1 : a.user > b.user ? 1 : 0)),
          ),
        ),
      );
  }

  private base(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/roles`;
  }
}
