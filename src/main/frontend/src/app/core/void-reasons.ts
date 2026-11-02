import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/** A reason on the sheet's list that a claim may be voided for. */
export interface VoidReason {
  id: number;
  reason: string;
}

// The void reasons of the active sheet, re-read whenever the sheet is loaded. While the list is not
// empty, a claim is voided with one of them.
@Injectable({ providedIn: 'root' })
export class VoidReasons {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The sheet's void reasons in the order they were added. */
  readonly list = signal<VoidReason[]>([]);
  private reads = 0;

  constructor() {
    effect(() => {
      this.session.loads();
      const sheetId = this.session.sheetId();
      this.list.set([]);
      if (sheetId) void this.reload(sheetId);
    });
  }

  /** Add a reason to the sheet's list; refused (400) when blank and (409) when already on it. */
  async add(reason: string): Promise<VoidReason> {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    const saved = await firstValueFrom(
      this.http.post<VoidReason>(this.url(sheetId), { reason }, { headers: { 'X-User': user } }),
    );
    this.list.update((rs) => [...rs.filter((r) => r.id !== saved.id), saved]);
    return saved;
  }

  private async reload(sheetId: string): Promise<void> {
    const read = ++this.reads;
    try {
      const reasons = await firstValueFrom(this.http.get<VoidReason[]>(this.url(sheetId)));
      if (read === this.reads) this.list.set(reasons);
    } catch {
      // the list stays empty; the next load reads it again
    }
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/void-reasons`;
  }
}
