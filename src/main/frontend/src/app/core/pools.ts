import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/** A person in a pool with their share percent; a retired one counts 0.00 toward the pool's total. */
export interface PoolMemberView {
  personId: number;
  name: string;
  share: string;
  retired: boolean;
  /** Their cut of the sheet's pot, two decimals (0.00 when retired); null when the pool is unbalanced or
   *  there is no pot. */
  cut: string | null;
}

/** A pool: its members, their total share, and whether that is exactly 100.00. */
export interface PoolView {
  id: number;
  name: string;
  members: PoolMemberView[];
  totalShare: string;
  balanced: boolean;
}

const SHARE = /^\d{1,3}(\.\d{1,2})?$/;

// The active sheet's pools, re-read from the server each time the sheet is loaded.
@Injectable({ providedIn: 'root' })
export class Pools {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** In the order they were added. */
  readonly pools = signal<PoolView[]>([]);
  private loads = 0;

  /** Re-read the active sheet's pools from the server; an error message when that failed. */
  async load(): Promise<string | null> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    const load = ++this.loads;
    this.pools.set([]);
    if (!user || !sid) return null;
    try {
      const pools = await firstValueFrom(this.http.get<PoolView[]>(this.url(sid), { headers: { 'X-User': user } }));
      if (load === this.loads) this.pools.set(pools);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not load pools (${e.status}).` : 'Could not load pools.';
    }
  }

  /** Add a pool; an error message when refused (blank, or the name already taken). */
  async add(name: string): Promise<string | null> {
    const n = name.trim();
    if (!n) return 'Enter a pool name.';
    return this.post('', { name: n }, 'add pool');
  }

  /** Add a person to a pool with a share; an error message when refused (already in it, or a bad share). */
  async addMember(poolId: number, personId: number | null, share: string): Promise<string | null> {
    const s = share.trim();
    if (personId === null) return 'Choose a person.';
    if (!SHARE.test(s) || Number(s) <= 0 || Number(s) > 100) {
      return 'Enter a share more than 0.00 and at most 100.00, in two decimals.';
    }
    return this.post(`/${poolId}/members`, { personId, share: s }, 'add pool member');
  }

  private async post(path: string, body: object, what: string): Promise<string | null> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    if (!user || !sid) return 'Load a sheet first.';
    try {
      const saved = await firstValueFrom(
        this.http.post<PoolView>(this.url(sid) + path, body, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) {
        this.pools.update((ps) =>
          ps.some((p) => p.id === saved.id) ? ps.map((p) => (p.id === saved.id ? saved : p)) : [...ps, saved],
        );
      }
      return null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && typeof e.error?.message === 'string') return e.error.message;
      return e instanceof HttpErrorResponse ? `Could not ${what} (${e.status}).` : `Could not ${what}.`;
    }
  }

  private url(sid: string): string {
    return `/api/sheets/${encodeURIComponent(sid)}/pools`;
  }
}
