import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CurrencyCode } from '../clients/client.service';

export const INVOICE_STATUSES = ['DRAFT', 'SENT', 'PARTIAL', 'PAID', 'VOID'] as const;

export type InvoiceStatus = (typeof INVOICE_STATUSES)[number];

export interface Invoice {
  id: number;
  projectId: number;
  /** The currency of the client the invoice is for; its money is in it. */
  currency: CurrencyCode;
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

/** One page of the invoices listed across all projects; pages are numbered from zero. */
export interface InvoicePage {
  items: InvoiceSummary[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

/** An invoice as listed on a client's statement: its project's name and how much is left to pay. */
export interface StatementInvoice extends ProjectInvoice {
  projectName: string;
}

/** One job on a client's statement: its invoices and what is still owed on them. */
export interface StatementJob {
  projectId: number;
  projectName: string;
  invoices: StatementInvoice[];
  subtotal: number;
}

/** A client's invoices across all their projects, also grouped by job, with the total they still owe. */
export interface ClientStatement {
  clientId: number;
  clientName: string;
  /** The client's currency; every figure on the statement is in it. */
  currency: CurrencyCode;
  invoices: StatementInvoice[];
  jobs: StatementJob[];
  /** The total invoiced on the client's sent invoices (drafts and void ones are left out). */
  invoiced: number;
  /** How much of what was invoiced has been paid. */
  paid: number;
  /** The grand total still owed: what was invoiced less what was paid. */
  outstanding: number;
}

/** One thing an invoice charges for; its amount is quantity times unit price. */
export interface LineItem {
  id: number;
  description: string;
  qty: number;
  /** What the quantity counts, such as hours; null when the line does not say. */
  unit: string | null;
  unitPrice: number;
  amount: number;
  /** Whether the line is tax-free: it is not taxable. */
  taxExempt: boolean;
}

/**
 * A single invoice with the line items it is built from: their subtotal, the percentage discount and
 * what it takes off, and the sales tax percentage, the taxable base it is charged on (the taxable
 * lines after the discount, leaving out tax-free ones) and what it adds, and the levy (a second tax)
 * percentage and what it adds on that same base; its amount is the subtotal less the discount plus
 * the tax plus the levy.
 */
export interface InvoiceDetail extends Invoice {
  /** The total before tax: the amount less the sales tax and levy. */
  totalExTax: number;
  subtotal: number;
  discountPct: number;
  discount: number;
  taxPct: number;
  taxableBase: number;
  tax: number;
  levyPct: number;
  levy: number;
  /** Whether the prices already include the tax and levy, so they are worked back out rather than added on. */
  taxInclusive: boolean;
  /** Whether the client is tax exempt, so the invoice carries no tax or levy whatever its lines say. */
  taxExempt: boolean;
  /** The overall tax rate that ended up on the invoice: the tax and levy as a percentage of the total before tax. */
  effectiveTaxPct: number;
  lineItems: LineItem[];
  /** The client's tax registration number; null when they are not tax registered. */
  clientTaxNumber: string | null;
}

export interface NewLineItem {
  description: string;
  qty: number;
  unit: string | null;
  unitPrice: number;
  taxExempt: boolean;
}

export interface NewInvoice {
  /** Raises the invoice as one figure; omit to start an empty draft built up from line items. */
  amount?: number;
  /** Defaults to today on the server when omitted. */
  issuedDate?: string;
  /** Defaults to 30 days after the issue date on the server when omitted. */
  dueDate?: string;
  /** The sales tax percentage; defaults to the default tax rate in the settings on the server when omitted. */
  taxPct?: number;
}

@Injectable({ providedIn: 'root' })
export class InvoiceService {
  private readonly http = inject(HttpClient);

  /** One page (numbered from zero) of the invoices across all projects, optionally narrowed to one status. */
  listAll(page: number, status?: InvoiceStatus): Observable<InvoicePage> {
    const params: Record<string, string | number> = status ? { status, page } : { page };
    return this.http.get<InvoicePage>('/api/invoices', { params });
  }

  listForProject(projectId: number): Observable<ProjectInvoice[]> {
    return this.http.get<ProjectInvoice[]>(`/api/projects/${projectId}/invoices`);
  }

  statementForClient(clientId: number): Observable<ClientStatement> {
    return this.http.get<ClientStatement>(`/api/clients/${clientId}/statement`);
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

  /** Sets the percentage taken off a draft invoice; zero removes the discount. */
  applyDiscount(projectId: number, invoiceId: number, discountPct: number): Observable<InvoiceDetail> {
    return this.http.put<InvoiceDetail>(`/api/projects/${projectId}/invoices/${invoiceId}/discount`, { discountPct });
  }

  /** Sets the sales tax percentage added to a draft invoice after its discount; zero removes the tax. */
  applyTax(projectId: number, invoiceId: number, taxPct: number): Observable<InvoiceDetail> {
    return this.http.put<InvoiceDetail>(`/api/projects/${projectId}/invoices/${invoiceId}/tax`, { taxPct });
  }

  /** Sets the levy (second tax) percentage added to a draft invoice on top of its sales tax; zero removes the levy. */
  applyLevy(projectId: number, invoiceId: number, levyPct: number): Observable<InvoiceDetail> {
    return this.http.put<InvoiceDetail>(`/api/projects/${projectId}/invoices/${invoiceId}/levy`, { levyPct });
  }

  send(projectId: number, invoiceId: number): Observable<ProjectInvoice> {
    return this.http.post<ProjectInvoice>(`/api/projects/${projectId}/invoices/${invoiceId}/send`, null);
  }

  /** Cancels a sent invoice so it is no longer owed; it then reads VOID. */
  cancel(projectId: number, invoiceId: number): Observable<ProjectInvoice> {
    return this.http.post<ProjectInvoice>(`/api/projects/${projectId}/invoices/${invoiceId}/cancel`, null);
  }
}
