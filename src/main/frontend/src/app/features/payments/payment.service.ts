import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Money a client has paid against an invoice. */
export interface Payment {
  id: number;
  invoiceId: number;
  amount: number;
  /** ISO date (yyyy-MM-dd) the payment was made. */
  date: string;
  /** What was received when paid in another currency than the invoice's (converted on the payment's date); else null. */
  paidAmount?: number | null;
  /** The currency the payment was received in, when not the invoice's; else null. */
  paidCurrency?: string | null;
}

/** A payment to record; `amount` is in `currency`, or the invoice's currency when none is given. */
export interface NewPayment {
  amount: number;
  date: string;
  currency?: string;
}

/**
 * A lump payment from a client, split across several of their invoices. With `useCredit` the client's credit is used
 * up first; the shares may add up to no more than the amount plus any credit used, and the rest is kept as credit.
 */
export interface NewClientPayment {
  amount: number;
  date: string;
  allocations: { invoiceId: number; amount: number }[];
  useCredit: boolean;
}

/** A recorded lump payment, the credit it used up, what was kept as credit, and the payment it made against each invoice. */
export interface ClientPayment {
  id: number;
  clientId: number;
  amount: number;
  date: string;
  fromDeposits: number;
  fromCreditNotes: number;
  toCredit: number;
  allocations: Payment[];
}

@Injectable({ providedIn: 'root' })
export class PaymentService {
  private readonly http = inject(HttpClient);

  /** An invoice's payments, oldest first. */
  list(projectId: number, invoiceId: number): Observable<Payment[]> {
    return this.http.get<Payment[]>(`/api/projects/${projectId}/invoices/${invoiceId}/payments`);
  }

  record(projectId: number, invoiceId: number, payment: NewPayment): Observable<Payment> {
    return this.http.post<Payment>(`/api/projects/${projectId}/invoices/${invoiceId}/payments`, payment);
  }

  /** Records one lump payment from a client, split across the given invoices. */
  recordForClient(clientId: number, payment: NewClientPayment): Observable<ClientPayment> {
    return this.http.post<ClientPayment>(`/api/clients/${clientId}/payments`, payment);
  }
}
