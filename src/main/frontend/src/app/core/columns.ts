import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

// The people-list columns a user may switch off, with the heading each shows.
export const COLUMNS = [
  { id: 'claimed', label: 'Claimed' },
  { id: 'due-total', label: 'Due' },
  { id: 'statement-count', label: 'Statements' },
  { id: 'stated-total', label: 'Last stated total' },
  { id: 'statement-date', label: 'Last statement' },
  { id: 'overdue-count', label: 'Overdue' },
  { id: 'overdue-total', label: 'Overdue total' },
  { id: 'patrons', label: 'Patrons' },
  { id: 'backing', label: 'Backing' },
  { id: 'excess', label: 'Excess' },
  { id: 'hold', label: 'On hold' },
  { id: 'retired', label: 'Retired' },
  { id: 'terms', label: 'Terms' },
  { id: 'contact', label: 'Contact' },
  { id: 'aliases', label: 'Aliases' },
] as const;

// The people-list columns the signed-in user's screen leaves out, saved per user. Only the screen changes: every
// figure, total and audit line is the same whichever columns are shown.
@Injectable({ providedIn: 'root' })
export class UserColumns {
  private readonly http = inject(HttpClient);
  // Saves are sent one after another, so the last choice is the one the store keeps.
  private saving: Promise<unknown> = Promise.resolve();

  readonly hidden = signal<ReadonlySet<string>>(new Set());

  async load(): Promise<void> {
    const v = await firstValueFrom(this.http.get<{ hidden: string[] }>('/api/me/columns'));
    this.hidden.set(new Set(v.hidden));
  }

  shown(id: string): boolean {
    return !this.hidden().has(id);
  }

  set(id: string, shown: boolean): Promise<unknown> {
    this.hidden.update((h) => {
      const next = new Set(h);
      if (shown) next.delete(id);
      else next.add(id);
      return next;
    });
    const hidden = [...this.hidden()];
    this.saving = this.saving
      .catch(() => undefined)
      .then(() => firstValueFrom(this.http.put('/api/me/columns', { hidden })));
    return this.saving;
  }
}
