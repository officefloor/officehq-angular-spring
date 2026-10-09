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

/** Part of a client's held deposits to put toward their invoices, split across them. */
export interface NewDepositApplication {
  allocations: { invoiceId: number; amount: number }[];
}

/** The deposits a client has paid, oldest first; `total` is what is still held after `applied` was put toward invoices. */
export interface ClientDeposits {
  total: number;
  applied: number;
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

  /** Puts part of the client's held deposits toward the given invoices. */
  apply(clientId: number, application: NewDepositApplication): Observable<unknown> {
    return this.http.post(`/api/clients/${clientId}/deposits/applications`, application);
  }
}
