import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ClaimView } from './claims';
import { Session } from './session';

/** A claim template on the sheet: a claim made from it takes its amount and note as they are then. */
export interface TemplateView {
  id: number;
  /** Trimmed; once per sheet. */
  name: string;
  /** The amount with two decimals, e.g. "12.50". */
  amount: string;
  note: string;
}

// The sheet's claim templates, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class TemplatesApi {
  private readonly http = inject(HttpClient);

  /** The sheet's templates by name. */
  list(sheet: string): Promise<TemplateView[]> {
    return firstValueFrom(this.http.get<TemplateView[]>(`${this.url(sheet)}/templates`));
  }

  /** Save a template; a name saved again replaces it. */
  save(sheet: string, form: { name: string; amount: string; note: string }): Promise<TemplateView> {
    return firstValueFrom(this.http.post<TemplateView>(`${this.url(sheet)}/templates`, form));
  }

  /** Add a DRAFT claim for a person from the template of that name, as it is now. */
  claimFrom(sheet: string, personId: number, name: string): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(`${this.url(sheet)}/people/${personId}/claims/from-template`, { name }));
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}`;
  }
}

// The active sheet's claim templates, shared by the template list and each person's claims; re-read
// from the store on every sheet load.
@Injectable({ providedIn: 'root' })
export class Templates {
  private readonly session = inject(Session);
  private readonly api = inject(TemplatesApi);

  readonly list = signal<TemplateView[]>([]);

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

  /** Save a template on the active sheet, replacing the one of that name. */
  async save(sheet: string, form: { name: string; amount: string; note: string }): Promise<void> {
    const saved = await this.api.save(sheet, form);
    if (this.session.sheet() === sheet) {
      this.list.update((all) =>
        [...all.filter((t) => t.name !== saved.name), saved].sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0)),
      );
    }
  }

  private async reload(sheet: string, loads: number): Promise<void> {
    const templates = await this.api.list(sheet);
    // A later load may have started while this one was in flight: only the latest is shown.
    if (this.session.sheet() === sheet && this.session.loads() === loads) this.list.set(templates);
  }
}
