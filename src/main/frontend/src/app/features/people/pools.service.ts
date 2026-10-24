import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A member of a pool: a person on the sheet or another pool (the other id null), its share percent and
 *  its cut of the pool's pot (two decimals; the cut null when the pool is unbalanced, has no pot or only retired members, or the
 *  member's dates leave out the sheet's today). A pool
 *  that is a member of none has the sheet's pot; one that is a member of another has its cut there. */
export interface PoolMember {
  personId: number | null;
  poolId: number | null;
  name: string;
  share: string;
  cut: string | null;
}

/** A pool on the sheet: its members, their total share (a retired member's counting as 0.00) and
 *  whether that total is exactly 100.00. A crew is also a pool: then the id is the crew's (crewId too),
 *  and its members are the crew's lead and members at equal shares. */
export interface Pool {
  id: number;
  crewId: number | null;
  name: string;
  members: PoolMember[];
  totalShare: string;
  balanced: boolean;
}

// The pools on a sheet, read from and written to the server, and kept for the sheet loaded: re-read
// from the store every time a sheet is loaded.
@Injectable({ providedIn: 'root' })
export class PoolsService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The loaded sheet's pools, in the order they were added. */
  readonly pools = signal<Pool[]>([]);

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      this.session.loads();
      this.pools.set([]);
      if (sheet) void this.read(sheet);
    });
  }

  /** Add a pool. Refused (409) when the sheet has a pool or a crew by that name and (400) when it is blank. */
  async add(sheet: string, name: string): Promise<void> {
    const all = await firstValueFrom(
      this.http.post<Pool[]>(this.url(sheet), { name }, { headers: this.session.headers() }),
    );
    if (this.session.sheetId() === sheet) this.pools.set(all);
  }

  /** Add a person, or another pool, to a pool with a share. Refused (409) when the person is already in it,
   *  or the other pool is already in a pool, contains it, or would make a chain more than three deep; and (400)
   *  for a bad share or a to-date before the from-date. The dates (yyyy-mm-dd) are blank for none. */
  async addMember(
    sheet: string,
    poolId: number,
    member: { personId: number } | { poolId: number },
    share: string,
    from = '',
    to = '',
  ): Promise<void> {
    const all = await firstValueFrom(
      this.http.post<Pool[]>(
        `${this.url(sheet)}/${poolId}/members`,
        { ...member, share, from, to },
        { headers: this.session.headers() },
      ),
    );
    if (this.session.sheetId() === sheet) this.pools.set(all);
  }

  /** Re-read the sheet's pools from the store: a change elsewhere (a merge of people) moved them. */
  refresh(sheet: string): Promise<void> {
    return this.read(sheet);
  }

  private async read(sheet: string): Promise<void> {
    try {
      const all = await firstValueFrom(this.http.get<Pool[]>(this.url(sheet), { headers: this.session.headers() }));
      if (this.session.sheetId() === sheet) this.pools.set(all);
    } catch {
      // The pools could not be read; they show as none.
    }
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/pools`;
  }
}
