import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

/** A void reason on the sheet: while the list is not empty, a claim is voided with one of them. */
export interface VoidReasonView {
  id: number;
  reason: string;
}

// The void reasons on the active sheet, in the order they were added.
@Injectable({ providedIn: 'root' })
export class VoidReasons {
  private readonly http = inject(HttpClient);
  private readonly list = signal<VoidReasonView[]>([]);
  readonly all = this.list.asReadonly();

  /** Re-read a sheet's void reasons from the store; null clears them. */
  load(sheetId: string | null): Observable<VoidReasonView[]> | null {
    this.list.set([]);
    if (sheetId === null) return null;
    return this.http.get<VoidReasonView[]>(this.base(sheetId)).pipe(tap((list) => this.list.set(list)));
  }

  /** Add a void reason; refused when blank or already on the list. */
  add(sheetId: string, reason: string): Observable<VoidReasonView> {
    return this.http.post<VoidReasonView>(this.base(sheetId), { reason }).pipe(
      tap((saved) => this.list.update((list) => [...list, saved])),
    );
  }

  private base(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/void-reasons`;
  }
}
