import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CurrencyCode } from '../clients/client.service';

export const INVOICE_STATUSES = ['DRAFT', 'SENT', 'PARTIAL', 'PAID', 'VOID', 'WRITTEN_OFF'] as const;

export type InvoiceStatus = (typeof INVOICE_STATUSES)[number];

export interface Invoice {
  id: number;
  projectId: number;
  /** The currency the invoice is billed in (its own, or else its client's); its money is in it. */
  currency: CurrencyCode;
  amount: number;
  status: InvoiceStatus;
  /** ISO date (yyyy-MM-dd) the invoice was issued. */
  issuedDate: string;
  /** ISO date (yyyy-MM-dd) payment is due. */
  dueDate: string;
}

/** An invoice as listed for its project, with what the client owes on it right now and the retention held back. */
export interface ProjectInvoice extends Invoice {
  /** What the client owes right now: what is left to pay after any payments, less the retention held back. */
  amountDue: number;
  /** The amount held back as retention, not due yet; shown separately from what is due now. */
  retention: number;
  /** Whether a credit note has been put against the invoice. */
  creditApplied: boolean;
  /** How an owing invoice on an instalment plan is keeping to its schedule; null when it has none. */
  schedule: ScheduleStatus | null;
  /** Whether the client disputes the invoice; it still counts as owed, it is only flagged. */
  disputed: boolean;
}

/** ON_TRACK while every instalment fallen due is paid, BEHIND once an unpaid one is overdue. */
export type ScheduleStatus = 'ON_TRACK' | 'BEHIND';

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
export interface StatementInvoice extends Omit<ProjectInvoice, 'retention'> {
  projectName: string;
  /** The sales tax and levy on the invoice. */
  tax: number;
}

/** One job on a client's statement: its invoices and what is still owed on them. */
export interface StatementJob {
  projectId: number;
  projectName: string;
  invoices: StatementInvoice[];
  subtotal: number;
  /** The tax on the job's sent invoices (drafts and void ones are left out). */
  tax: number;
}

/** What an entry on a client's running account records. */
export type StatementEntryKind = 'INVOICE' | 'PAYMENT' | 'CREDIT_NOTE' | 'DEPOSIT' | 'REFUND';

/**
 * One dated entry on a client's running account: an invoice or refund is a charge, a payment, credit note or deposit
 * is a credit. The balance is what the client owes once this entry and those before it are counted.
 */
export interface StatementEntry {
  kind: StatementEntryKind;
  sourceId: number;
  /** The invoice the entry is for; null for deposits and refunds. */
  invoiceId: number | null;
  date: string;
  description: string;
  charge: number | null;
  credit: number | null;
  balance: number;
  /** The purchase-order number of the invoice the entry is for; null when it has none. */
  poNumber: string | null;
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
  /** The tax across the client's sent invoices (drafts and void ones are left out). */
  tax: number;
  /** Invoices, payments, credit notes, deposits and refunds in date order, each with the running balance. */
  entries: StatementEntry[];
  /** The business's home currency. */
  homeCurrency: CurrencyCode;
  /** The total still owed converted into the home currency, each invoice at its issue-date rate; null without a rate. */
  homeOutstanding: number | null;
}

/** What a client owed as at the end of a chosen day (negative when in credit). */
export interface ClientBalanceAsOf {
  clientId: number;
  currency: CurrencyCode;
  asOf: string;
  balance: number;
}

/** A note that a client's statement was emailed: the address it went to and when. */
export interface StatementEmail {
  id: number;
  clientId: number;
  email: string;
  sentAt: string;
}

/** A client's statement for a date range: the balance owed at its start, the entries within it, and the balance owed at its end. */
export interface ClientStatementRange {
  clientId: number;
  currency: CurrencyCode;
  from: string;
  to: string;
  openingBalance: number;
  entries: StatementEntry[];
  /** The charges less the credits dated within the range; opening plus movements is the closing balance. */
  movementsTotal: number;
  closingBalance: number;
  /** The payments received within the range, in date order. */
  payments: StatementEntry[];
  /** The total of the payments received within the range. */
  paymentsTotal: number;
  /** The credit notes issued within the range, in date order. */
  credits: StatementEntry[];
  /** The total of the credit notes issued within the range. */
  creditsTotal: number;
  /** What was invoiced within the range. */
  invoicedTotal: number;
  /** What was paid within the range. */
  paidTotal: number;
  /** The debt broken down by age as at the end of the range. */
  aging: ClientAging;
}

/** How old a client's debt is as at a day (today, unless for a date range): what is current (up to 30 days overdue), 31 to 60 days, and more than 60 days overdue. */
export interface ClientAging {
  clientId: number;
  currency: CurrencyCode;
  asOf: string;
  current: number;
  days30To60: number;
  days60Plus: number;
}

/** One thing an invoice charges for; its amount is quantity times unit price, less the line's own discount. */
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
  /** The percentage the line takes off itself; zero when it has no discount of its own. */
  discountPct: number;
  /** Quantity times unit price, before the line's own discount. */
  grossAmount: number;
}

/** One discount on an invoice: a percentage or a flat amount (the other zero), and what it takes off. */
export interface InvoiceDiscount {
  id: number;
  discountPct: number;
  discountAmount: number;
  /** The most a percentage discount takes off; null when it is not capped. */
  discountCap: number | null;
  /** What this discount actually takes off the subtotal. */
  amount: number;
}

/**
 * A single invoice with the line items it is built from: their subtotal, each of its discounts (a
 * percentage or flat amount) and what they take off together, and the sales tax percentage, the taxable base it is charged on (the taxable
 * lines after the discount, leaving out tax-free ones) and what it adds, and the levy (a second tax)
 * percentage and what it adds on that same base, and any flat surcharge (such as a handling fee) added
 * after tax; its amount is the subtotal less the discount plus the tax plus the levy plus the surcharge.
 */
export interface InvoiceDetail extends Invoice {
  /** The total before tax: the amount less the sales tax and levy. */
  totalExTax: number;
  subtotal: number;
  /** The percentage discounts added up; zero when there are none. */
  discountPct: number;
  /** The flat amount discounts added up (taken off after the percentages); zero when there are none. */
  discountAmount: number;
  /** What all the discounts take off together, before tax. */
  discount: number;
  /** Each discount on the invoice, in the order it was added. */
  discounts: InvoiceDiscount[];
  taxPct: number;
  taxableBase: number;
  tax: number;
  levyPct: number;
  levy: number;
  /** A flat amount (such as a handling fee) added to the total after tax; zero when there is none. */
  surcharge: number;
  /** Whether the prices already include the tax and levy, so they are worked back out rather than added on. */
  taxInclusive: boolean;
  /** Whether the client is tax exempt, so the invoice carries no tax or levy whatever its lines say. */
  taxExempt: boolean;
  /** The overall tax rate that ended up on the invoice: the tax and levy as a percentage of the total before tax. */
  effectiveTaxPct: number;
  /** The percentage taken off when paid early; zero when no early-payment discount is offered. */
  earlyPaymentPct: number;
  /** How many days after being issued it must be paid within to get the early-payment discount: the client's early-payment window; zero when they have none. */
  earlyPaymentDays: number;
  /** The last day to pay to get the early-payment discount; null when none is offered. */
  earlyPaymentBy: string | null;
  /** The reduced amount to pay when settling early; null when no early-payment discount is offered. */
  earlyPaymentAmount: number | null;
  /** The percentage of the amount given back when paid before the due date; zero when no rebate is offered. */
  rebatePct: number;
  /** The settlement rebate for paying before the due date; null when none is offered. */
  rebate: number | null;
  /** The reduced amount the client actually needed to pay once the rebate was earned; null until then. */
  rebatedAmount: number | null;
  lineItems: LineItem[];
  /** The client's tax registration number; null when they are not tax registered. */
  clientTaxNumber: string | null;
  /** The least the invoice bills; zero when there is no minimum charge. */
  minimumCharge: number;
  /** What the invoice comes to before any minimum charge. */
  netTotal: number;
  /** Whether the net total came out under the minimum charge, so the minimum is billed instead. */
  minimumApplied: boolean;
  /** How much the client saves: the line discounts and the invoice's discounts added together. */
  totalSavings: number;
  /** The currency the business keeps its own totals in. */
  homeCurrency: string;
  /**
   * A foreign invoice's amount converted into the home currency at the exchange rate from its issue date;
   * null when it is already in the home currency or there is no rate for that date.
   */
  homeAmount: number | null;
  /**
   * The exchange gain (positive) or loss (negative) in the home currency realised by the payments on a foreign
   * invoice, from the rate moving between its issue date and each payment's date; null when there is none.
   */
  fxGainLoss: number | null;
  /** The late fee charged for each day the invoice is overdue once sent; zero when none is charged. */
  lateFeePerDay: number;
  /** How many days past due the invoice is today; zero unless it is sent and still owed. */
  daysLate: number;
  /** The late fee accrued so far: the fee per day times the days overdue. */
  lateFee: number;
  /** The percentage of the amount held back as retention; zero when none is held back. */
  retentionPct: number;
  /** The amount held back as retention, which is not due yet; nothing once released. */
  retention: number;
  /** Whether the retention has been released once the job is finished, making it due. */
  retentionReleased: boolean;
  /** What is due now: the amount less the retention held back. */
  dueNow: number;
  /** The client's purchase-order number the invoice is raised against; null when none was given. */
  poNumber: string | null;
  /** The part of the invoice written off as bad debt, no longer owed; zero when none has been written off. */
  writeOffAmount: number;
  /** What the client owes right now: what is left to pay after any payments and part written off, less the retention held back. */
  amountDue: number;
}

export interface NewLineItem {
  description: string;
  qty: number;
  unit: string | null;
  unitPrice: number;
  taxExempt: boolean;
  discountPct: number;
}

export interface NewInvoice {
  /** Raises the invoice as one figure; omit to start an empty draft built up from line items. */
  amount?: number;
  /** Defaults to today on the server when omitted. */
  issuedDate?: string;
  /** Defaults to the client's payment terms (30 days when none) after the issue date on the server when omitted. */
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

  balanceAsOf(clientId: number, asOf: string): Observable<ClientBalanceAsOf> {
    return this.http.get<ClientBalanceAsOf>(`/api/clients/${clientId}/statement/balance`, { params: { asOf } });
  }

  statementForRange(clientId: number, from: string, to: string): Observable<ClientStatementRange> {
    return this.http.get<ClientStatementRange>(`/api/clients/${clientId}/statement/range`, { params: { from, to } });
  }

  statementEmails(clientId: number): Observable<StatementEmail[]> {
    return this.http.get<StatementEmail[]>(`/api/clients/${clientId}/statement/emails`);
  }

  emailStatement(clientId: number): Observable<StatementEmail> {
    return this.http.post<StatementEmail>(`/api/clients/${clientId}/statement/emails`, {});
  }

  agingForClient(clientId: number): Observable<ClientAging> {
    return this.http.get<ClientAging>(`/api/clients/${clientId}/statement/aging`);
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

  /**
   * Sets the percentage (optionally capped at the most it takes off) or the flat amount taken off a draft
   * invoice (the other being zero); zero for both removes the discount.
   */
  applyDiscount(projectId: number, invoiceId: number, discountPct: number, discountAmount = 0, discountCap: number | null = null): Observable<InvoiceDetail> {
    return this.http.put<InvoiceDetail>(`/api/projects/${projectId}/invoices/${invoiceId}/discount`, {
      discountPct,
      discountAmount,
      discountCap,
    });
  }

  /** Adds another percentage or flat amount discount to a draft invoice (the other being zero). */
  addDiscount(projectId: number, invoiceId: number, discountPct: number, discountAmount = 0, discountCap: number | null = null): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/projects/${projectId}/invoices/${invoiceId}/discounts`, {
      discountPct,
      discountAmount,
      discountCap,
    });
  }

  /** Removes one discount from a draft invoice. */
  removeDiscount(projectId: number, invoiceId: number, discountId: number): Observable<InvoiceDetail> {
    return this.http.delete<InvoiceDetail>(`/api/projects/${projectId}/invoices/${invoiceId}/discounts/${discountId}`);
  }

  /** Sets the sales tax percentage added to a draft invoice after its discount; zero removes the tax. */
  applyTax(projectId: number, invoiceId: number, taxPct: number): Observable<InvoiceDetail> {
    return this.http.put<InvoiceDetail>(`/api/projects/${projectId}/invoices/${invoiceId}/tax`, { taxPct });
  }

  /** Sets the levy (second tax) percentage added to a draft invoice on top of its sales tax; zero removes the levy. */
  applyLevy(projectId: number, invoiceId: number, levyPct: number): Observable<InvoiceDetail> {
    return this.http.put<InvoiceDetail>(`/api/projects/${projectId}/invoices/${invoiceId}/levy`, { levyPct });
  }

  /** Sets the flat surcharge (such as a handling fee) added to a draft invoice; zero removes it. */
  applySurcharge(projectId: number, invoiceId: number, surcharge: number): Observable<InvoiceDetail> {
    return this.http.put<InvoiceDetail>(`/api/projects/${projectId}/invoices/${invoiceId}/surcharge`, { surcharge });
  }

  /** Sets the minimum charge billed on a draft invoice when its net total comes out under it; zero removes it. */
  applyMinimumCharge(projectId: number, invoiceId: number, minimumCharge: number): Observable<InvoiceDetail> {
    return this.http.put<InvoiceDetail>(`/api/projects/${projectId}/invoices/${invoiceId}/minimum-charge`, { minimumCharge });
  }

  /** Sets the early-payment discount offered on a draft invoice, earned within the client's early-payment window; zero removes the offer. */
  applyEarlyPayment(projectId: number, invoiceId: number, earlyPaymentPct: number): Observable<InvoiceDetail> {
    return this.http.put<InvoiceDetail>(`/api/projects/${projectId}/invoices/${invoiceId}/early-payment`, {
      earlyPaymentPct,
    });
  }

  /** Sets the settlement rebate offered on a draft invoice for paying before the due date; zero removes it. */
  applyRebate(projectId: number, invoiceId: number, rebatePct: number): Observable<InvoiceDetail> {
    return this.http.put<InvoiceDetail>(`/api/projects/${projectId}/invoices/${invoiceId}/rebate`, { rebatePct });
  }

  /** Sets the late fee charged for each day a draft invoice is overdue once sent; zero charges none. */
  applyLateFee(projectId: number, invoiceId: number, lateFeePerDay: number): Observable<InvoiceDetail> {
    return this.http.put<InvoiceDetail>(`/api/projects/${projectId}/invoices/${invoiceId}/late-fee`, { lateFeePerDay });
  }

  /** Sets the percentage of a draft invoice held back as retention, not due yet; zero holds none back. */
  applyRetention(projectId: number, invoiceId: number, retentionPct: number): Observable<InvoiceDetail> {
    return this.http.put<InvoiceDetail>(`/api/projects/${projectId}/invoices/${invoiceId}/retention`, { retentionPct });
  }

  /** Releases the retention held back on a sent invoice once the job is finished, so it becomes due. */
  setPoNumber(projectId: number, invoiceId: number, poNumber: string | null): Observable<InvoiceDetail> {
    return this.http.put<InvoiceDetail>(`/api/projects/${projectId}/invoices/${invoiceId}/po-number`, { poNumber });
  }

  releaseRetention(projectId: number, invoiceId: number): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/projects/${projectId}/invoices/${invoiceId}/retention/release`, null);
  }

  /** Sends every draft invoice on a job in one go; returns the invoices that were sent. */
  sendDrafts(projectId: number): Observable<ProjectInvoice[]> {
    return this.http.post<ProjectInvoice[]>(`/api/projects/${projectId}/invoices/send-drafts`, null);
  }

  send(projectId: number, invoiceId: number): Observable<ProjectInvoice> {
    return this.http.post<ProjectInvoice>(`/api/projects/${projectId}/invoices/${invoiceId}/send`, null);
  }

  /** Writes off a sent or part-paid invoice as bad debt so it is no longer owed; it then reads WRITTEN_OFF. */
  writeOff(projectId: number, invoiceId: number): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/projects/${projectId}/invoices/${invoiceId}/write-off`, null);
  }

  /** Writes off part of a sent or part-paid invoice as bad debt; the rest is still owed. */
  writeOffPart(projectId: number, invoiceId: number, amount: number): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/projects/${projectId}/invoices/${invoiceId}/write-off-part`, { amount });
  }

  /** Cancels a sent invoice so it is no longer owed; it then reads VOID. */
  /** Flags a sent or part-paid invoice as disputed by the client; it still counts as owed. */
  dispute(projectId: number, invoiceId: number): Observable<ProjectInvoice> {
    return this.http.post<ProjectInvoice>(`/api/projects/${projectId}/invoices/${invoiceId}/dispute`, null);
  }

  cancel(projectId: number, invoiceId: number): Observable<ProjectInvoice> {
    return this.http.post<ProjectInvoice>(`/api/projects/${projectId}/invoices/${invoiceId}/cancel`, null);
  }
}
