import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session, otherError } from '../../core/session';

/** A crew on the sheet: its name, its lead's person id and its members' person ids, in the order
 *  they were added. */
export interface Crew {
  name: string;
  leadId: number;
  memberIds: number[];
}

@Injectable({ providedIn: 'root' })
export class CrewsApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The sheet's crews, in the order they were added. */
  list(sheetId: string): Promise<Crew[]> {
    return firstValueFrom(this.http.get<Crew[]>(this.url(sheetId), { headers: this.headers() }));
  }

  /** Add a crew led by a person; resolves to the sheet's crews with it. */
  add(sheetId: string, name: string, leadId: number): Promise<Crew[]> {
    return firstValueFrom(this.http.post<Crew[]>(this.url(sheetId), { name, leadId }, { headers: this.headers() }));
  }

  /** Add a person to a crew; resolves to the sheet's crews with them. */
  addMember(sheetId: string, crew: string, personId: number): Promise<Crew[]> {
    return firstValueFrom(
      this.http.post<Crew[]>(`${this.url(sheetId)}/members`, { crew, personId }, { headers: this.headers() }),
    );
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/crews`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}

export function crewError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Enter a name for the crew and choose its lead.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'The sheet has a crew or a pool of that name already.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That person is no longer on the sheet; load the sheet again.';
  return otherError(e);
}

export function crewMemberError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 409) return 'That person leads the crew or is in it already.';
  if (e instanceof HttpErrorResponse && e.status === 404) return 'That crew or person is no longer on the sheet; load the sheet again.';
  return otherError(e);
}
