import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/** A member of a pool: a person of the sheet, or another pool of it, and their share percent (two
 *  decimals). */
export interface PoolMember {
  /** The person; null when the member is a pool. */
  personId: number | null;
  /** The pool; null when the member is a person. */
  poolId: number | null;
  name: string;
  share: string;
  /** Whether the person has a retirement day set, whatever the day: they then get no
   *  cut, their share spread over the other members. */
  retired: boolean;
  /** Their cut of the pool's pot, as text with two decimals; null when the pool shows no cut (it is
   *  not balanced, or it has no pot). */
  cut: string | null;
  /** The first day of the membership (yyyy-mm-dd); null: no start. */
  from: string | null;
  /** The last day of the membership (yyyy-mm-dd); null: no end. */
  to: string | null;
  /** Whether the membership's dates include the sheet's today: a member out of force is left out
   *  entirely (not in the total share, not in the balance, no cut). */
  inForce: boolean;
  /** Their cut for each period that is closed or soft-closed (fixed as it stood when it was
   *  closed) or whose to-date is on or before the sheet's today (as it stood at the end of the
   *  period), by the period's name, earliest period first; null for a period with none then. */
  periodCuts: Record<string, string | null>;
}

/** A pool on the sheet: its members in the order they were added, their shares' total and whether
 *  it is exactly 100.00. A crew is also a pool: its members are then the crew's lead and members at
 *  equal shares, and none can be added to it here. */
export interface Pool {
  id: number;
  name: string;
  members: PoolMember[];
  totalShare: string;
  balanced: boolean;
  /** Whether the pool is a crew's. */
  crew: boolean;
}

/** A share as entered: a number with at most two decimals. */
const SHARE = /^\d{1,3}(\.\d{1,2})?$/;

/** A share as entered in hundredths, when it has at most two decimals and is above 0.00 and at
 *  most 100.00; null otherwise. */
export function shareCents(text: string): number | null {
  const t = text.trim();
  if (!SHARE.test(t)) return null;
  const [whole, frac = ''] = t.split('.');
  const cents = Number(whole) * 100 + Number(frac.padEnd(2, '0'));
  return cents > 0 && cents <= 10000 ? cents : null;
}

/** Hundredths as text with two decimals. */
export function formatCents(cents: number): string {
  return `${Math.trunc(cents / 100)}.${String(cents % 100).padStart(2, '0')}`;
}

// The pools of the active sheet, re-read whenever the sheet is loaded.
@Injectable({ providedIn: 'root' })
export class Pools {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The sheet's pools in the order they were added, then the crews' pools. */
  readonly list = signal<Pool[]>([]);
  private reads = 0;

  constructor() {
    effect(() => {
      this.session.loads();
      const sheetId = this.session.sheetId();
      this.list.set([]);
      if (sheetId) void this.reload(sheetId);
    });
  }

  /** Read the active sheet's pools again (the pot they cut has changed). */
  async refresh(): Promise<void> {
    const sheetId = this.session.sheetId();
    if (sheetId) await this.reload(sheetId);
  }

  /** Add a pool; refused (400) when the name is blank and (409) when it is already a pool. */
  async add(name: string): Promise<Pool> {
    const { sheetId, user } = this.active();
    const saved = await firstValueFrom(
      this.http.post<Pool>(this.url(sheetId), { name }, { headers: { 'X-User': user } }),
    );
    this.keep(saved);
    return saved;
  }

  /** Add a person, or another pool, to a pool with a share; refused (400) for a bad share and (409)
   *  when the person is already in the pool, or the pool is already a member of a pool, would contain
   *  itself or would make a chain of pools too deep; the optional from- and to-dates bound the days
   *  the membership runs (refused, 400, when the to-date is before the from-date). */
  async addMember(
    poolId: number,
    member: { personId: number } | { poolId: number },
    share: string,
    dates: { from: string | null; to: string | null },
  ): Promise<void> {
    const { sheetId, user } = this.active();
    await firstValueFrom(
      this.http.post<Pool>(`${this.url(sheetId)}/${poolId}/members`, { ...member, share, ...dates },
        { headers: { 'X-User': user } }),
    );
    // a pool member changes the pots of the pools below it: read them all again
    await this.reload(sheetId);
  }

  private keep(saved: Pool): void {
    this.list.update((ps) =>
      ps.some((p) => p.id === saved.id) ? ps.map((p) => (p.id === saved.id ? saved : p)) : [...ps, saved],
    );
  }

  private async reload(sheetId: string): Promise<void> {
    const read = ++this.reads;
    try {
      const pools = await firstValueFrom(this.http.get<Pool[]>(this.url(sheetId)));
      if (read === this.reads) this.list.set(pools);
    } catch {
      // the list stays empty; the next load reads it again
    }
  }

  private active(): { sheetId: string; user: string } {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    return { sheetId, user };
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/pools`;
  }
}
