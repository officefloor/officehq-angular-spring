import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type InvoiceStatus = 'UNPAID' | 'PAID';

export interface Invoice {
  id: number;
  projectId: number;
  amount: number;
  status: InvoiceStatus;
}

export interface NewInvoice {
  amount: number;
}

@Injectable({ providedIn: 'root' })
export class InvoiceService {
  private readonly http = inject(HttpClient);

  listForProject(projectId: number): Observable<Invoice[]> {
    return this.http.get<Invoice[]>(`/api/projects/${projectId}/invoices`);
  }

  create(projectId: number, invoice: NewInvoice): Observable<Invoice> {
    return this.http.post<Invoice>(`/api/projects/${projectId}/invoices`, invoice);
  }

  pay(projectId: number, invoiceId: number): Observable<Invoice> {
    return this.http.post<Invoice>(`/api/projects/${projectId}/invoices/${invoiceId}/pay`, null);
  }
}
