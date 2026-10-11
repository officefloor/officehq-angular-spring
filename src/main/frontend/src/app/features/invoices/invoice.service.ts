import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

// A sent invoice is PART_PAID while some of it has been paid and some is still due, and PAID once
// nothing is due; it is OVERDUE while something is still due after its due date, part paid or not.
// The server works this out from its payments and today's date. A VOID invoice was voided while sent
// and unpaid: it no longer counts towards the balance, takes no payments and cannot be changed.
export type InvoiceStatus = 'DRAFT' | 'SENT' | 'PART_PAID' | 'PAID' | 'OVERDUE' | 'VOID';

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
// cents in proportion to the line amounts by the server; the shares add up to the discount. `tax` is
// the tax rate on the amount less that share, rounded half up to the cent; a `taxExempt` line
// (postage, say) has none. `taxRate` is the line's own tax rate (without trailing zeros), null when
// it uses the workspace rate. `discountPct` is the line's own
// discount (without trailing zeros), `discount` what it takes off quantity x unit price, and
// `amount` is after it.
export interface InvoiceLineView {
  id: number;
  description: string;
  qty: string;
  unitPrice: string;
  discountPct: string;
  discount: string;
  amount: string;
  discountShare: string;
  tax: string;
  taxExempt: boolean;
  taxRate: string | null;
}

// A payment made to an opened invoice: the whole amount paid and `allocated`, how much of it went to
// this invoice, decimal strings to the cent; the date it was paid (yyyy-mm-dd) and the RF reference it
// quoted in groups of four (null when none).
export interface InvoicePaymentView {
  id: number;
  amount: string;
  allocated: string;
  date: string;
  reference: string | null;
}

// An opened invoice with its lines and totals, all worked out by the server. `discountPct` is the
// percentage taken off the subtotal, without trailing zeros ("12.5"), and `discountAmount` the fixed
// amount taken off instead ("0.00" when the discount is a percentage); `discountCap` is the most taken
// off in all, line discounts included (null when uncapped); `discount` is the amount the invoice
// discount takes off, rounded half up to the cent and shrunk so the total taken off stays within the cap; `taxRate` is the workspace's tax percentage (without
// trailing zeros, "0" when none is set) and `tax` is the sum of the line taxes; `total` is the subtotal less the discount plus the tax.
// `payments` are the payments made to it, oldest first; `paid` is what they add up to and
// `amountDue` is the total less that. `reference` is its ISO 11649 RF payment reference, made from
// the seven digits of its number, in groups of four (RF16 0000 075).
export interface InvoiceDetail {
  id: number;
  number: string;
  reference: string;
  clientId: number;
  status: InvoiceStatus;
  issueDate: string | null;
  dueDate: string | null;
  lines: InvoiceLineView[];
  subtotal: string;
  discountPct: string;
  discountAmount: string;
  discountCap: string | null;
  discount: string;
  taxRate: string;
  tax: string;
  total: string;
  payments: InvoicePaymentView[];
  paid: string;
  amountDue: string;
}

// A payment to record against a sent invoice: the amount as entered, the date (yyyy-mm-dd) and the
// RF reference the client quoted ('' when none), checked again by the server.
export interface NewPayment {
  amount: string;
  date: string;
  reference: string;
}

// The part of a split payment that goes to one invoice: the amount as entered.
export interface NewAllocation {
  invoiceId: number;
  amount: string;
}

// A payment spread over several of a client's invoices, with the part going to each.
export interface NewSplitPayment extends NewPayment {
  allocations: NewAllocation[];
}

// A line to add to a draft: quantity, unit price, the line's own discount percentage and its own tax
// rate as entered (each left out when none), checked again by the server.
export interface NewLine {
  description: string;
  qty: string;
  unitPrice: string;
  taxExempt: boolean;
  discountPct?: string;
  taxRate?: string;
}

// A discount to give: either a percentage or a fixed amount, as entered, with an optional cap on
// what a percentage takes off.
export type NewDiscount = ({ discountPct: string } | { discountAmount: string }) & { discountCap?: string };

// What a client still owes across its sent invoices (drafts do not count), after discounts, tax and
// payments, and how much of that is overdue; decimal strings to the cent, worked out by the server.
export interface ClientBalance {
  clientId: number;
  balance: string;
  overdue: string;
}

// The invoices REST API.
@Injectable({ providedIn: 'root' })
export class InvoiceService {
  private readonly http = inject(HttpClient);

  forClient(clientId: number): Observable<InvoiceSummary[]> {
    return this.http.get<InvoiceSummary[]>(`/api/clients/${clientId}/invoices`);
  }

  // The balance of each client with a sent invoice; a client not listed owes nothing.
  balances(): Observable<ClientBalance[]> {
    return this.http.get<ClientBalance[]>('/api/clients/balances');
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

  // Removes a line from a draft, or from a sent invoice with no payments (putting it back to draft);
  // refused (409) when what is left would be less than a fixed discount. Answers the invoice with its
  // remaining lines and new totals.
  removeLine(invoiceId: number, lineId: number): Observable<InvoiceDetail> {
    return this.http.delete<InvoiceDetail>(`/api/invoices/${invoiceId}/lines/${lineId}`);
  }

  // Gives a draft invoice a discount — a percentage (0 to 100, up to two decimals) or a fixed amount
  // (to the cent, at most the subtotal); the one given clears the other. Answers the invoice with
  // its new totals.
  setDiscount(invoiceId: number, discount: NewDiscount): Observable<InvoiceDetail> {
    return this.http.put<InvoiceDetail>(`/api/invoices/${invoiceId}/discount`, discount);
  }

  // Sends a draft invoice, keeping its issue and due dates; refused (409) when it has no lines.
  send(invoiceId: number): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/invoices/${invoiceId}/send`, null);
  }

  // Sends every draft of the client that has at least one line, lowest id first; drafts with no
  // lines stay drafts. Answers the client's invoices with their new statuses.
  sendAllDrafts(clientId: number): Observable<InvoiceSummary[]> {
    return this.http.post<InvoiceSummary[]>(`/api/clients/${clientId}/invoices/send-drafts`, null);
  }

  // Copies an invoice into a new draft for the same client: its lines and invoice discount, issued
  // today and due as any new invoice; payments are not copied. Answers the new draft.
  duplicate(invoiceId: number): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/invoices/${invoiceId}/duplicate`, null);
  }

  // Voids a sent invoice with no payments; refused (409) for a draft, a void invoice or one with
  // payments. Answers the invoice with its new status.
  void(invoiceId: number): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/invoices/${invoiceId}/void`, null);
  }

  // Records a payment against a sent invoice that still has something due; refused (409) when it
  // is a draft, already paid, the amount is more than is due, or the date is after today or before
  // the issue date, or the reference is mistyped (400) or another invoice's (409). Answers the invoice with its payments and new status.
  recordPayment(invoiceId: number, payment: NewPayment): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/invoices/${invoiceId}/payments`, payment);
  }

  // Records one payment spread over several of the client's invoices; refused (409) unless the parts
  // add up to the payment and each goes to a sent invoice of the client, no more than it has due.
  // Answers the client's invoices with their new statuses.
  recordSplitPayment(clientId: number, payment: NewSplitPayment): Observable<InvoiceSummary[]> {
    return this.http.post<InvoiceSummary[]>(`/api/clients/${clientId}/payments`, payment);
  }

  // Reverses a payment (a bounced cheque) on every invoice it was applied to; refused (409) when it
  // has already been reversed. Answers the opened invoice with its payments and new status.
  reversePayment(invoiceId: number, paymentId: number): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/invoices/${invoiceId}/payments/${paymentId}/reverse`, null);
  }
}
