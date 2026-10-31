import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

/** A steward's delegation of the steward role to a user, from a from-date to a to-date, inclusive. */
export interface DelegationView {
  id: number;
  user: string;
  by: string;
  from: string;
  to: string;
}

/** A delegation as the form submits it; a blank date is the sheet's today. */
export interface DelegationForm {
  user: string;
  from: string | null;
  to: string | null;
}

// The steward role's delegations on the active sheet, in the order they were made.
@Injectable({ providedIn: 'root' })
export class Delegations {
  private readonly http = inject(HttpClient);
  private readonly list = signal<DelegationView[]>([]);
  readonly all = this.list.asReadonly();

  /** Re-read a sheet's delegations from the store; null clears them. */
  load(sheetId: string | null): Observable<DelegationView[]> | null {
    this.list.set([]);
    if (sheetId === null) return null;
    return this.http.get<DelegationView[]>(this.base(sheetId)).pipe(tap((list) => this.list.set(list)));
  }

  /** Delegate the steward role; refused unless the signed-in user is a steward and the range runs forward. */
  delegate(sheetId: string, form: DelegationForm): Observable<DelegationView> {
    return this.http
      .post<DelegationView>(this.base(sheetId), form)
      .pipe(tap((saved) => this.list.update((list) => [...list, saved])));
  }

  private base(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/delegations`;
  }
}
