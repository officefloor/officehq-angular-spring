import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** The steward role delegated to a user by a steward, from a date to a date (yyyy-mm-dd), inclusive. */
export interface DelegationView {
  id: number;
  user: string;
  steward: string;
  from: string;
  to: string;
}

export interface DelegationForm {
  user: string;
  from: string;
  to: string;
}

// The sheet's delegations of the steward role, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class DelegationsApi {
  private readonly http = inject(HttpClient);

  /** The delegations made on the sheet, in the order they were made. */
  list(sheet: string): Promise<DelegationView[]> {
    return firstValueFrom(this.http.get<DelegationView[]>(this.url(sheet)));
  }

  /** Delegate the steward role; refused unless the signed-in user is a steward, or when to is before from. */
  delegate(sheet: string, form: DelegationForm): Promise<DelegationView> {
    return firstValueFrom(this.http.post<DelegationView>(this.url(sheet), form));
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/delegations`;
  }
}
