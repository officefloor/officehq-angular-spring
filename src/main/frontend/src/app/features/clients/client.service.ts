import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** A currency a client can be billed in, by ISO 4217 code; the known ones come from the currency service. */
export type CurrencyCode = string;

export interface Client {
  id: number;
  name: string;
  email: string;
  /** A number to reach the client on; null when not known. */
  phone: string | null;
  /** The client's tax registration number; null when they are not tax registered. */
  taxNumber: string | null;
  /** Where the client's bills are sent; null when not known. */
  billingAddress: string | null;
  /** The language the client prefers to be dealt with in; null when not known. */
  language: string | null;
  /** The person in the office who looks after the client; null when not recorded. */
  accountManager: string | null;
  /** Whether the client's prices already include tax, so it is worked back out of them rather than added on. */
  taxInclusive: boolean;
  /** Whether the client is tax exempt, so none of their invoices carry any tax whatever the lines say. */
  taxExempt: boolean;
  /** Whether the client is one of the office's key accounts, marked out wherever they are listed. */
  keyAccount: boolean;
  /** The client's standard discount, a percentage each new invoice for them starts with; 0 when none. */
  defaultDiscountPct: number;
  archived: boolean;
  /** The client's main contact; null until one is chosen. */
  primaryContact: { id: number; name: string } | null;
  /** The currency the client is billed in; all of their money is shown in it. */
  currency: CurrencyCode;
  /** The most the client may owe, in their currency; null when no limit is set. */
  creditLimit: number | null;
  /** What is left to pay on the client's sent, not yet fully paid invoices, in their currency. */
  outstanding: number;
}

/** At-a-glance counts of what one client has. */
export interface ClientSummary {
  projectCount: number;
  contactCount: number;
  lifetimeBilled: number;
}

export type NewClient = Omit<Client, 'id' | 'archived' | 'primaryContact' | 'currency' | 'creditLimit' | 'outstanding'>;

/** Where a client's contact details file is downloaded from. */
export function exportUrl(id: number): string {
  return `/api/clients/${id}/export`;
}

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

  /** The client's contact details as the CSV file the export downloads. */
  exportContactDetails(id: number): Observable<string> {
    return this.http.get(exportUrl(id), { responseType: 'text' });
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

  /** Sets the most the client may owe, in their currency; null removes the limit. */
  changeCreditLimit(id: number, creditLimit: number | null): Observable<Client> {
    return this.http.put<Client>(`/api/clients/${id}/credit-limit`, { creditLimit });
  }

  /** Merges the client into another one, which keeps everything the client had; the client is removed. */
  merge(id: number, targetId: number): Observable<Client> {
    return this.http.post<Client>(`/api/clients/${id}/merge`, { targetId });
  }

  archive(id: number): Observable<Client> {
    return this.http.post<Client>(`/api/clients/${id}/archive`, null);
  }

  restore(id: number): Observable<Client> {
    return this.http.post<Client>(`/api/clients/${id}/restore`, null);
  }
}
