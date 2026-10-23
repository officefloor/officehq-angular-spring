import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { PersonView } from './people';

/**
 * A pool's member: a person on the sheet or another pool (the other null), its name, its share percent with
 * two decimals, and the dates (yyyy-mm-dd) the membership is in force between (null: open-ended).
 */
export interface PoolMemberView {
  personId: number | null;
  poolId: number | null;
  name: string;
  share: string;
  from: string | null;
  to: string | null;
}

/**
 * Whether a membership is in force on a day (yyyy-mm-dd): within its from-date and to-date, each optional;
 * with no day, every membership is.
 */
export function memberOn(m: PoolMemberView, day: string): boolean {
  return day === '' || ((m.from === null || m.from <= day) && (m.to === null || day <= m.to));
}

/**
 * A pool on the sheet: a name, once per sheet and never a crew's but its own crew's, the crew it is (null
 * for a pool of its own), and its members in the order they were added. A crew's pool's members are the
 * crew's lead and members at equal shares, the remainder on the lead.
 */
export interface PoolView {
  id: number;
  name: string;
  crewId: number | null;
  members: PoolMemberView[];
}

/**
 * A period whose to-date is on or before the sheet's today, or that is closed or soft-closed, with what its
 * pools' cuts are worked out from, on the day `to` as the sheet's today: for a closed or soft-closed period
 * the day it was closed and the pot (null for none), people and pools as they stood at that moment; else
 * when its to-date is the sheet's today (current) the sheet as it stands, else the pot, people and pools as
 * they stood at the end of its to-date.
 */
export interface PeriodPoolsView {
  period: string;
  to: string;
  current: boolean;
  pot: string | null;
  people: PersonView[];
  pools: PoolView[];
}

// The sheet's pools, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class PoolsApi {
  private readonly http = inject(HttpClient);

  /** The sheet's pools in the order they were added. */
  list(sheet: string): Promise<PoolView[]> {
    return firstValueFrom(this.http.get<PoolView[]>(this.url(sheet)));
  }

  /** Each period ended by the sheet's today or fixed by a close, with its pools as they stood then. */
  history(sheet: string): Promise<PeriodPoolsView[]> {
    return firstValueFrom(this.http.get<PeriodPoolsView[]>(`${this.url(sheet)}/history`));
  }

  /** Add a pool; refused when its name, trimmed, is blank or already a pool's or a crew's. */
  add(sheet: string, name: string): Promise<PoolView> {
    return firstValueFrom(this.http.post<PoolView>(this.url(sheet), { name }));
  }

  /**
   * Add a person, or another pool, to a pool with a share; refused when the person is in it already, the
   * share is bad, the to-date is before the from-date, or the member pool is in a pool already, would
   * contain itself, or would be too deep. A date left out (null) leaves that end open.
   */
  addMember(
    sheet: string,
    poolId: number,
    member: { personId: number } | { poolId: number },
    share: string,
    from: string | null,
    to: string | null,
  ): Promise<PoolView> {
    return firstValueFrom(this.http.post<PoolView>(`${this.url(sheet)}/${poolId}/members`, { ...member, share, from, to }));
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/pools`;
  }
}
