import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A reason a claim on the sheet can be voided with. */
export interface VoidReason {
  id: number;
  reason: string;
}

// The void reasons on a sheet, read from and written to the server, and kept for the sheet loaded:
// re-read from the store every time a sheet is loaded. While the list is not empty, a void of a claim
// names one of them.
@Injectable({ providedIn: 'root' })
export class VoidReasonsService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The loaded sheet's void reasons, in the order they were added. */
  readonly reasons = signal<VoidReason[]>([]);

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      this.session.loads();
      this.reasons.set([]);
      if (sheet) void this.read(sheet);
    });
  }

  /** Add a void reason. Refused (409) when it is already on the list and (400) when it is blank. */
  async add(sheet: string, reason: string): Promise<void> {
    const all = await firstValueFrom(
      this.http.post<VoidReason[]>(this.url(sheet), { reason }, { headers: this.session.headers() }),
    );
    if (this.session.sheetId() === sheet) this.reasons.set(all);
  }

  private async read(sheet: string): Promise<void> {
    try {
      const all = await firstValueFrom(this.http.get<VoidReason[]>(this.url(sheet), { headers: this.session.headers() }));
      if (this.session.sheetId() === sheet) this.reasons.set(all);
    } catch {
      // The void reasons could not be read; they show as none.
    }
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/void-reasons`;
  }
}
