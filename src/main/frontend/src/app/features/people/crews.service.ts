import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';

/** A person in a crew: their id and name. */
export interface CrewPerson {
  personId: number;
  name: string;
}

/** A crew on the sheet: its name, its lead and its members (never the lead), in the order added. */
export interface Crew {
  id: number;
  name: string;
  lead: CrewPerson;
  members: CrewPerson[];
}

/** A crew's due-total in cents: its lead's and its members' due-totals (in cents, by person id; null
 *  when n/a) added up, n/a ones skipped. */
export function crewDueCents(crew: Crew, due: Map<number, number | null>): number {
  return [crew.lead, ...crew.members].reduce((sum, m) => sum + (due.get(m.personId) ?? 0), 0);
}

// The crews on a sheet, read from and written to the server, and kept for the sheet loaded: re-read
// from the store every time a sheet is loaded.
@Injectable({ providedIn: 'root' })
export class CrewsService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The loaded sheet's crews, in the order they were added. */
  readonly crews = signal<Crew[]>([]);

  constructor() {
    effect(() => {
      const sheet = this.session.sheetId();
      this.session.loads();
      this.crews.set([]);
      if (sheet) void this.read(sheet);
    });
  }

  /** Add a crew led by a person. Refused (409) when the sheet has a crew or a pool by that name and (400) when it is blank. */
  async add(sheet: string, name: string, leadId: number): Promise<void> {
    const all = await firstValueFrom(
      this.http.post<Crew[]>(this.url(sheet), { name, leadId }, { headers: this.session.headers() }),
    );
    if (this.session.sheetId() === sheet) this.crews.set(all);
  }

  /** Add a person to a crew. Refused (409) when they are its lead or already in it. */
  async addMember(sheet: string, crewId: number, personId: number): Promise<void> {
    const all = await firstValueFrom(
      this.http.post<Crew[]>(`${this.url(sheet)}/${crewId}/members`, { personId }, { headers: this.session.headers() }),
    );
    if (this.session.sheetId() === sheet) this.crews.set(all);
  }

  /** Re-read the sheet's crews from the store: a change elsewhere (a merge or transfer of people) moved them. */
  refresh(sheet: string): Promise<void> {
    return this.read(sheet);
  }

  private async read(sheet: string): Promise<void> {
    try {
      const all = await firstValueFrom(this.http.get<Crew[]>(this.url(sheet), { headers: this.session.headers() }));
      if (this.session.sheetId() === sheet) this.crews.set(all);
    } catch {
      // The crews could not be read; they show as none.
    }
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/crews`;
  }
}
