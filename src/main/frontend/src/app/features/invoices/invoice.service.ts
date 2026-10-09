import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type InvoiceStatus = 'DRAFT' | 'SENT' | 'PAID';

export interface Invoice {
  id: number;
  projectId: number;
  amount: number;
  status: InvoiceStatus;
  /** ISO date (yyyy-MM-dd) the invoice was issued. */
  issuedDate: string;
  /** ISO date (yyyy-MM-dd) payment is due. */
  dueDate: string;
}

/** An invoice as listed across all projects, with the name of the project it is for. */
export interface InvoiceSummary extends Invoice {
  projectName: string;
}

export interface NewInvoice {
  amount: number;
  /** Defaults to today on the server when omitted. */
  issuedDate?: string;
  /** Defaults to 30 days after the issue date on the server when omitted. */
  dueDate?: string;
}

@Injectable({ providedIn: 'root' })
export class InvoiceService {
  private readonly http = inject(HttpClient);

  listAll(): Observable<InvoiceSummary[]> {
    return this.http.get<InvoiceSummary[]>('/api/invoices');
  }

  listForProject(projectId: number): Observable<Invoice[]> {
    return this.http.get<Invoice[]>(`/api/projects/${projectId}/invoices`);
  }

  create(projectId: number, invoice: NewInvoice): Observable<Invoice> {
    return this.http.post<Invoice>(`/api/projects/${projectId}/invoices`, invoice);
  }

  send(projectId: number, invoiceId: number): Observable<Invoice> {
    return this.http.post<Invoice>(`/api/projects/${projectId}/invoices/${invoiceId}/send`, null);
  }

  pay(projectId: number, invoiceId: number): Observable<Invoice> {
    return this.http.post<Invoice>(`/api/projects/${projectId}/invoices/${invoiceId}/pay`, null);
  }
}
