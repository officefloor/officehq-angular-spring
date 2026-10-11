import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

// A sent invoice is PART_PAID while some of it has been paid and some is still due, and PAID once
// nothing is due; the server works this out from its payments.
export type InvoiceStatus = 'DRAFT' | 'SENT' | 'PART_PAID' | 'PAID';

// An invoice as listed for its client. `total` is a decimal string to the cent, worked out by the
// server: the grand total — the lines' subtotal less any discount plus tax.
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
// decimal string to the cent. `discountShare` is the line's share of the invoice discount, split in
// cents in proportion to the line amounts by the server; the shares add up to the discount. A
// `taxExempt` line (postage, say) is left out of the taxed amount.
export interface InvoiceLineView {
  id: number;
  description: string;
  qty: string;
  unitPrice: string;
  amount: string;
  discountShare: string;
  taxExempt: boolean;
}

// A payment made to an opened invoice: the amount that went to it, a decimal string to the cent,
// and the date it was paid (yyyy-mm-dd).
export interface InvoicePaymentView {
  id: number;
  amount: string;
  date: string;
}

// An opened invoice with its lines and totals, all worked out by the server. `discountPct` is the
// percentage taken off the subtotal, without trailing zeros ("12.5"); `discount` is the amount it
// takes off, rounded half up to the cent; `taxRate` is the workspace's tax percentage (without
// trailing zeros, "0" when none is set) and `tax` is that rate on the taxable lines' amounts less
// their discount shares, rounded half up to the cent; `total` is the subtotal less the discount plus the tax.
// `payments` are the payments made to it, oldest first; `paid` is what they add up to and
// `amountDue` is the total less that.
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
  taxRate: string;
  tax: string;
  total: string;
  payments: InvoicePaymentView[];
  paid: string;
  amountDue: string;
}

// A payment to record against a sent invoice: the amount as entered and the date (yyyy-mm-dd),
// checked again by the server.
export interface NewPayment {
  amount: string;
  date: string;
}

// A line to add to a draft: quantity and unit price as entered, checked again by the server.
export interface NewLine {
  description: string;
  qty: string;
  unitPrice: string;
  taxExempt: boolean;
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

  // Sends a draft invoice, keeping its issue and due dates; refused (409) when it has no lines.
  send(invoiceId: number): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/invoices/${invoiceId}/send`, null);
  }

  // Records a payment against a sent invoice that still has something due; refused (409) when it
  // is a draft, already paid, or the amount is more than is due. Answers the invoice with its
  // payments and new status.
  recordPayment(invoiceId: number, payment: NewPayment): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/invoices/${invoiceId}/payments`, payment);
  }
}
