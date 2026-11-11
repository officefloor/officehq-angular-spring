import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/** A void reason on the sheet. */
export interface VoidReasonView {
  id: number;
  reason: string;
}

// The active sheet's void reasons, in the order they were added, re-read whenever a sheet is loaded. While there are
// any, voiding a claim needs one of them.
@Injectable({ providedIn: 'root' })
export class VoidReasons {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly list = signal<VoidReasonView[]>([]);
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

  /** Add a reason (already trimmed) to the active sheet's list; throws when the store refuses it. */
  async add(reason: string): Promise<void> {
    const sheet = this.session.sheet();
    if (!sheet) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.post<VoidReasonView>(
        `/api/sheets/${encodeURIComponent(sheet)}/void-reasons`,
        { reason },
        this.session.headers(),
      ),
    );
    if (this.session.sheet() === sheet) this.list.update((list) => [...list, saved]);
  }

  private async read(sheet: string): Promise<void> {
    const read = ++this.reads;
    const list = await firstValueFrom(
      this.http.get<VoidReasonView[]>(`/api/sheets/${encodeURIComponent(sheet)}/void-reasons`, this.session.headers()),
    ).catch(() => null);
    // A later read wins over a slower earlier one.
    if (list && read === this.reads && this.session.sheet() === sheet) {
      this.list.set(list);
    }
  }
}
