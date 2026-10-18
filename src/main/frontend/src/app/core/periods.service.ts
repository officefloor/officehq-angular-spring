import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, of, tap } from 'rxjs';
import { SessionService } from './session.service';
import { SheetService } from './sheet.service';

export interface Period {
  id: number;
  /** Unique on the sheet. */
  name: string;
  /** The first date (yyyy-MM-dd) in the period. */
  from: string;
  /** The last date (yyyy-MM-dd) in the period. */
  to: string;
  /** Closed: its due-total is fixed and no claim dated in it can change. */
  closed: boolean;
  /** The due-total fixed when it closed (two decimals); null while open. */
  closedTotal: string | null;
}

export interface PeriodForm {
  name: string;
  from: string;
  to: string;
}

// The active sheet's periods, by from-date. No two overlap, so a date (and the claim dated on it)
// belongs to at most one.
@Injectable({ providedIn: 'root' })
export class PeriodsService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionService);
  private readonly sheets = inject(SheetService);
  private readonly list = signal<Period[]>([]);

  readonly periods = this.list.asReadonly();

  /** The period whose dates contain the date (yyyy-MM-dd); undefined when none does. */
  periodOn(date: string): Period | undefined {
    return this.list().find((p) => p.from <= date && date <= p.to);
  }

  /** Re-read the active sheet's periods from the store. */
  reload(): Observable<unknown> {
    const id = this.sheets.sheetId();
    if (!id) {
      this.list.set([]);
      return of(null);
    }
    return this.http
      .get<Period[]>(this.url(id), { headers: this.session.headers() })
      .pipe(tap((periods) => {
        if (this.sheets.sheetId() === id) this.list.set(periods);
      }));
  }

  /** Add a period; refused when it ends before it starts, reuses a name or overlaps another. */
  add(form: PeriodForm): Observable<Period> {
    const id = this.sheets.sheetId();
    if (!id) throw new Error('no sheet loaded');
    return this.http
      .post<Period>(this.url(id), form, { headers: this.session.headers() })
      .pipe(tap((p) => this.list.update((all) => [...all, p].sort((a, b) => a.from.localeCompare(b.from)))));
  }

  /** Close a period, fixing its due-total; refused when it is already closed. */
  close(periodId: number): Observable<Period> {
    const id = this.sheets.sheetId();
    if (!id) throw new Error('no sheet loaded');
    return this.http
      .post<Period>(`${this.url(id)}/${periodId}/close`, null, { headers: this.session.headers() })
      .pipe(tap((p) => this.list.update((all) => all.map((q) => (q.id === p.id ? p : q)))));
  }

  /** Reopen a closed period, making its due-total live again; refused unless a steward reopens a closed one. */
  reopen(periodId: number): Observable<Period> {
    const id = this.sheets.sheetId();
    if (!id) throw new Error('no sheet loaded');
    return this.http
      .post<Period>(`${this.url(id)}/${periodId}/reopen`, null, { headers: this.session.headers() })
      .pipe(tap((p) => this.list.update((all) => all.map((q) => (q.id === p.id ? p : q)))));
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/periods`;
  }
}
