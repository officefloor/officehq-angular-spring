import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { Grade } from './people';

/** A dated setting's value from its from-date (yyyy-mm-dd; null: from the beginning of time) until the next one's
 *  of the same key; a null value means none. Keys: clerk-limit, floor, terms, terms-A/B/C, late-pct, cap. */
export interface DatedSetting {
  key: string;
  from: string | null;
  value: string | null;
}

/** The sheet as the screen shows it: each dated setting as it is in force on its today. */
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
  /** The sheet's late percent (two decimals): an overdue claim's due gains this percent of its figure
   *  for every full 30 days it is overdue. */
  latePct: string;
  /** The sheet's policy date (yyyy-mm-dd): a claim dated before it rounds half up, one on or after it half to
   *  even; null when it is the sheet's today. */
  policyDate: string | null;
  /** The sheet's period numbering from (yyyy-mm-dd): while its today is on or after it, a claim opened is
   *  numbered within its period; null when off. */
  periodNumberingFrom: string | null;
  /** The terms in days set per grade; a grade without its own uses the sheet's terms. */
  gradeTerms: Partial<Record<Grade, number>>;
  /** A person's due-total is at most this (two decimals); null: none. */
  cap: string | null;
  /** Every dated value recorded for the settings that carry a figure. */
  settings: DatedSetting[];
}

/** A setting's value in force on a date (yyyy-mm-dd): the one recorded with the latest from-date on or before it;
 *  `start` (the sheet's starting value) before every from-date, or when it is none. */
export function settingOn(settings: readonly DatedSetting[], key: string, date: string, start: string): string {
  let inForce: DatedSetting | undefined;
  for (const s of settings) {
    if (s.key === key && (s.from === null || s.from <= date) && (inForce === undefined || inForce.from === null
        || (s.from !== null && s.from > inForce.from))) inForce = s;
  }
  return inForce?.value ?? start;
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

  /** Record the active sheet's clerk limit from a date (null: its today); a null clerk limit means no limit. */
  setSettings(id: string, clerkLimit: string | null, from: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings`, { clerkLimit, from })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Record the active sheet's floor from a date (null: its today). */
  setFloor(id: string, floor: string, from: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/floor`, { floor, from })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Record the active sheet's terms (days) from a date (null: its today). */
  setTerms(id: string, terms: number, from: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/terms`, { terms, from })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Record a grade's own terms (days) from a date (null: the sheet's today); null leaves the grade on the sheet's terms. */
  setGradeTerms(id: string, grade: Grade, terms: number | null, from: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/terms/${grade}`, { terms, from })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Change the active sheet's policy date; null means the sheet's today. */
  setPolicyDate(id: string, policyDate: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/policy-date`, { policyDate })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Change the active sheet's period numbering from; null means off. */
  setPeriodNumberingFrom(id: string, periodNumberingFrom: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/period-numbering-from`, { periodNumberingFrom })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Record the active sheet's late percent from a date (null: its today). */
  setLatePct(id: string, latePct: string, from: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/late-pct`, { latePct, from })
      .pipe(tap((s) => this.active.set(s)));
  }

  /** Record the active sheet's cap from a date (null: its today); null means none. */
  setCap(id: string, cap: string | null, from: string | null): Observable<SheetView> {
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings/cap`, { cap, from })
      .pipe(tap((s) => this.active.set(s)));
  }
}
