import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, switchMap, tap } from 'rxjs';

/** A share percent the pool's row accepts: up to two decimals (more than 0.00 and at most 100.00 is checked apart). */
export const SHARE_PATTERN = /^\d{1,3}(\.\d{1,2})?$/;

/** A member of a pool: a person of the sheet, or another pool of it, with its share percent. */
export interface PoolMemberView {
  /** The person that is the member; null when the member is a pool. */
  personId: number | null;
  /** The pool that is the member; null when the member is a person. */
  memberPoolId: number | null;
  /** The person's name, or the member pool's. */
  name: string;
  share: string;
  /** Retired as soon as a retirement date is set: their share then counts as 0.00 toward the total. */
  retired: boolean;
  /** The first and last day the membership is in force (yyyy-mm-dd); null: no start, no end. Outside them, on the
   *  sheet's today, the member is left out of the pool's total share and has no cut. */
  from: string | null;
  to: string | null;
  /** Their cut of the pool's pot (two decimals); null when the pool has no pot or is not balanced, or the member's
   *  dates do not include the sheet's today. A pool that is a
   *  member of another has its cut there as its pot; one that is a member of none has the sheet's. */
  cut: string | null;
}

/** A pool on the sheet: its members, their total share, and balanced when that is exactly 100.00. */
export interface PoolView {
  id: number;
  name: string;
  members: PoolMemberView[];
  totalShare: string;
  balanced: boolean;
}

// The pools on the active sheet, in the order they were added.
@Injectable({ providedIn: 'root' })
export class Pools {
  private readonly http = inject(HttpClient);
  private readonly list = signal<PoolView[]>([]);
  readonly all = this.list.asReadonly();

  /** Re-read a sheet's pools from the store; null clears them. */
  load(sheetId: string | null): Observable<PoolView[]> | null {
    this.list.set([]);
    if (sheetId === null) return null;
    return this.http.get<PoolView[]>(this.base(sheetId)).pipe(tap((list) => this.list.set(list)));
  }

  /** Add a pool; refused when the name is blank or already a pool on the sheet. */
  add(sheetId: string, name: string): Observable<PoolView> {
    return this.http.post<PoolView>(this.base(sheetId), { name }).pipe(
      tap((added) => this.list.update((list) => [...list, added])),
    );
  }

  /** Add a person or another pool to a pool with a share; refused when the person is already in it, the share is
   *  out of range, the to-date is before the from-date, or the pool member is already in a pool, would contain itself
   *  or make a chain over three deep. The from-date and to-date are optional (null).
   *  Every pool is re-read after, since a pool's pot follows its cut in the pool above. */
  addMember(sheetId: string, poolId: number, member: { personId: number } | { memberPoolId: number },
            share: string, from: string | null, to: string | null): Observable<PoolView[]> {
    return this.http.post<PoolView>(`${this.base(sheetId)}/${poolId}/members`, { ...member, share, from, to }).pipe(
      switchMap(() => this.http.get<PoolView[]>(this.base(sheetId))),
      tap((list) => this.list.set(list)),
    );
  }

  private base(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/pools`;
  }
}
