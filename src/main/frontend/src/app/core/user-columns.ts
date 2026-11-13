import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/** The columns of the people list a user may switch off; every one is shown until they do. */
export const COLUMNS = [
  'claimed',
  'due-total',
  'excess',
  'statement-count',
  'stated-total',
  'statement-date',
  'overdue-count',
  'overdue-total',
  'patrons',
  'backing',
  'hold',
  'retired',
  'terms',
  'contact',
  'aliases',
] as const;
export type ColumnKey = (typeof COLUMNS)[number];

// Which columns of the people list the signed-in user's screen shows, kept per user on the server and
// re-read each time a sheet is loaded. It only changes which cells the list draws; every figure stays.
@Injectable({ providedIn: 'root' })
export class UserColumns {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The columns switched off. */
  readonly hidden = signal<ReadonlySet<string>>(new Set());
  private loads = 0;

  shown(column: ColumnKey): boolean {
    return !this.hidden().has(column);
  }

  /** Re-read the signed-in user's columns from the server; an error message when that failed. */
  async load(): Promise<string | null> {
    const user = this.session.user();
    const load = ++this.loads;
    if (!user) return null;
    try {
      const view = await firstValueFrom(
        this.http.get<{ hidden: string[] }>('/api/user/columns', { headers: { 'X-User': user } }),
      );
      if (load === this.loads) this.hidden.set(new Set(view.hidden));
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not load your columns (${e.status}).` : 'Could not load your columns.';
    }
  }

  /** Switch one column on or off for the signed-in user; an error message when refused. */
  async show(column: ColumnKey, shown: boolean): Promise<string | null> {
    const user = this.session.user();
    if (!user) return 'Sign in first.';
    // Shown at once; a load already under way must not put back what it read before this.
    ++this.loads;
    this.hidden.update((h) => {
      const next = new Set(h);
      if (shown) next.delete(column);
      else next.add(column);
      return next;
    });
    try {
      await firstValueFrom(
        this.http.put<{ hidden: string[] }>(`/api/user/columns/${column}`, { shown }, { headers: { 'X-User': user } }),
      );
      return null;
    } catch (e) {
      void this.load();
      if (e instanceof HttpErrorResponse && typeof e.error?.message === 'string') return e.error.message;
      return e instanceof HttpErrorResponse ? `Could not save your columns (${e.status}).` : 'Could not save your columns.';
    }
  }
}
