import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** One time a client was contacted. */
export interface ContactHistoryEntry {
  id: number;
  clientId: number;
  /** ISO date (yyyy-MM-dd) of the contact. */
  date: string;
  note: string;
}

export type NewContactHistoryEntry = Pick<ContactHistoryEntry, 'date' | 'note'>;

@Injectable({ providedIn: 'root' })
export class ContactHistoryService {
  private readonly http = inject(HttpClient);

  /** When the client was contacted, newest first. */
  list(clientId: number): Observable<ContactHistoryEntry[]> {
    return this.http.get<ContactHistoryEntry[]>(`/api/clients/${clientId}/contact-history`);
  }

  record(clientId: number, entry: NewContactHistoryEntry): Observable<ContactHistoryEntry> {
    return this.http.post<ContactHistoryEntry>(`/api/clients/${clientId}/contact-history`, entry);
  }
}
