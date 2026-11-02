import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/** A member of a pool: a person of the sheet and their share percent (two decimals). */
export interface PoolMember {
  personId: number;
  name: string;
  share: string;
  /** Whether the person has a retirement day set, whatever the day: their share then counts as 0.00. */
  retired: boolean;
  /** Their cut of the sheet's pot, as text with two decimals; null when the pool shows no cut (it is
   *  not balanced, or there is no pot). */
  cut: string | null;
}

/** A pool on the sheet: its members in the order they were added, their shares' total and whether
 *  it is exactly 100.00. */
export interface Pool {
  id: number;
  name: string;
  members: PoolMember[];
  totalShare: string;
  balanced: boolean;
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

  /** The sheet's pools in the order they were added. */
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
  refresh(): void {
    const sheetId = this.session.sheetId();
    if (sheetId) void this.reload(sheetId);
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

  /** Add a person to a pool with a share; refused (400) for a bad share and (409) when the person
   *  is already in the pool. */
  async addMember(poolId: number, personId: number, share: string): Promise<Pool> {
    const { sheetId, user } = this.active();
    const saved = await firstValueFrom(
      this.http.post<Pool>(`${this.url(sheetId)}/${poolId}/members`, { personId, share },
        { headers: { 'X-User': user } }),
    );
    this.keep(saved);
    return saved;
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
