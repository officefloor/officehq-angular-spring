import { HttpClient, HttpContext } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { LIVE_READ } from '../../core/as-of.interceptor';

/** A person, date and amount shared by two or more of the person's claims (VOID and DELETED left out). */
export interface Duplicate {
  name: string;
  date: string;
  /** The amount with two decimals. */
  amount: string;
  count: number;
}

// The sheet's duplicate claims: people in list order, then by date, then by amount. Read live, as the
// store holds them now.
@Injectable({ providedIn: 'root' })
export class DuplicatesApi {
  private readonly http = inject(HttpClient);

  list(sheetId: string): Promise<Duplicate[]> {
    return firstValueFrom(this.http.get<Duplicate[]>(`/api/sheets/${encodeURIComponent(sheetId)}/duplicate-claims`,
      { context: new HttpContext().set(LIVE_READ, true) }));
  }
}
