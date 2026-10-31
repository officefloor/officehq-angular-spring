import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

/** A share percent the pool's row accepts: up to two decimals (more than 0.00 and at most 100.00 is checked apart). */
export const SHARE_PATTERN = /^\d{1,3}(\.\d{1,2})?$/;

/** A member of a pool: a person of the sheet with their share percent. */
export interface PoolMemberView {
  personId: number;
  name: string;
  share: string;
  /** Retired as soon as a retirement date is set: their share then counts as 0.00 toward the total. */
  retired: boolean;
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

  /** Add a person to a pool with a share; refused when they are already in it or the share is out of range. */
  addMember(sheetId: string, poolId: number, personId: number, share: string): Observable<PoolView> {
    return this.http.post<PoolView>(`${this.base(sheetId)}/${poolId}/members`, { personId, share }).pipe(
      tap((saved) => this.list.update((list) => list.map((p) => (p.id === saved.id ? saved : p)))),
    );
  }

  private base(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/pools`;
  }
}
