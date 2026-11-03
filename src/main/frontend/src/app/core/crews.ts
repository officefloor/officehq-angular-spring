import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/** A person of a crew: their id and name. */
export interface CrewPerson {
  personId: number;
  name: string;
}

/** A crew on the sheet: its lead (null when they have left the sheet) and its members in the order
 *  they were added. */
export interface Crew {
  id: number;
  name: string;
  lead: CrewPerson | null;
  members: CrewPerson[];
}

// The crews of the active sheet, re-read whenever the sheet is loaded.
@Injectable({ providedIn: 'root' })
export class Crews {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The sheet's crews in the order they were added. */
  readonly list = signal<Crew[]>([]);
  private reads = 0;

  constructor() {
    effect(() => {
      this.session.loads();
      const sheetId = this.session.sheetId();
      this.list.set([]);
      if (sheetId) void this.reload(sheetId);
    });
  }

  /** Add a crew with a lead; refused (400) when the name is blank and (409) when it is already a
   *  crew. */
  async add(name: string, leadId: number): Promise<void> {
    const { sheetId, user } = this.active();
    const saved = await firstValueFrom(
      this.http.post<Crew>(this.url(sheetId), { name, leadId }, { headers: { 'X-User': user } }),
    );
    this.keep(saved);
  }

  /** Add a person to a crew; refused (409) when they lead it or are already in it. */
  async addMember(crewId: number, personId: number): Promise<void> {
    const { sheetId, user } = this.active();
    const saved = await firstValueFrom(
      this.http.post<Crew>(`${this.url(sheetId)}/${crewId}/members`, { personId }, { headers: { 'X-User': user } }),
    );
    this.keep(saved);
  }

  private keep(saved: Crew): void {
    this.list.update((cs) =>
      cs.some((c) => c.id === saved.id) ? cs.map((c) => (c.id === saved.id ? saved : c)) : [...cs, saved],
    );
  }

  private async reload(sheetId: string): Promise<void> {
    const read = ++this.reads;
    try {
      const crews = await firstValueFrom(this.http.get<Crew[]>(this.url(sheetId)));
      if (read === this.reads) this.list.set(crews);
    } catch {
      // the list stays empty; the next load reads it again
    }
  }

  private active(): { sheetId: string; user: string } {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    return { sheetId, user };
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/crews`;
  }
}
