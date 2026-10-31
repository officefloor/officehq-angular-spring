import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

/** A person of a crew: its lead or one of its members. */
export interface CrewPersonView {
  id: number;
  name: string;
}

/** A crew on the sheet: its lead (null once the lead has left the sheet) and its members, in the order added. */
export interface CrewView {
  id: number;
  name: string;
  lead: CrewPersonView | null;
  members: CrewPersonView[];
}

/** A crew's due-total in whole cents: its lead's and members' due-totals added up, skipping those with none. */
export function crewDueCents(crew: CrewView, dueByPerson: ReadonlyMap<number, number | null>): number {
  const ids = [...(crew.lead ? [crew.lead.id] : []), ...crew.members.map((m) => m.id)];
  return ids.reduce((sum, id) => sum + (dueByPerson.get(id) ?? 0), 0);
}

// The crews on the active sheet, in the order they were added.
@Injectable({ providedIn: 'root' })
export class Crews {
  private readonly http = inject(HttpClient);
  private readonly list = signal<CrewView[]>([]);
  readonly all = this.list.asReadonly();

  /** Re-read a sheet's crews from the store; null clears them. */
  load(sheetId: string | null): Observable<CrewView[]> | null {
    this.list.set([]);
    if (sheetId === null) return null;
    return this.http.get<CrewView[]>(this.base(sheetId)).pipe(tap((list) => this.list.set(list)));
  }

  /** Add a crew led by a person of the sheet; refused when the name is blank or already a crew on the sheet. */
  add(sheetId: string, name: string, leadId: number): Observable<CrewView> {
    return this.http.post<CrewView>(this.base(sheetId), { name, leadId }).pipe(
      tap((added) => this.list.update((list) => [...list, added])),
    );
  }

  /** Add a person to a crew; refused when they lead it or are already in it. */
  addMember(sheetId: string, crewId: number, personId: number): Observable<CrewView> {
    return this.http.post<CrewView>(`${this.base(sheetId)}/${crewId}/members`, { personId }).pipe(
      tap((saved) => this.list.update((list) => list.map((c) => (c.id === saved.id ? saved : c)))),
    );
  }

  private base(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/crews`;
  }
}
