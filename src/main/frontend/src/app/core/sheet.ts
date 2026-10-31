import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

export interface SheetView {
  id: string;
  /** The sheet's today (yyyy-mm-dd): every date-dependent decision and default uses it, never the clock. */
  today: string;
  /** A clerk can settle a claim only when its due is at or below this (two decimals); null: no limit. */
  clerkLimit: string | null;
  /** A claim's live due is at least this (two decimals). */
  floor: string;
  /** The sheet's terms in days: a claim is due by its date plus these. */
  terms: number;
}

/** The terms a sheet has until they are changed. */
export const DEFAULT_TERMS = 14;

// The active sheet. Everything else in the app is kept per sheet; loading an id re-reads it from
// the store, and an id never loaded before starts an empty sheet.
@Injectable({ providedIn: 'root' })
export class Sheets {
  private readonly http = inject(HttpClient);
  private readonly active = signal<SheetView | null>(null);
  readonly current = this.active.asReadonly();
  /** The sheet as last (re)loaded: set on each load only, so changing its today re-reads nothing. */
  private readonly lastLoad = signal<SheetView | null>(null);
  readonly loaded = this.lastLoad.asReadonly();

  load(id: string): Observable<SheetView> {
    return this.http
      .post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/load`, null)
      .pipe(
        tap((s) => {
          this.active.set(s);
          this.lastLoad.set(s);
        }),
      );
  }

  /** Change the active sheet's today. */
  setToday(id: string, today: string): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/today`, { today })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Change the active sheet's settings; a null clerk limit means no limit. */
  setSettings(id: string, clerkLimit: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings`, { clerkLimit })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Change the active sheet's floor. */
  setFloor(id: string, floor: string): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/floor`, { floor })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Change the active sheet's terms (days). */
  setTerms(id: string, terms: number): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/terms`, { terms })
      .pipe(tap((s) => this.active.set(s)));
  }
}
