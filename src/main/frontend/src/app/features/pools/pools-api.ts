import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A member of a pool, a person or another pool of the sheet (the other id null), with its share percent
 *  (two decimals) and the from-date and to-date of the membership (yyyy-mm-dd, each null when unbounded). */
export interface PoolMemberView {
  personId: number | null;
  poolId: number | null;
  share: string;
  from: string | null;
  to: string | null;
}

/** The dates a membership may carry, each null when unbounded. */
export interface MemberDates {
  from: string | null;
  to: string | null;
}

export interface PoolView {
  id: number;
  name: string;
  /** The members in the order they were added. */
  members: PoolMemberView[];
}

// The pools of a sheet, as the store holds them.
@Injectable({ providedIn: 'root' })
export class PoolsApi {
  private readonly http = inject(HttpClient);

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/pools`;
  }

  /** The sheet's pools in the order they were added. */
  list(sheetId: string): Promise<PoolView[]> {
    return firstValueFrom(this.http.get<PoolView[]>(this.url(sheetId)));
  }

  /** Add a pool: refused for a blank name or one the sheet already has. */
  add(sheetId: string, name: string): Promise<PoolView> {
    return firstValueFrom(this.http.post<PoolView>(this.url(sheetId), { name }));
  }

  /** Add a person, or another pool, to a pool with a share: refused when the person is in it already,
   *  when the pool is itself, a member of a pool already, contains it through any chain or would make a
   *  chain of pools more than three deep, or for a share that is not more than 0.00 and at most 100.00
   *  with up to two decimals, or a to-date before the from-date. The pool as it is then. */
  addMember(sheetId: string, poolId: number, member: { personId: number } | { poolId: number }, share: string,
      dates: MemberDates): Promise<PoolView> {
    return firstValueFrom(this.http.post<PoolView>(`${this.url(sheetId)}/${poolId}/members`, { ...member, share, ...dates }));
  }
}

// The active sheet's pools. Each load of a sheet re-reads them from the store.
@Injectable({ providedIn: 'root' })
export class Pools {
  private readonly session = inject(Session);
  private readonly api = inject(PoolsApi);

  private readonly state = signal<PoolView[]>([]);
  readonly pools = this.state.asReadonly();
  /** The refusal of the last loading of the pools, or blank. */
  readonly loadError = signal('');

  constructor() {
    effect(() => {
      const sheet = this.session.loaded();
      this.state.set([]);
      this.loadError.set('');
      if (sheet) void this.load(sheet.id);
    });
  }

  private async load(sheetId: string): Promise<void> {
    try {
      const list = await this.session.reading(this.api.list(sheetId));
      if (this.session.sheet()?.id === sheetId) this.state.set(list);
    } catch {
      if (this.session.sheet()?.id === sheetId) this.loadError.set('The pools could not be loaded.');
    }
  }

  /** Re-read the sheet's pools from the store, as after a change that moves their members. */
  refresh(sheetId: string): Promise<void> {
    return this.load(sheetId);
  }

  /** Add a pool to the active sheet. */
  async add(sheetId: string, name: string): Promise<void> {
    const added = await this.api.add(sheetId, name);
    if (this.session.sheet()?.id !== sheetId) return;
    this.state.update((list) => [...list.filter((p) => p.id !== added.id), added]);
  }

  /** Add a member to a pool of the active sheet; the store's answer replaces the pool. */
  async addMember(sheetId: string, poolId: number, member: { personId: number } | { poolId: number }, share: string,
      dates: MemberDates): Promise<void> {
    const pool = await this.api.addMember(sheetId, poolId, member, share, dates);
    if (this.session.sheet()?.id !== sheetId) return;
    this.state.update((list) => list.map((p) => (p.id === pool.id ? pool : p)));
  }
}
