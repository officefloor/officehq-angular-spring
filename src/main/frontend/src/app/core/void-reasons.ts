import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/** A void reason on the sheet: while the list is not empty, a claim is voided only with one of them. */
export interface VoidReasonView {
  id: number;
  /** Trimmed; on the sheet's list at most once. */
  reason: string;
}

// The sheet's void reasons, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class VoidReasonsApi {
  private readonly http = inject(HttpClient);

  /** The sheet's void reasons in the order they were added. */
  list(sheet: string): Promise<VoidReasonView[]> {
    return firstValueFrom(this.http.get<VoidReasonView[]>(this.url(sheet)));
  }

  /** Add a void reason; refused when blank or already on the list. */
  add(sheet: string, reason: string): Promise<VoidReasonView> {
    return firstValueFrom(this.http.post<VoidReasonView>(this.url(sheet), { reason }));
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/void-reasons`;
  }
}

// The active sheet's void reasons, shared by the list, each claim's void and the summary; re-read
// from the store on every sheet load.
@Injectable({ providedIn: 'root' })
export class VoidReasons {
  private readonly session = inject(Session);
  private readonly api = inject(VoidReasonsApi);

  readonly list = signal<VoidReasonView[]>([]);

  constructor() {
    effect(() => {
      const sheet = this.session.sheet();
      const loads = this.session.loads();
      untracked(() => {
        this.list.set([]);
        if (sheet) void this.reload(sheet, loads);
      });
    });
  }

  /** Add a void reason to the active sheet's list. */
  async add(sheet: string, reason: string): Promise<void> {
    const saved = await this.api.add(sheet, reason);
    if (this.session.sheet() === sheet) this.list.update((all) => [...all, saved]);
  }

  private async reload(sheet: string, loads: number): Promise<void> {
    const reasons = await this.api.list(sheet);
    // A later load may have started while this one was in flight: only the latest is shown.
    if (this.session.sheet() === sheet && this.session.loads() === loads) this.list.set(reasons);
  }
}
