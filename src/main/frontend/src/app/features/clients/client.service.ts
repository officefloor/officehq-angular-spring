import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** The currencies a client can be billed in, by ISO 4217 code. */
export const CURRENCIES = ['USD', 'EUR', 'GBP', 'CAD', 'AUD'] as const;

export type CurrencyCode = (typeof CURRENCIES)[number];

export interface Client {
  id: number;
  name: string;
  email: string;
  /** A number to reach the client on; null when not known. */
  phone: string | null;
  /** The client's tax registration number; null when they are not tax registered. */
  taxNumber: string | null;
  /** Whether the client's prices already include tax, so it is worked back out of them rather than added on. */
  taxInclusive: boolean;
  archived: boolean;
  /** The client's main contact; null until one is chosen. */
  primaryContact: { id: number; name: string } | null;
  /** The currency the client is billed in; all of their money is shown in it. */
  currency: CurrencyCode;
  /** What is left to pay on the client's sent, not yet fully paid invoices, in their currency. */
  outstanding: number;
}

/** At-a-glance counts of what one client has. */
export interface ClientSummary {
  projectCount: number;
  contactCount: number;
}

export type NewClient = Omit<Client, 'id' | 'archived' | 'primaryContact' | 'currency' | 'outstanding'>;

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

  update(id: number, client: NewClient): Observable<Client> {
    return this.http.put<Client>(`/api/clients/${id}`, client);
  }

  changeCurrency(id: number, currency: CurrencyCode): Observable<Client> {
    return this.http.put<Client>(`/api/clients/${id}/currency`, { currency });
  }

  archive(id: number): Observable<Client> {
    return this.http.post<Client>(`/api/clients/${id}/archive`, null);
  }

  restore(id: number): Observable<Client> {
    return this.http.post<Client>(`/api/clients/${id}/restore`, null);
  }
}
