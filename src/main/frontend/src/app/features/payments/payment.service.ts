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
}

export interface NewPayment {
  amount: number;
  date: string;
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
}
