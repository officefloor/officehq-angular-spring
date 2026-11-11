import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/** The people-list columns a user may switch off, with their headings, in the order the list shows them. */
export const COLUMNS = [
  { key: 'terms', label: 'Terms' },
  { key: 'contact', label: 'Contact' },
  { key: 'aliases', label: 'Aliases' },
  { key: 'claimed', label: 'Claimed' },
  { key: 'due-total', label: 'Due' },
  { key: 'statement-count', label: 'Statements' },
  { key: 'stated-total', label: 'Last stated' },
  { key: 'statement-date', label: 'Stated on' },
  { key: 'retired', label: 'Retired' },
  { key: 'hold', label: 'On hold' },
  { key: 'overdue-count', label: 'Overdue' },
  { key: 'overdue-total', label: 'Overdue total' },
  { key: 'patrons', label: 'Patrons' },
  { key: 'backing', label: 'Backing' },
  { key: 'excess', label: 'Excess' },
] as const;
export type ColumnKey = (typeof COLUMNS)[number]['key'];

interface ColumnsView {
  hidden: ColumnKey[];
}

// The people-list columns the signed-in user has switched off, saved per user: a switched-off column has no cell on
// their screen. Nothing else on the screen depends on it.
@Injectable({ providedIn: 'root' })
export class UserColumns {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);
  /** Raised on each choice, so a read that a later choice overtakes is dropped. */
  private choices = 0;

  /** The switched-off columns: none until they are read. */
  readonly hidden = signal<ReadonlySet<ColumnKey>>(new Set());

  /** Whether the column shows on the signed-in user's screen. */
  shows(key: ColumnKey): boolean {
    return !this.hidden().has(key);
  }

  /** Re-read the signed-in user's switched-off columns. */
  async read(): Promise<void> {
    const user = this.session.user();
    const choices = this.choices;
    const view = await firstValueFrom(this.http.get<ColumnsView>('/api/me/columns', this.session.headers()));
    if (this.session.user() === user && this.choices === choices) this.hidden.set(new Set(view.hidden));
  }

  /** Switch the column on or off on the signed-in user's screen, at once, and save the choice. */
  async show(key: ColumnKey, on: boolean): Promise<void> {
    const user = this.session.user();
    const choice = ++this.choices;
    const next = new Set(this.hidden());
    if (on) next.delete(key);
    else next.add(key);
    this.hidden.set(next);
    const view = await firstValueFrom(
      this.http.put<ColumnsView>('/api/me/columns', { hidden: [...next] }, this.session.headers()),
    );
    if (this.session.user() === user && this.choices === choice) this.hidden.set(new Set(view.hidden));
  }
}
