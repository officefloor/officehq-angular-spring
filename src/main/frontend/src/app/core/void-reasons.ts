import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/** A reason on the sheet's list of void reasons. */
export interface VoidReasonView {
  id: number;
  reason: string;
}

// The active sheet's void reasons, re-read from the server each time the sheet is loaded. While the list
// has any, a claim is voided with one of them.
@Injectable({ providedIn: 'root' })
export class VoidReasons {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** In the order they were added. */
  readonly reasons = signal<VoidReasonView[]>([]);
  private loads = 0;

  /** Re-read the active sheet's void reasons from the server; an error message when that failed. */
  async load(): Promise<string | null> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    const load = ++this.loads;
    this.reasons.set([]);
    if (!user || !sid) return null;
    try {
      const reasons = await firstValueFrom(
        this.http.get<VoidReasonView[]>(this.url(sid), { headers: { 'X-User': user } }),
      );
      if (load === this.loads) this.reasons.set(reasons);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not load void reasons (${e.status}).` : 'Could not load void reasons.';
    }
  }

  /** Add a reason to the sheet's list; an error message when refused (blank, or already listed). */
  async add(reason: string): Promise<string | null> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    const r = reason.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (!r) return 'Enter a void reason.';
    try {
      const saved = await firstValueFrom(
        this.http.post<VoidReasonView>(this.url(sid), { reason: r }, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) {
        this.reasons.update((rs) => [...rs.filter((x) => x.id !== saved.id), saved]);
      }
      return null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && typeof e.error?.message === 'string') return e.error.message;
      return e instanceof HttpErrorResponse ? `Could not add void reason (${e.status}).` : 'Could not add void reason.';
    }
  }

  private url(sid: string): string {
    return `/api/sheets/${encodeURIComponent(sid)}/void-reasons`;
  }
}
