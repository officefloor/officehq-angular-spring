import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type InvoiceStatus = 'DRAFT' | 'SENT';

// An invoice as listed for its client. `total` is a decimal string to the cent, worked out by the
// server: each line's quantity times unit price rounded half up to the cent, then added up.
export interface InvoiceSummary {
  id: number;
  // INV-, the id padded to six digits, then a Luhn check digit, e.g. INV-0000075.
  number: string;
  status: InvoiceStatus;
  total: string;
  issueDate: string | null;
  dueDate: string | null;
}

// One line of an opened invoice. `qty` is as entered, without trailing zeros ("1.5"); money is a
// decimal string to the cent.
export interface InvoiceLineView {
  id: number;
  description: string;
  qty: string;
  unitPrice: string;
  amount: string;
}

// An opened invoice with its lines and totals, all worked out by the server. `discountPct` is the
// percentage taken off the subtotal, without trailing zeros ("12.5"); `discount` is the amount it
// takes off, rounded half up to the cent; `total` is the subtotal less the discount.
export interface InvoiceDetail {
  id: number;
  number: string;
  clientId: number;
  status: InvoiceStatus;
  issueDate: string | null;
  dueDate: string | null;
  lines: InvoiceLineView[];
  subtotal: string;
  discountPct: string;
  discount: string;
  total: string;
}

// A line to add to a draft: quantity and unit price as entered, checked again by the server.
export interface NewLine {
  description: string;
  qty: string;
  unitPrice: string;
}

// The invoices REST API.
@Injectable({ providedIn: 'root' })
export class InvoiceService {
  private readonly http = inject(HttpClient);

  forClient(clientId: number): Observable<InvoiceSummary[]> {
    return this.http.get<InvoiceSummary[]>(`/api/clients/${clientId}/invoices`);
  }

  // Starts an empty draft for the client, issued today and due on the last day of the following month.
  newDraft(clientId: number): Observable<InvoiceSummary> {
    return this.http.post<InvoiceSummary>(`/api/clients/${clientId}/invoices`, null);
  }

  open(id: number): Observable<InvoiceDetail> {
    return this.http.get<InvoiceDetail>(`/api/invoices/${id}`);
  }

  // Opens the invoice with a typed number; refused (400) when its check digit does not match.
  openByNumber(number: string): Observable<InvoiceDetail> {
    return this.http.get<InvoiceDetail>(`/api/invoices/by-number/${encodeURIComponent(number)}`);
  }

  // Adds a line to a draft invoice; answers the invoice with its new lines and totals.
  addLine(invoiceId: number, line: NewLine): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/invoices/${invoiceId}/lines`, line);
  }

  // Gives a draft invoice a percentage discount (0 to 100, up to two decimals); answers the
  // invoice with its new totals.
  setDiscount(invoiceId: number, discountPct: string): Observable<InvoiceDetail> {
    return this.http.put<InvoiceDetail>(`/api/invoices/${invoiceId}/discount`, { discountPct });
  }
}
