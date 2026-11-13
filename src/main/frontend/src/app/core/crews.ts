import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Pools } from './pools';
import { Session } from './session';

/** A person of a crew, by id, with their name. */
export interface CrewPersonView {
  personId: number;
  name: string;
}

/** A crew: its lead (null once its lead has left the sheet) and its members, in the order they were added. */
export interface CrewView {
  id: number;
  name: string;
  lead: CrewPersonView | null;
  members: CrewPersonView[];
}

// The active sheet's crews, re-read from the server each time the sheet is loaded.
@Injectable({ providedIn: 'root' })
export class Crews {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);
  private readonly pools = inject(Pools);

  /** In the order they were added. */
  readonly crews = signal<CrewView[]>([]);
  private loads = 0;

  /** Re-read the active sheet's crews from the server; an error message when that failed. */
  async load(): Promise<string | null> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    const load = ++this.loads;
    this.crews.set([]);
    if (!user || !sid) return null;
    try {
      const crews = await firstValueFrom(this.http.get<CrewView[]>(this.url(sid), { headers: { 'X-User': user } }));
      if (load === this.loads) this.crews.set(crews);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not load crews (${e.status}).` : 'Could not load crews.';
    }
  }

  /** Add a crew with a lead; an error message when refused (blank, the name already taken, or no lead). */
  async add(name: string, leadId: number | null): Promise<string | null> {
    const n = name.trim();
    if (!n) return 'Enter a crew name.';
    if (leadId === null) return 'Choose a lead.';
    return this.post('', { name: n, leadId }, 'add crew');
  }

  /** Add a person to a crew; an error message when refused (its lead, or already in it). */
  async addMember(crewId: number, personId: number | null): Promise<string | null> {
    if (personId === null) return 'Choose a person.';
    return this.post(`/${crewId}/members`, { personId }, 'add crew member');
  }

  private async post(path: string, body: object, what: string): Promise<string | null> {
    const user = this.session.user();
    const sid = this.session.sheetId();
    if (!user || !sid) return 'Load a sheet first.';
    try {
      const saved = await firstValueFrom(
        this.http.post<CrewView>(this.url(sid) + path, body, { headers: { 'X-User': user } }),
      );
      if (this.session.sheetId() === sid) {
        this.crews.update((cs) =>
          cs.some((c) => c.id === saved.id) ? cs.map((c) => (c.id === saved.id ? saved : c)) : [...cs, saved],
        );
        await this.pools.reread(); // a crew is a pool too
      }
      return null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && typeof e.error?.message === 'string') return e.error.message;
      return e instanceof HttpErrorResponse ? `Could not ${what} (${e.status}).` : `Could not ${what}.`;
    }
  }

  private url(sid: string): string {
    return `/api/sheets/${encodeURIComponent(sid)}/crews`;
  }
}
