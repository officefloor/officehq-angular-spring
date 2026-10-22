import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** A pool's member: a person on the sheet or another pool (the other null), its name, and its share percent with two decimals. */
export interface PoolMemberView {
  personId: number | null;
  poolId: number | null;
  name: string;
  share: string;
}

/** A pool on the sheet: a name, once per sheet, and its members in the order they were added. */
export interface PoolView {
  id: number;
  name: string;
  members: PoolMemberView[];
}

// The sheet's pools, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class PoolsApi {
  private readonly http = inject(HttpClient);

  /** The sheet's pools in the order they were added. */
  list(sheet: string): Promise<PoolView[]> {
    return firstValueFrom(this.http.get<PoolView[]>(this.url(sheet)));
  }

  /** Add a pool; refused when its name, trimmed, is blank or already a pool's. */
  add(sheet: string, name: string): Promise<PoolView> {
    return firstValueFrom(this.http.post<PoolView>(this.url(sheet), { name }));
  }

  /**
   * Add a person, or another pool, to a pool with a share; refused when the person is in it already, the
   * share is bad, or the member pool is in a pool already, would contain itself, or would be too deep.
   */
  addMember(sheet: string, poolId: number, member: { personId: number } | { poolId: number }, share: string): Promise<PoolView> {
    return firstValueFrom(this.http.post<PoolView>(`${this.url(sheet)}/${poolId}/members`, { ...member, share }));
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/pools`;
  }
}
