import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

// The sheet's claim templates, by name, re-read from the store every time a sheet is loaded. Saving
// a name again replaces that template. A claim made from a template copies its amount and note as
// they are then.
export interface ClaimTemplate {
  id: number;
  name: string;
  amount: string;
  note: string;
}

@Injectable({ providedIn: 'root' })
export class ClaimTemplates {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly list = signal<ClaimTemplate[]>([]);
  /** Set when the list could not be read from the store. */
  readonly loadError = signal<string | null>(null);

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loads();
      this.list.set([]);
      this.loadError.set(null);
      if (sheet) {
        void this.fetch(sheet);
      }
    });
  }

  private async fetch(sheet: string): Promise<void> {
    try {
      const list = await firstValueFrom(this.http.post<ClaimTemplate[]>('/api/templates/list', { sheet }));
      if (this.session.sheet() === sheet) this.list.set(list);
    } catch {
      this.loadError.set('Could not read the claim templates on this sheet.');
    }
  }

  /** Save a template; the store refuses a blank name or an amount the claim form would refuse. */
  async save(name: string, amount: string, note: string): Promise<void> {
    const sheet = this.session.sheet();
    const saved = await firstValueFrom(
      this.http.post<ClaimTemplate>('/api/templates/save', { sheet, user: this.session.user(), name, amount, note }),
    );
    if (this.session.sheet() === sheet) {
      this.list.update((list) =>
        [...list.filter((t) => t.name !== saved.name), saved].sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0)),
      );
    }
  }
}
