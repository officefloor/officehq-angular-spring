import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/** A claim template: a claim made from it takes its amount and note as they are at that moment. */
export interface ClaimTemplateView {
  id: number;
  name: string;
  amount: string;
  note: string;
}

// The active sheet's claim templates, re-read from the server each time the sheet is loaded.
@Injectable({ providedIn: 'root' })
export class ClaimTemplates {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** By name. */
  readonly templates = signal<ClaimTemplateView[]>([]);
  private loads = 0;

  /** Re-read the active sheet's templates from the server; an error message when that failed. */
  async load(): Promise<string | null> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    const load = ++this.loads;
    this.templates.set([]);
    if (!user || !sid) return null;
    try {
      const templates = await firstValueFrom(
        this.http.get<ClaimTemplateView[]>(this.url(sid), { headers: { 'X-User': user } }),
      );
      if (load === this.loads) this.templates.set(templates);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not load claim templates (${e.status}).` : 'Could not load claim templates.';
    }
  }

  /** Save a template, replacing the one by its name; an error message when refused (a blank name, or an
   *  amount the claim form would refuse). */
  async save(name: string, amount: string, note: string): Promise<string | null> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    const n = name.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (!n) return 'Enter a template name.';
    if (!/^\d+(\.\d{1,2})?$/.test(amount.trim())) return 'Enter an amount of 0.00 or more with up to two decimals.';
    try {
      const saved = await firstValueFrom(
        this.http.post<ClaimTemplateView>(
          this.url(sid),
          { name: n, amount: amount.trim(), note: note.trim() },
          { headers: { 'X-User': user } },
        ),
      );
      if (this.session.sheetId() === sid) {
        this.templates.update((ts) =>
          [...ts.filter((t) => t.name !== saved.name), saved].sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0)),
        );
      }
      return null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && typeof e.error?.message === 'string') return e.error.message;
      if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter an amount of 0.00 or more with up to two decimals.';
      return e instanceof HttpErrorResponse ? `Could not save claim template (${e.status}).` : 'Could not save claim template.';
    }
  }

  private url(sid: string): string {
    return `/api/sheets/${encodeURIComponent(sid)}/claim-templates`;
  }
}
