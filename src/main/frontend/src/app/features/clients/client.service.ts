import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface Client {
  id: number;
  name: string;
  email: string;
}

/** At-a-glance counts of what one client has. */
export interface ClientSummary {
  projectCount: number;
  contactCount: number;
}

export type NewClient = Omit<Client, 'id'>;

@Injectable({ providedIn: 'root' })
export class ClientService {
  private readonly http = inject(HttpClient);

  list(): Observable<Client[]> {
    return this.http.get<Client[]>('/api/clients');
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
}
