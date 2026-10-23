import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** A crew's member: a person on the sheet and their name. */
export interface CrewMemberView {
  personId: number;
  name: string;
}

/** A crew on the sheet: a name, once per sheet, its lead, and its members in the order they were added. */
export interface CrewView {
  id: number;
  name: string;
  leadId: number;
  lead: string;
  members: CrewMemberView[];
}

/**
 * A crew's due-total in whole cents: its lead's and members' due-totals, as shown, added up, skipping
 * those shown as n/a (null) in the given due-totals by person id.
 */
export function crewDueCents(crew: CrewView, dues: Map<number, number | null>): number {
  return [crew.leadId, ...crew.members.map((m) => m.personId)].reduce((sum, id) => sum + (dues.get(id) ?? 0), 0);
}

// The sheet's crews, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class CrewsApi {
  private readonly http = inject(HttpClient);

  /** The sheet's crews in the order they were added. */
  list(sheet: string): Promise<CrewView[]> {
    return firstValueFrom(this.http.get<CrewView[]>(this.url(sheet)));
  }

  /** Add a crew led by a person, and its pool; refused when its name, trimmed, is blank or already a crew's or a pool's. */
  add(sheet: string, name: string, leadId: number): Promise<CrewView> {
    return firstValueFrom(this.http.post<CrewView>(this.url(sheet), { name, leadId }));
  }

  /** Add a person to a crew; refused when they lead it or are in it already. */
  addMember(sheet: string, crewId: number, personId: number): Promise<CrewView> {
    return firstValueFrom(this.http.post<CrewView>(`${this.url(sheet)}/${crewId}/members`, { personId }));
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/crews`;
  }
}
