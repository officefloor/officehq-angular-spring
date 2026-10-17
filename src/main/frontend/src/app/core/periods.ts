import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

export interface Period {
  id: number;
  /** Unique on the sheet, without spaces. */
  name: string;
  /** yyyy-mm-dd, inclusive. */
  from: string;
  /** yyyy-mm-dd, inclusive. */
  to: string;
  closed: boolean;
  /** The due-total fixed when the period was closed, two decimals; null while it is open. */
  closedTotal: string | null;
}

/** A period name the store accepts: up to 100 characters, without spaces. */
export const PERIOD_NAME = /^\S{1,100}$/;

/** The period among these whose dates contain a date (yyyy-mm-dd); undefined when none does. */
export function periodOn(periods: Period[], date: string): Period | undefined {
  return periods.find((p) => p.from <= date && date <= p.to);
}

// The periods on the active sheet, in date order, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class Periods {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly all = signal<Period[]>([]);

  /** The period a date (yyyy-mm-dd) falls in; undefined when it falls in none. */
  on(date: string): Period | undefined {
    return periodOn(this.all(), date);
  }

  /** Whether a date (yyyy-mm-dd) falls in a closed period; no claim dated in one can be changed. */
  closedOn(date: string): boolean {
    return this.on(date)?.closed ?? false;
  }

  async reload(): Promise<void> {
    try {
      this.all.set(await firstValueFrom(this.http.get<Period[]>(this.url(), { headers: this.headers() })));
    } catch {
      this.all.set([]);
    }
  }

  /** Add a period; the store refuses a name already used, dates out of order or an overlap. */
  async add(name: string, from: string, to: string): Promise<void> {
    const added = await firstValueFrom(
      this.http.post<Period>(this.url(), { name, from, to }, { headers: this.headers() }),
    );
    this.all.update((list) => [...list, added].sort((a, b) => a.from.localeCompare(b.from)));
  }

  /** Close a period, fixing its due-total; the store refuses one already closed. */
  async close(name: string): Promise<void> {
    const saved = await firstValueFrom(
      this.http.post<Period>(`${this.url()}/${encodeURIComponent(name)}/close`, null, { headers: this.headers() }),
    );
    this.all.update((list) => list.map((p) => (p.id === saved.id ? saved : p)));
  }

  private url(): string {
    const id = this.session.sheetId();
    if (!id) throw new Error('no sheet loaded');
    return `/api/sheets/${encodeURIComponent(id)}/periods`;
  }

  private headers(): Record<string, string> {
    const user = this.session.user();
    if (!user) throw new Error('not signed in');
    return { 'X-User': user };
  }
}
