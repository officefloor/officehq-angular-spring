import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, map, switchMap, tap } from 'rxjs';

export interface PeriodView {
  id: number;
  /** Unique on its sheet. */
  name: string;
  /** The first day of the period (yyyy-mm-dd), inclusive. */
  from: string;
  /** The last day of the period (yyyy-mm-dd), inclusive. */
  to: string;
  /** Whether the period is closed: no claim dated inside it can be added, edited or moved. */
  closed: boolean;
  /** Whether the period is soft-closed: its figures are fixed as for a close, but claims dated inside it can still
   *  be added and changed. Never true together with closed. */
  softClosed: boolean;
  /** The due-total fixed when the period was closed or soft-closed, with exactly two decimals; null while open. */
  closedTotal: string | null;
  /** Each person's due-total for the period, by person id, fixed when it was closed or soft-closed; empty while open. A person
   *  missing (added later) has 0.00. */
  closedPersonTotals: Record<string, string>;
  /** The most OPEN or PENDING claims a person may have in the period; null for no cap. */
  cap: number | null;
  /** Each person's carried figure for the period, by person id, fixed when it was closed or soft-closed; empty
   *  while open (absent from what was kept before figures were fixed). A person missing has 0.00. */
  closedPersonCarried?: Record<string, string> | null;
  /** Each person's opening balance for the period, by person id, added to their carried figure for it (absent from
   *  what was kept before openings were pasted). A person missing has 0.00. */
  personOpenings?: Record<string, string> | null;
  /** Whether a steward has locked the period: while locked it cannot be reopened (absent from what was kept before
   *  periods could be locked). */
  locked?: boolean;
  /** How many claims belong to the period (their due-by falls inside it), VOID and DELETED ones left out (absent
   *  from what was kept before periods were counted). */
  claimsCount?: number;
}

/** A period as the form submits it: a blank from-date takes the sheet's today. */
export interface PeriodForm {
  name: string;
  from: string | null;
  to: string;
  /** The most OPEN or PENDING claims a person may have in the period, a whole number from 1; null for no cap. */
  cap: number | null;
}

/** What a generate made: how many periods, and the periods themselves. */
export interface GenerateView {
  made: number;
  periods: PeriodView[];
}

/** What an opening balance paste did: how many lines went through, and the periods they changed. */
export interface OpeningImportView {
  added: number;
  periods: PeriodView[];
}

/** The period whose dates contain a date (yyyy-mm-dd); undefined when none does. */
export function periodOn(periods: readonly PeriodView[], date: string): PeriodView | undefined {
  return periods.find((p) => p.from <= date && date <= p.to);
}

// The periods of the active sheet, by from-date; no two of them overlap.
@Injectable({ providedIn: 'root' })
export class Periods {
  private readonly http = inject(HttpClient);
  private readonly periods = signal<PeriodView[]>([]);
  readonly list = this.periods.asReadonly();

  /** Re-read a sheet's periods from the store; null clears them. */
  load(sheetId: string | null): Observable<PeriodView[]> | null {
    this.periods.set([]);
    if (sheetId === null) return null;
    return this.http.get<PeriodView[]>(this.base(sheetId)).pipe(tap((periods) => this.periods.set(periods)));
  }

  /** Add a period; refused when its name is in use, it ends before it starts, or it overlaps another. */
  add(sheetId: string, period: PeriodForm): Observable<PeriodView> {
    return this.http
      .post<PeriodView>(this.base(sheetId), period)
      .pipe(tap((saved) => this.periods.update((list) => [...list, saved].sort((a, b) => (a.from < b.from ? -1 : 1)))));
  }

  /** Generate one period per calendar month from the from-date's month to the to-date's, named yyyy-mm; a month
   *  whose name is in use or that overlaps a period is left out. Refused when the to-date is before the from-date. */
  generate(sheetId: string, from: string | null, to: string): Observable<GenerateView> {
    return this.http
      .post<GenerateView>(`${this.base(sheetId)}/generate`, { from, to })
      .pipe(tap((made) => this.addMade(made)));
  }

  /** Generate the twelve monthly periods of the fiscal year starting in the given calendar year, from the first of
   *  the sheet's fiscal start month, as {@link generate} does. */
  generateFiscalYear(sheetId: string, year: number): Observable<GenerateView> {
    return this.http
      .post<GenerateView>(`${this.base(sheetId)}/generate-fy`, { year })
      .pipe(tap((made) => this.addMade(made)));
  }

  /** Generate the four calendar quarters of a year, named yyyy-Q1 to yyyy-Q4; a quarter whose name is in use or
   *  that overlaps a period is left out. */
  generateQuarters(sheetId: string, year: number): Observable<GenerateView> {
    return this.http
      .post<GenerateView>(`${this.base(sheetId)}/generate-quarters`, { year })
      .pipe(tap((made) => this.addMade(made)));
  }

  /** Roll the fiscal year starting in the given calendar year over: the next fiscal year's periods are generated as
   *  {@link generateFiscalYear} does and each person's open figure is carried into its first period; only a steward
   *  may, and it is refused when the year has no periods. The periods are re-read once it is done. */
  rollover(sheetId: string, year: number): Observable<GenerateView> {
    return this.http.post<GenerateView>(`${this.base(sheetId)}/rollover`, { year }).pipe(
      switchMap((made) =>
        this.http.get<PeriodView[]>(this.base(sheetId)).pipe(
          tap((periods) => this.periods.set(periods)),
          map(() => made),
        ),
      ),
    );
  }

  /** Undo the sheet's last rollover: the periods it generated are removed and the openings it set taken back; only
   *  the steward who made it may, on the sheet's today it was made, while no claim is in a period it generated. The
   *  periods are re-read once it is done. */
  undoRollover(sheetId: string): Observable<void> {
    return this.http.post<void>(`${this.base(sheetId)}/rollover/undo`, {}).pipe(
      switchMap(() =>
        this.http.get<PeriodView[]>(this.base(sheetId)).pipe(
          tap((periods) => this.periods.set(periods)),
          map(() => undefined),
        ),
      ),
    );
  }

  private addMade(made: GenerateView): void {
    this.periods.update((list) => [...list, ...made.periods].sort((a, b) => (a.from < b.from ? -1 : 1)));
  }

  /** Close a period, open or soft-closed, fixing its due-total afresh; refused when it is already closed. */
  close(sheetId: string, name: string): Observable<PeriodView> {
    return this.http
      .post<PeriodView>(`${this.base(sheetId)}/${encodeURIComponent(name)}/close`, {})
      .pipe(tap((saved) => this.periods.update((list) => list.map((p) => (p.id === saved.id ? saved : p)))));
  }

  /** Soft-close an open period, fixing its figures while its claims can still be changed; refused when it is
   *  already closed or soft-closed. */
  softClose(sheetId: string, name: string): Observable<PeriodView> {
    return this.http
      .post<PeriodView>(`${this.base(sheetId)}/${encodeURIComponent(name)}/soft-close`, {})
      .pipe(tap((saved) => this.periods.update((list) => list.map((p) => (p.id === saved.id ? saved : p)))));
  }

  /** Reopen a closed or soft-closed period, making its due-total live again; only a steward may. */
  reopen(sheetId: string, name: string): Observable<PeriodView> {
    return this.http
      .post<PeriodView>(`${this.base(sheetId)}/${encodeURIComponent(name)}/reopen`, {})
      .pipe(tap((saved) => this.periods.update((list) => list.map((p) => (p.id === saved.id ? saved : p)))));
  }

  /** Lock or unlock a period; only a steward may, and it is refused when the period is already so. */
  setLocked(sheetId: string, name: string, locked: boolean): Observable<PeriodView> {
    return this.http
      .post<PeriodView>(`${this.base(sheetId)}/${encodeURIComponent(name)}/${locked ? 'lock' : 'unlock'}`, {})
      .pipe(tap((saved) => this.periods.update((list) => list.map((p) => (p.id === saved.id ? saved : p)))));
  }

  /** Add an opening balance per pasted line of name,period,amount to the person's carried figure for the period;
   *  when any line is refused, none is added. */
  importOpenings(sheetId: string, text: string): Observable<OpeningImportView> {
    return this.http
      .post<OpeningImportView>(`/api/sheets/${encodeURIComponent(sheetId)}/openings/import`, { text })
      .pipe(
        tap(({ periods }) =>
          this.periods.update((list) => list.map((p) => periods.find((saved) => saved.id === p.id) ?? p)),
        ),
      );
  }

  private base(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/periods`;
  }
}
