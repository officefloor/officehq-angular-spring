import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/** clerk (any name not given another role) or steward. */
export type RoleName = 'clerk' | 'steward';

export const ROLES: RoleName[] = ['clerk', 'steward'];

export interface Role {
  user: string;
  role: RoleName;
}

// The roles given to user names on the active sheet, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class Roles {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly all = signal<Role[]>([]);

  async reload(): Promise<void> {
    try {
      this.all.set(await firstValueFrom(this.http.get<Role[]>(this.url(), { headers: this.headers() })));
    } catch {
      this.all.set([]);
    }
  }

  /** Give a user name a role, replacing any it had. */
  async set(user: string, role: RoleName): Promise<void> {
    const saved = await firstValueFrom(
      this.http.put<Role>(this.url(), { user: user.trim(), role }, { headers: this.headers() }),
    );
    this.all.update((list) =>
      [...list.filter((r) => r.user !== saved.user), saved].sort((a, b) => a.user.localeCompare(b.user)),
    );
  }

  private url(): string {
    const id = this.session.sheetId();
    if (!id) throw new Error('no sheet loaded');
    return `/api/sheets/${encodeURIComponent(id)}/roles`;
  }

  private headers(): Record<string, string> {
    const user = this.session.user();
    if (!user) throw new Error('not signed in');
    return { 'X-User': user };
  }
}
