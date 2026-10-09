import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface Client {
  id: number;
  name: string;
  email: string;
  archived: boolean;
  /** The client's main contact; null until one is chosen. */
  primaryContact: { id: number; name: string } | null;
}

/** At-a-glance counts of what one client has. */
export interface ClientSummary {
  projectCount: number;
  contactCount: number;
}

export type NewClient = Omit<Client, 'id' | 'archived' | 'primaryContact'>;

@Injectable({ providedIn: 'root' })
export class ClientService {
  private readonly http = inject(HttpClient);

  list(includeArchived = false): Observable<Client[]> {
    return this.http.get<Client[]>('/api/clients', { params: { includeArchived } });
  }

  get(id: number): Observable<Client> {
    return this.http.get<Client>(`/api/clients/${id}`);
  }

  summary(id: number): Observable<ClientSummary> {
    return this.http.get<ClientSummary>(`/api/clients/${id}/summary`);
  }

  create(client: NewClient): Observable<Client> {
    return this.http.post<Client>('/api/clients', client);
  }

  archive(id: number): Observable<Client> {
    return this.http.post<Client>(`/api/clients/${id}/archive`, null);
  }

  restore(id: number): Observable<Client> {
    return this.http.post<Client>(`/api/clients/${id}/restore`, null);
  }
}
