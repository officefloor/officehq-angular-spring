import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export const INVOICE_STATUSES = ['DRAFT', 'SENT', 'PARTIAL', 'PAID'] as const;

export type InvoiceStatus = (typeof INVOICE_STATUSES)[number];

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

/** An invoice as listed for its project, with how much is still left to pay after any payments. */
export interface ProjectInvoice extends Invoice {
  amountDue: number;
}

/** An invoice as listed across all projects, with the name of the project it is for. */
export interface InvoiceSummary extends Invoice {
  projectName: string;
}

/** One thing an invoice charges for; its amount is quantity times unit price. */
export interface LineItem {
  id: number;
  description: string;
  qty: number;
  unitPrice: number;
  amount: number;
}

/** A single invoice with the line items it is built from. */
export interface InvoiceDetail extends Invoice {
  lineItems: LineItem[];
}

export interface NewLineItem {
  description: string;
  qty: number;
  unitPrice: number;
}

export interface NewInvoice {
  /** Raises the invoice as one figure; omit to start an empty draft built up from line items. */
  amount?: number;
  /** Defaults to today on the server when omitted. */
  issuedDate?: string;
  /** Defaults to 30 days after the issue date on the server when omitted. */
  dueDate?: string;
}

@Injectable({ providedIn: 'root' })
export class InvoiceService {
  private readonly http = inject(HttpClient);

  /** Every invoice across all projects, optionally narrowed to one status. */
  listAll(status?: InvoiceStatus): Observable<InvoiceSummary[]> {
    const params: Record<string, string> = status ? { status } : {};
    return this.http.get<InvoiceSummary[]>('/api/invoices', { params });
  }

  listForProject(projectId: number): Observable<ProjectInvoice[]> {
    return this.http.get<ProjectInvoice[]>(`/api/projects/${projectId}/invoices`);
  }

  create(projectId: number, invoice: NewInvoice): Observable<ProjectInvoice> {
    return this.http.post<ProjectInvoice>(`/api/projects/${projectId}/invoices`, invoice);
  }

  get(projectId: number, invoiceId: number): Observable<InvoiceDetail> {
    return this.http.get<InvoiceDetail>(`/api/projects/${projectId}/invoices/${invoiceId}`);
  }

  addLineItem(projectId: number, invoiceId: number, item: NewLineItem): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/projects/${projectId}/invoices/${invoiceId}/line-items`, item);
  }

  updateLineItem(projectId: number, invoiceId: number, lineItemId: number, item: NewLineItem): Observable<InvoiceDetail> {
    return this.http.put<InvoiceDetail>(
      `/api/projects/${projectId}/invoices/${invoiceId}/line-items/${lineItemId}`,
      item,
    );
  }

  removeLineItem(projectId: number, invoiceId: number, lineItemId: number): Observable<InvoiceDetail> {
    return this.http.delete<InvoiceDetail>(`/api/projects/${projectId}/invoices/${invoiceId}/line-items/${lineItemId}`);
  }

  send(projectId: number, invoiceId: number): Observable<ProjectInvoice> {
    return this.http.post<ProjectInvoice>(`/api/projects/${projectId}/invoices/${invoiceId}/send`, null);
  }
}
