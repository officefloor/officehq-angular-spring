import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/** A claim template on the sheet: a claim added from it takes its amount and note at that moment. */
export interface ClaimTemplateView {
  id: number;
  name: string;
  /** Two decimals, e.g. "2.50". */
  amount: string;
  note: string;
}

// The active sheet's claim templates, by name, re-read whenever a sheet is loaded.
@Injectable({ providedIn: 'root' })
export class Templates {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly list = signal<ClaimTemplateView[]>([]);
  private reads = 0;

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      this.session.loaded();
      untracked(() => {
        this.list.set([]);
        if (sheet) void this.read(sheet);
      });
    });
  }

  /** Save a template on the active sheet, replacing the one of the same name; throws when the store refuses it. */
  async save(template: { name: string; amount: string; note: string }): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<ClaimTemplateView>(
        `/api/sheets/${encodeURIComponent(sheet)}/templates`,
        template,
        this.session.headers(),
      ),
    );
    if (this.session.sheet() === sheet) {
      this.list.update((list) =>
        [...list.filter((t) => t.name !== saved.name), saved].sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0)),
      );
    }
  }

  private async read(sheet: string): Promise<void> {
    const read = ++this.reads;
    const list = await firstValueFrom(
      this.http.get<ClaimTemplateView[]>(`/api/sheets/${encodeURIComponent(sheet)}/templates`, this.session.headers()),
    ).catch(() => null);
    // A later read wins over a slower earlier one.
    if (list && read === this.reads && this.session.sheet() === sheet) {
      this.list.set(list);
    }
  }
}
