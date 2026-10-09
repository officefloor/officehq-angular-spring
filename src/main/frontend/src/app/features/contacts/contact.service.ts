import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface Contact {
  id: number;
  clientId: number;
  name: string;
  email: string;
  role: string;
  /** Whether this is the client's main contact. */
  primary: boolean;
}

export type NewContact = Pick<Contact, 'name' | 'email' | 'role'>;

@Injectable({ providedIn: 'root' })
export class ContactService {
  private readonly http = inject(HttpClient);

  listForClient(clientId: number): Observable<Contact[]> {
    return this.http.get<Contact[]>(`/api/clients/${clientId}/contacts`);
  }

  create(clientId: number, contact: NewContact): Observable<Contact> {
    return this.http.post<Contact>(`/api/clients/${clientId}/contacts`, contact);
  }

  makePrimary(clientId: number, contactId: number): Observable<Contact> {
    return this.http.post<Contact>(`/api/clients/${clientId}/contacts/${contactId}/primary`, null);
  }
}
