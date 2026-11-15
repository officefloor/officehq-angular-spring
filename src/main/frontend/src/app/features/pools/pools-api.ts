import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session, otherError } from '../../core/session';

/** A share percent: up to two decimals (more than 0.00 and at most 100.00 is checked apart). */
export const SHARE_PATTERN = /^\d{1,3}(\.\d{1,2})?$/;

/** A person, or another pool of the sheet, in a pool with their share percent (two decimals):
 *  exactly one of personId and pool is set. */
export interface PoolMember {
  personId: number | null;
  pool: string | null;
  share: string;
}

/** A member's key in its row's data-testid: the person's id, or pool-<name>. */
export function memberKey(m: PoolMember): string {
  return m.pool !== null ? `pool-${m.pool}` : String(m.personId);
}

/** A pool on the sheet: its name and its members, in the order they were added. */
export interface Pool {
  name: string;
  members: PoolMember[];
}

@Injectable({ providedIn: 'root' })
export class PoolsApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The sheet's pools, in the order they were added. */
  list(sheetId: string): Promise<Pool[]> {
    return firstValueFrom(this.http.get<Pool[]>(this.url(sheetId), { headers: this.headers() }));
  }

  /** Add a pool; resolves to the sheet's pools with it. */
  add(sheetId: string, name: string): Promise<Pool[]> {
    return firstValueFrom(this.http.post<Pool[]>(this.url(sheetId), { name }, { headers: this.headers() }));
  }

  /** Add a person, or another pool, to a pool with a share; resolves to the sheet's pools with them. */
  addMember(sheetId: string, pool: string, member: { personId: number } | { memberPool: string }, share: string): Promise<Pool[]> {
    return firstValueFrom(
      this.http.post<Pool[]>(`${this.url(sheetId)}/members`, { pool, ...member, share }, { headers: this.headers() }),
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
  if (e instanceof HttpErrorResponse && e.status === 409) return 'The sheet has a pool of that name already.';
  return otherError(e);
}

export function memberError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a share more than 0.00 and at most 100.00, with up to two decimals.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'That person is in the pool already, or that pool is a member of a pool already.';
  if (e instanceof HttpErrorResponse && e.status === 422) return 'A pool cannot contain itself, and a chain of pools is at most three deep.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That pool or person is no longer on the sheet; load the sheet again.';
  return otherError(e);
}
