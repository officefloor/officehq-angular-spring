import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** The columns of the people list a user may switch off, in the order the chooser lists them. */
export const PEOPLE_COLUMNS = [
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
export type PeopleColumn = (typeof PEOPLE_COLUMNS)[number];

// The people-list columns the signed-in user has switched off, saved per user in the store (the same
// on every sheet). It changes only which cells their screen shows: every figure is worked out as before.
@Injectable({ providedIn: 'root' })
export class Columns {
  private readonly http = inject(HttpClient);
  /** The saves not yet answered, in the order they were made: a load waits for them. */
  private saving: Promise<unknown> = Promise.resolve();

  readonly hidden = signal<ReadonlySet<string>>(new Set());
  /** Why the last read or save of the columns failed; null when it went through. */
  readonly error = signal<string | null>(null);

  /** Whether the column is shown on this user's screen. */
  shown(column: PeopleColumn): boolean {
    return !this.hidden().has(column);
  }

  /** Re-read the signed-in user's switched-off columns from the store. */
  async load(): Promise<void> {
    await this.saving.catch(() => undefined);
    try {
      const view = await firstValueFrom(this.http.get<{ hidden: string[] }>('/api/user/columns'));
      this.hidden.set(new Set(view.hidden));
      this.error.set(null);
    } catch {
      this.error.set('Your columns could not be read.');
    }
  }

  /** Switch the column on or off on this user's screen, and save the choice in the store. */
  async set(column: PeopleColumn, shown: boolean): Promise<void> {
    const next = new Set(this.hidden());
    if (shown) next.delete(column);
    else next.add(column);
    this.hidden.set(next);
    this.error.set(null);
    const hidden = [...next];
    const save = this.saving
      .catch(() => undefined)
      .then(() => firstValueFrom(this.http.put<{ hidden: string[] }>('/api/user/columns', { hidden })));
    this.saving = save;
    try {
      await save;
    } catch {
      this.error.set('Your columns could not be saved.');
    }
  }
}
