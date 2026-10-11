import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface Client {
  id: number;
  name: string;
  email: string;
  // Payment terms in days; null means due at the end of the month after issue.
  termsDays: number | null;
  // The standard percentage discount new invoices for the client start with; 0 is none.
  discountPct: number;
  // Whether the client is tax exempt (a charity, say): its invoices charge no tax on any line.
  taxExempt: boolean;
}

export type NewClient = Omit<Client, 'id'>;

// The clients REST API.
@Injectable({ providedIn: 'root' })
export class ClientService {
  private readonly http = inject(HttpClient);

  list(): Observable<Client[]> {
    return this.http.get<Client[]>('/api/clients');
  }

  add(client: NewClient): Observable<Client> {
    return this.http.post<Client>('/api/clients', client);
  }
}

// Clients A to Z by name, ignoring capitals — the same order the server lists them in.
export function byName(a: Client, b: Client): number {
  const x = a.name.toLowerCase();
  const y = b.name.toLowerCase();
  return x < y ? -1 : x > y ? 1 : a.name < b.name ? -1 : a.name > b.name ? 1 : a.id - b.id;
}
