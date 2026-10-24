import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A member of a pool: a person on the sheet, their share percent and their cut of the sheet's pot
 *  (two decimals; the cut null when the pool is unbalanced or there is no pot). */
export interface PoolMember {
  personId: number;
  name: string;
  share: string;
  cut: string | null;
}

/** A pool on the sheet: its members, their total share (a retired member's counting as 0.00) and
 *  whether that total is exactly 100.00. */
export interface Pool {
  id: number;
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

  /** Add a pool. Refused (409) when the sheet has one by that name and (400) when it is blank. */
  async add(sheet: string, name: string): Promise<void> {
    const all = await firstValueFrom(
      this.http.post<Pool[]>(this.url(sheet), { name }, { headers: this.session.headers() }),
    );
    if (this.session.sheetId() === sheet) this.pools.set(all);
  }

  /** Add a person to a pool with a share. Refused (409) when they are already in it and (400) for a bad share. */
  async addMember(sheet: string, poolId: number, personId: number, share: string): Promise<void> {
    const all = await firstValueFrom(
      this.http.post<Pool[]>(`${this.url(sheet)}/${poolId}/members`, { personId, share }, { headers: this.session.headers() }),
    );
    if (this.session.sheetId() === sheet) this.pools.set(all);
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
