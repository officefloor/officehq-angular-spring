import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { readAsOf } from '../../core/as-of.interceptor';
import { Session } from '../../core/session';

export interface CrewView {
  id: number;
  name: string;
  /** The lead, a person of the sheet; none once they have been transferred to another sheet. */
  leadId: number | null;
  /** The members' person ids in the order they were added. */
  memberIds: number[];
}

/** The crew's people: its lead, when it has one, and then its members. */
export function crewIds(crew: CrewView): number[] {
  return crew.leadId === null ? [...crew.memberIds] : [crew.leadId, ...crew.memberIds];
}

// The crews of a sheet, as the store holds them.
@Injectable({ providedIn: 'root' })
export class CrewsApi {
  private readonly http = inject(HttpClient);

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/crews`;
  }

  /** The sheet's crews in the order they were added; as they stood at the end of the as-of date when one
   *  is given. */
  list(sheetId: string, asOf?: string): Promise<CrewView[]> {
    return firstValueFrom(this.http.get<CrewView[]>(this.url(sheetId), readAsOf(asOf)));
  }

  /** Add a crew with a lead: refused for a blank name or one the sheet already has. */
  add(sheetId: string, name: string, leadId: number): Promise<CrewView> {
    return firstValueFrom(this.http.post<CrewView>(this.url(sheetId), { name, leadId }));
  }

  /** Add a person to a crew's members: refused for its lead or a member already. The crew as it is then. */
  addMember(sheetId: string, crewId: number, personId: number): Promise<CrewView> {
    return firstValueFrom(this.http.post<CrewView>(`${this.url(sheetId)}/${crewId}/members`, { personId }));
  }
}

// The active sheet's crews. Each load of a sheet re-reads them from the store.
@Injectable({ providedIn: 'root' })
export class Crews {
  private readonly session = inject(Session);
  private readonly api = inject(CrewsApi);

  private readonly state = signal<CrewView[]>([]);
  readonly crews = this.state.asReadonly();
  /** The refusal of the last loading of the crews, or blank. */
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
      const list = await this.session.reading(this.api.list(sheetId));
      if (this.session.sheet()?.id === sheetId) this.state.set(list);
    } catch {
      if (this.session.sheet()?.id === sheetId) this.loadError.set('The crews could not be loaded.');
    }
  }

  /** Re-read the sheet's crews from the store, as after a change that moves their people. */
  refresh(sheetId: string): Promise<void> {
    return this.load(sheetId);
  }

  /** Add a crew to the active sheet. */
  async add(sheetId: string, name: string, leadId: number): Promise<void> {
    const added = await this.api.add(sheetId, name, leadId);
    if (this.session.sheet()?.id !== sheetId) return;
    this.state.update((list) => [...list.filter((c) => c.id !== added.id), added]);
  }

  /** Add a member to a crew of the active sheet; the store's answer replaces the crew. */
  async addMember(sheetId: string, crewId: number, personId: number): Promise<void> {
    const crew = await this.api.addMember(sheetId, crewId, personId);
    if (this.session.sheet()?.id !== sheetId) return;
    this.state.update((list) => list.map((c) => (c.id === crew.id ? crew : c)));
  }
}
