import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { hundredths } from './rates';
import { Session } from './session';

/** A member of a pool with its share percent: a person (personId), or another pool (poolId). A retired
 *  person counts 0.00 toward the pool's total. */
export interface PoolMemberView {
  personId: number | null;
  poolId: number | null;
  name: string;
  share: string;
  retired: boolean;
  /** Their cut of the pool's pot, two decimals (0.00 when retired or on hold on the sheet's today, their
   *  share spread over the others with a cut); null when the pool is unbalanced or
   *  has no pot. A pool in another has its cut there as its pot; one in none has the sheet's pot. */
  cut: string | null;
}

/** A pool: its members, their total share, and whether that is exactly 100.00. */
/** A member to add: a person or another pool, by id. */
export type PoolMemberChoice = { personId: number } | { poolId: number };

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

  /** Each person's cuts across every pool they are a member of, added up, in hundredths, by person id; a pool
   *  that shows no cuts adds nothing. */
  readonly cutCents = computed(() => {
    const out = new Map<number, bigint>();
    for (const pool of this.pools()) {
      for (const m of pool.members) {
        if (m.personId !== null && m.cut !== null) {
          out.set(m.personId, (out.get(m.personId) ?? 0n) + hundredths(m.cut));
        }
      }
    }
    return out;
  });

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

  /** Add a person or another pool to a pool with a share; an error message when refused (already in it, a
   *  bad share, or a pool that would contain itself, is already in one, or would make a chain too deep). The
   *  pools are re-read after: a pool in another takes its pot from its cut there. */
  async addMember(poolId: number, member: PoolMemberChoice | null, share: string): Promise<string | null> {
    const s = share.trim();
    if (member === null) return 'Choose a person or a pool.';
    if (!SHARE.test(s) || Number(s) <= 0 || Number(s) > 100) {
      return 'Enter a share more than 0.00 and at most 100.00, in two decimals.';
    }
    const err = await this.post(`/${poolId}/members`, { ...member, share: s }, 'add pool member');
    if (!err) await this.reread();
    return err;
  }

  /** Re-read the active sheet's pools without clearing the ones shown meanwhile. */
  private async reread(): Promise<void> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    if (!user || !sid) return;
    const load = ++this.loads;
    try {
      const pools = await firstValueFrom(this.http.get<PoolView[]>(this.url(sid), { headers: { 'X-User': user } }));
      if (load === this.loads && this.session.sheetId() === sid) this.pools.set(pools);
    } catch {
      // the pool just saved is shown already; the rest are re-read on the next load
    }
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
