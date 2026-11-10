import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

// The sheet's void reasons, in the order they were added, re-read from the store every time a
// sheet is loaded. While the list is not empty a claim is voided only with one of them; the
// summary counts the VOID claims voided with each.
export interface VoidReason {
  id: number;
  reason: string;
}

@Injectable({ providedIn: 'root' })
export class VoidReasons {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly list = signal<VoidReason[]>([]);
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
      const list = await firstValueFrom(this.http.post<VoidReason[]>('/api/void-reasons/list', { sheet }));
      if (this.session.sheet() === sheet) this.list.set(list);
    } catch {
      this.loadError.set('Could not read the void reasons on this sheet.');
    }
  }

  /** Add a reason (trimmed) to the list; the store refuses a blank or repeated one. */
  async add(reason: string): Promise<void> {
    const sheet = this.session.sheet();
    const added = await firstValueFrom(
      this.http.post<VoidReason>('/api/void-reasons/add', { sheet, user: this.session.user(), reason }),
    );
    if (this.session.sheet() === sheet) this.list.update((list) => [...list, added]);
  }
}
