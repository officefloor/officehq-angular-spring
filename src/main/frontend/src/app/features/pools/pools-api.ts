import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session, otherError } from '../../core/session';
import { money } from '../claims/claims-api';
import { type Crew, crewIds } from '../crews/crews-api';
import { type Hold, patronShares } from '../people/people-api';

/** A share percent: up to two decimals (more than 0.00 and at most 100.00 is checked apart). */
export const SHARE_PATTERN = /^\d{1,3}(\.\d{1,2})?$/;

/** A from- or to-date of a membership: yyyy-mm-dd. */
export const MEMBER_DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

/** A person, or another pool of the sheet, in a pool with their share percent (two decimals):
 *  exactly one of personId and pool is set. The membership may carry a from- and a to-date
 *  (yyyy-mm-dd, both inclusive, null when not given). */
export interface PoolMember {
  personId: number | null;
  pool: string | null;
  share: string;
  from: string | null;
  to: string | null;
}

/** Whether the membership's dates include the day (yyyy-mm-dd): a member outside them is left out
 *  of the pool entirely. With no day known, every member is in. */
export function memberOn(m: PoolMember, day: string | null): boolean {
  if (day === null) return true;
  return (m.from === null || m.from <= day) && (m.to === null || day <= m.to);
}

/** A member's key in its row's data-testid: the person's id, or pool-<name>. */
export function memberKey(m: PoolMember): string {
  return m.pool !== null ? `pool-${m.pool}` : String(m.personId);
}

/** A pool on the sheet: its name and its members, in the order they were added. A crew is a pool
 *  too (crew set): its lead and its members at equal shares, kept by the crew itself. */
export interface Pool {
  name: string;
  members: PoolMember[];
  crew?: boolean;
}

/** A crew as a pool under its name: its lead, then its members, at equal shares (100.00 split as
 *  patrons' shares are, the remainder on the lead, or on the first member when it has no lead). */
export function crewPool(crew: Crew): Pool {
  const ids = crewIds(crew);
  const shares = patronShares(ids.length);
  return {
    name: crew.name,
    members: ids.map((personId, i) => ({ personId, pool: null, share: money(shares[i]), from: null, to: null })),
    crew: true,
  };
}

/** A person as a period's cuts need them: their id, the date they are retired from (null when not
 *  retired) and their holds. */
export interface PersonThen {
  id: number;
  retiredFrom: string | null;
  holds: Hold[];
}

/** A period whose to-date is on or before the sheet's today, with what its cuts are worked from as
 *  it all stood at the end of that to-date (the day the cuts are taken on): the pot then (null when
 *  none), the pools and crews then, and the people then. */
export interface PeriodThen {
  period: string;
  to: string;
  pot: string | null;
  pools: Pool[];
  crews: Crew[];
  people: PersonThen[];
}

@Injectable({ providedIn: 'root' })
export class PoolsApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The sheet's pools, in the order they were added. */
  list(sheetId: string): Promise<Pool[]> {
    return firstValueFrom(this.http.get<Pool[]>(this.url(sheetId), { headers: this.headers() }));
  }

  /** Each period whose to-date is on or before the sheet's today, by from-date, as it all stood at
   *  the end of its to-date. */
  history(sheetId: string): Promise<PeriodThen[]> {
    return firstValueFrom(this.http.get<PeriodThen[]>(`${this.url(sheetId)}/history`, { headers: this.headers() }));
  }

  /** Add a pool; resolves to the sheet's pools with it. */
  add(sheetId: string, name: string): Promise<Pool[]> {
    return firstValueFrom(this.http.post<Pool[]>(this.url(sheetId), { name }, { headers: this.headers() }));
  }

  /** Add a person, or another pool, to a pool with a share; resolves to the sheet's pools with them. */
  addMember(sheetId: string, pool: string, member: { personId: number } | { memberPool: string }, share: string,
    from: string, to: string): Promise<Pool[]> {
    return firstValueFrom(
      this.http.post<Pool[]>(`${this.url(sheetId)}/members`, { pool, ...member, share, from, to }, { headers: this.headers() }),
    );
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/pools`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}

export function poolError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a name for the pool.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'The sheet has a pool or a crew of that name already.';
  return otherError(e);
}

export function memberError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a share more than 0.00 and at most 100.00, with up to two decimals, and dates as yyyy-mm-dd.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'That person is in the pool already, or that pool is a member of a pool already.';
  if (e instanceof HttpErrorResponse && e.status === 422) return 'A pool cannot contain itself, a chain of pools is at most three deep, and a to-date is not before the from-date.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That pool or person is no longer on the sheet; load the sheet again.';
  return otherError(e);
}
