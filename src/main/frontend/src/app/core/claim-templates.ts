import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Claim } from './claims';
import { Session } from './session';

/** A claim template: the amount and note a claim made from it is given at that moment. */
export interface ClaimTemplate {
  id: number;
  name: string;
  amount: string;
  note: string;
}

// The claim templates of the active sheet, re-read whenever the sheet is loaded.
@Injectable({ providedIn: 'root' })
export class ClaimTemplates {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The sheet's templates in the order they were first saved. */
  readonly list = signal<ClaimTemplate[]>([]);
  private reads = 0;

  constructor() {
    effect(() => {
      this.session.loads();
      const sheetId = this.session.sheetId();
      this.list.set([]);
      if (sheetId) void this.reload(sheetId);
    });
  }

  /** Save a template under its name, replacing the one of that name; refused (400) when the name is
   *  blank or the amount is not one the claim form takes. */
  async save(form: { name: string; amount: string; note: string }): Promise<ClaimTemplate> {
    const { sheetId, user } = this.active();
    const saved = await firstValueFrom(
      this.http.put<ClaimTemplate>(this.url(sheetId), form, { headers: { 'X-User': user } }),
    );
    this.list.update((ts) => {
      const at = ts.findIndex((t) => t.id === saved.id);
      return at < 0 ? [...ts, saved] : ts.map((t, i) => (i === at ? saved : t));
    });
    return saved;
  }

  /** Add a draft claim for the person from the template of the name as it is now. */
  addClaim(personId: number, name: string): Promise<Claim> {
    const { sheetId, user } = this.active();
    return firstValueFrom(
      this.http.post<Claim>(`/api/sheets/${encodeURIComponent(sheetId)}/people/${personId}/claims/from-template`,
        { name }, { headers: { 'X-User': user } }),
    );
  }

  private active(): { sheetId: string; user: string } {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    return { sheetId, user };
  }

  private async reload(sheetId: string): Promise<void> {
    const read = ++this.reads;
    try {
      const templates = await firstValueFrom(this.http.get<ClaimTemplate[]>(this.url(sheetId)));
      if (read === this.reads) this.list.set(templates);
    } catch {
      // the list stays empty; the next load reads it again
    }
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/claim-templates`;
  }
}
