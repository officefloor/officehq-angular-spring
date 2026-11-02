import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/** A patron named: the person who named them and the person named, by id. */
export interface Patron {
  id: number;
  personId: number;
  patronId: number;
}

// The patrons people on the active sheet have named.
@Injectable({ providedIn: 'root' })
export class Patrons {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The patrons named on the sheet in the order they were named. */
  list(sheetId: string): Promise<Patron[]> {
    return firstValueFrom(this.http.get<Patron[]>(`/api/sheets/${encodeURIComponent(sheetId)}/patrons`));
  }

  /** Name another person as the person's patron; refused for the person themselves and for a
   *  patron they have already named. */
  add(personId: number, patronId: number): Promise<Patron> {
    const sheetId = this.session.sheetId();
    const user = this.session.user();
    if (!sheetId || !user) throw new Error('no sheet loaded');
    return firstValueFrom(
      this.http.post<Patron>(`/api/sheets/${encodeURIComponent(sheetId)}/people/${personId}/patrons`, { patronId },
        { headers: { 'X-User': user } }),
    );
  }
}
