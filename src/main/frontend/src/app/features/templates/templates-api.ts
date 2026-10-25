import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { ClaimView } from '../claims/claims-api';

export interface TemplateView {
  id: number;
  name: string;
  amount: string;
  note: string;
}

// The claim templates of a sheet, as the store holds them.
@Injectable({ providedIn: 'root' })
export class TemplatesApi {
  private readonly http = inject(HttpClient);

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}`;
  }

  /** The sheet's claim templates by name. */
  list(sheetId: string): Promise<TemplateView[]> {
    return firstValueFrom(this.http.get<TemplateView[]>(`${this.url(sheetId)}/templates`));
  }

  /** Save a template, replacing the one of the same name: refused for a blank name or an amount the
   *  claim form would refuse. */
  save(sheetId: string, form: { name: string; amount: string; note: string }): Promise<TemplateView> {
    return firstValueFrom(this.http.put<TemplateView>(`${this.url(sheetId)}/templates`, form));
  }

  /** Add a draft claim to a person with the template's amount and note as the store holds them now. */
  addClaim(sheetId: string, personId: number, template: string): Promise<ClaimView> {
    return firstValueFrom(this.http.post<ClaimView>(
      `${this.url(sheetId)}/people/${personId}/claims/from-template`, { template }));
  }
}

// The active sheet's claim templates, shared by their form and each person's claims.
// Each load of a sheet re-reads them from the store.
@Injectable({ providedIn: 'root' })
export class Templates {
  private readonly session = inject(Session);
  private readonly api = inject(TemplatesApi);

  private readonly state = signal<TemplateView[]>([]);
  readonly templates = this.state.asReadonly();
  /** The refusal of the last loading of the templates, or blank. */
  readonly loadError = signal('');

  constructor() {
    effect(() => {
      const sheet = this.session.loaded();
      this.state.set([]);
      this.loadError.set('');
      if (sheet) void this.load(sheet.id);
    });
  }

  private async load(sheetId: string): Promise<void> {
    try {
      const list = await this.api.list(sheetId);
      if (this.session.sheet()?.id === sheetId) this.state.set(list);
    } catch {
      if (this.session.sheet()?.id === sheetId) this.loadError.set('The claim templates could not be loaded.');
    }
  }

  /** Save a template of the active sheet; the store's answer replaces the one of its name. */
  async save(sheetId: string, form: { name: string; amount: string; note: string }): Promise<void> {
    const saved = await this.api.save(sheetId, form);
    if (this.session.sheet()?.id !== sheetId) return;
    this.state.update((list) => [...list.filter((t) => t.name !== saved.name), saved]
      .sort((a, b) => (a.name < b.name ? -1 : a.name > b.name ? 1 : 0)));
  }
}
