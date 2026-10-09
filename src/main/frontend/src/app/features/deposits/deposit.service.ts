import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Money a client paid up front, before any invoice, held against the client. */
export interface Deposit {
  id: number;
  clientId: number;
  amount: number;
  /** ISO date (yyyy-MM-dd) the deposit was paid. */
  date: string;
}

export interface NewDeposit {
  amount: number;
  date: string;
}

/** The deposits held for a client, oldest first, and their total. */
export interface ClientDeposits {
  total: number;
  deposits: Deposit[];
}

@Injectable({ providedIn: 'root' })
export class DepositService {
  private readonly http = inject(HttpClient);

  list(clientId: number): Observable<ClientDeposits> {
    return this.http.get<ClientDeposits>(`/api/clients/${clientId}/deposits`);
  }

  record(clientId: number, deposit: NewDeposit): Observable<Deposit> {
    return this.http.post<Deposit>(`/api/clients/${clientId}/deposits`, deposit);
  }
}
