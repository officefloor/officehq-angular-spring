import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** The credit a client has to spend: unused deposits plus unused credit notes, added up in `total`. */
export interface ClientCredit {
  deposits: number;
  creditNotes: number;
  total: number;
}

/** Unused credit to pay back to a client, with an optional note of why. */
export interface NewRefund {
  amount: number;
  note: string | null;
}

/** Unused credit paid back to a client, split between their held deposits and unused credit notes. */
export interface Refund {
  id: number;
  clientId: number;
  amount: number;
  fromDeposits: number;
  fromCreditNotes: number;
  /** ISO date (yyyy-MM-dd) the refund was made. */
  date: string;
  note: string | null;
}

@Injectable({ providedIn: 'root' })
export class CreditService {
  private readonly http = inject(HttpClient);

  available(clientId: number): Observable<ClientCredit> {
    return this.http.get<ClientCredit>(`/api/clients/${clientId}/credit`);
  }

  /** Pays part of the client's unused credit back to them. */
  refund(clientId: number, refund: NewRefund): Observable<Refund> {
    return this.http.post<Refund>(`/api/clients/${clientId}/refunds`, refund);
  }
}
