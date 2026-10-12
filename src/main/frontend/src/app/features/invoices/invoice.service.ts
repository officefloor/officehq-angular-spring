import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, ReplaySubject, of, switchMap, tap } from 'rxjs';
import { Currency } from './invoice-format';

// A sent invoice is PART_PAID while some of it has been paid and some is still due, and PAID once
// nothing is due, or PAID_LATE when the payment that cleared it (payments in date order) came after
// its due date; it is OVERDUE while something is still due more than its client's grace period
// (graceDays, a week unless set otherwise) after its due date, part paid or not.
// The server works this out from its payments and today's date. A VOID invoice was voided while sent
// and unpaid: it no longer counts towards the balance, takes no payments and cannot be changed.
// A credit note is a DRAFT until it is issued, then CREDIT_NOTE for good; a sent invoice whose issued
// credit notes leave nothing due, with nothing paid, is CREDITED. A sent invoice with nothing due
// but retention still held back is RETENTION_HELD instead of PAID. A sent invoice written off as a bad
// debt is WRITTEN_OFF for good: it owes nothing, earns no more interest and takes no payments.
// A sent invoice in dispute is DISPUTED until the dispute is resolved: it earns no interest, is not
// overdue and takes no payments.
export type InvoiceStatus =
  | 'DRAFT'
  | 'SENT'
  | 'PART_PAID'
  | 'PAID'
  | 'PAID_LATE'
  | 'OVERDUE'
  | 'VOID'
  | 'CREDIT_NOTE'
  | 'CREDITED'
  | 'RETENTION_HELD'
  | 'WRITTEN_OFF'
  | 'DISPUTED';

// An invoice as listed for its client. `total` is a decimal string to the cent, worked out by the
// server: the grand total — the lines' subtotal less any discount plus tax.
export interface InvoiceSummary {
  id: number;
  // INV-, the id padded to six digits, then a Luhn check digit, e.g. INV-0000075; CN- for a credit note.
  number: string;
  status: InvoiceStatus;
  // Negative for a credit note.
  total: string;
  issueDate: string | null;
  dueDate: string | null;
  // The id of the invoice a credit note credits; null for an ordinary invoice.
  creditOf: number | null;
}

// One line of an opened invoice. `qty` is as entered, without trailing zeros ("1.5"); money is a
// decimal string to the cent. `discountShare` is the line's share of the invoice discount, split in
// cents in proportion to the line amounts by the server; the shares add up to the discount. `tax` is
// the tax rate on the amount less that share, rounded half up to the cent; a `taxExempt` line
// (postage, say) has none. `taxRate` is the line's own tax rate (without trailing zeros), null when
// it uses the workspace rate. `discountPct` is the line's own
// discount (without trailing zeros), `discount` what it takes off quantity x unit price, and
// `amount` is after it. `unitType` is the unit the line is billed in (hour, day, ...), null when none.
export interface InvoiceLineView {
  id: number;
  // Whether the line is in the invoice's open dispute.
  disputed: boolean;
  description: string;
  qty: string;
  unitType: string | null;
  unitPrice: string;
  discountPct: string;
  discount: string;
  amount: string;
  discountShare: string;
  tax: string;
  // The line's amount before tax: its amount less its discount share, less the tax inside it when the
  // client's prices include tax.
  net: string;
  taxExempt: boolean;
  taxRate: string | null;
  // The id of the invoice line a credit note's line credits; null when none.
  creditOfLine: number | null;
  // Whether the line is priced in progressive tiers from the client's rate card; its `unitPrice` is
  // then the first band's, and a change of quantity reprices it from the bands.
  tiered: boolean;
  // The quantity the line is billed and priced on, without trailing zeros: `qty` rounded up to the
  // next whole increment its unit type has on the client's rate card (`qty` itself without one), and
  // then at least the minimum units it has there.
  billedQty: string;
  // Whether the line is work out of hours, charged at time and a half: its `unitPrice` (and, when
  // tiered, each band's price) is 1.5 times `basePrice`, the price it was given, rounded half up to the cent.
  overtime: boolean;
  basePrice: string;
  // The service period the line is billed for (yyyy-mm-dd, both ends included), null for none: its
  // quantity x unit price is then a month's price, charged for the period's days month by month.
  periodStart: string | null;
  periodEnd: string | null;
}

// A payment made to an opened invoice: the whole amount paid and `allocated`, how much of it went to
// this invoice, decimal strings to the cent; the date it was paid (yyyy-mm-dd) and the RF reference it
// quoted in groups of four (null when none).
// A payment to an opened invoice: `amount` is in the `currency` it was paid in, `allocated` (the
// part applied to this invoice) in the client's currency. `surcharge` is the part of the amount that
// was the card surcharge, in the client's currency ("0.00" unless paid by card), and `fxFee` the part
// that was the conversion fee ("0.00" unless paid in another currency). `remittance` is the client's
// remittance text (null when none); `matchedInvoice` is the number of the invoice whose purchase order
// it matched and `matchScore` the similarity to three decimals (both null when none).
export interface InvoicePaymentView {
  id: number;
  amount: string;
  currency: Currency;
  allocated: string;
  date: string;
  reference: string | null;
  method: PaymentMethod;
  surcharge: string;
  fxFee: string;
  remittance: string | null;
  matchedInvoice: string | null;
  matchScore: string | null;
}

// An opened invoice with its lines and totals, all worked out by the server. `discountPct` is the
// percentage taken off the subtotal, without trailing zeros ("12.5"), and `discountAmount` the fixed
// amount taken off instead ("0.00" when the discount is a percentage); `discountCap` is the most taken
// off in all, line discounts included (null when uncapped); `discount` is the amount the invoice
// discount takes off, rounded down to the cent (a percentage) and shrunk so the total taken off stays within the cap; `taxRate` is the workspace's tax percentage (without
// trailing zeros, "0" when none is set); `discountReason` is why the discount was given (null when none) and `tax` is the sum of the line taxes; `total` is the subtotal less the discount plus the tax.
// `payments` are the payments made to it, oldest first; `paid` is what they add up to and
// `amountDue` is the total less that and less `writtenOff`: when payments leave less than $0.05 due (and no more than 1% of the grand total),
// the rest is written off ("0.00" when nothing is). `reference` is its ISO 11649 RF payment reference, made from
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
  discountReason: string | null;
  taxRate: string;
  tax: string;
  total: string;
  // The amount before tax: the subtotal less the discount, less the tax inside it when the client's
  // prices include tax (then `total` is the subtotal less the discount, and `tax` is inside it).
  net: string;
  // Whether the client is quoted prices that include tax: every taxable line is taxed at `taxRate`.
  pricesIncludeTax: boolean;
  payments: InvoicePaymentView[];
  paid: string;
  amountDue: string;
  writtenOff: string;
  // The date (yyyy-mm-dd) of the payment that cleared it, payments in date order; null while not fully paid.
  paidDate: string | null;
  // Whether the due date was set by hand; it is then kept when the invoice is sent.
  manualDueDate: boolean;
  // What issued credit notes take off the amount due.
  credited: string;
  // The id of the invoice a credit note credits; null for an ordinary invoice.
  creditOf: number | null;
  // The total as listed: negative for a credit note.
  listedTotal: string;
  // The percentage of the amount before tax held back (without trailing zeros), and `retention`, that share
  // rounded down to the cent and never more than `retentionCap` (null when uncapped); `retentionHeld` is
  // what is still held back, not part of `amountDue`.
  retentionPct: string;
  retentionCap: string | null;
  retention: string;
  retentionHeld: string;
  // The day (yyyy-mm-dd) of the latest release of retention, and the day what was released falls due;
  // null while none has been released.
  retentionReleasedOn: string | null;
  retentionDueDate: string | null;
  // How many monthly instalments it is spread over (null without a plan), at the annual
  // `instalmentRate` (without trailing zeros); `schedule` lists them and `planTotal` is what they add
  // up to (null without a plan). The amount due on a planned invoice is the plan total less what is paid.
  instalments: number | null;
  instalmentRate: string;
  // The plan's down payment, a percentage of the grand total without trailing zeros ("0" when none);
  // when there is one it is instalment 0 of the schedule, due on the issue date with no interest.
  planDownPct: string;
  // The plan fee charged when the plan was set up ("0.00" when none), added to what is spread over
  // the instalments after any down payment, so it earns their interest and counts in the plan total.
  planFee: string;
  schedule: InstalmentView[];
  planTotal: string | null;
  // The due date as listed (yyyy-mm-dd): for a plan, its next unpaid instalment's due date.
  listedDueDate: string | null;
  // The currency the client is billed in: every amount is in it (whole yen for a yen client).
  currency: Currency;
  // The early-payment discount it was settled with ("0.00" when none was taken), and what would
  // settle it today while its early-payment window is open (null otherwise).
  earlyDiscount: string;
  settleAmount: string | null;
  // The late interest charged on it so far ("0.00" when none): part of `amountDue`.
  lateInterest: string;
  // The late interest written off by waiving it ("0.00" when none was).
  lateWaived: string;
  // What would pay off its plan today (null without a plan or once it is paid off), and the interest
  // of instalments not yet due that paying it off early dropped ("0.00" when none was).
  payoff: string | null;
  interestSaved: string;
  // For a client billed in another currency, the exchange difference in US dollars of the payments
  // applied to it (negative for a loss); null for a dollar invoice.
  fxDifference: string | null;
  // The day (yyyy-mm-dd) it was written off as a bad debt, null when it was not; everything it still
  // owed then, late charges and retention included, is in `writtenOff`.
  writtenOffOn: string | null;
  // The day (yyyy-mm-dd) it was last disputed, null when never, and the day that dispute was resolved,
  // null while it is open.
  disputedOn: string | null;
  disputeResolvedOn: string | null;
  // The amount in dispute: the disputed lines after their share of the invoice discount, with their tax
  // (all that is due when the whole invoice is disputed), never more than is due; "0.00" with no open dispute.
  disputed: string;
  // Whether it is reverse charged: its client is registered (a valid tax number whose two letters are
  // their region's code) outside the workspace's home region and accounts for the tax itself, so no line is taxed.
  reverseCharge: boolean;
  // What the amount after its discounts plus the surcharge falls short of the workspace's minimum
  // charge, added to the invoice and taxed at the client's rate; "0.00" when there is none.
  minimumCharge: string;
  // The rush surcharge percentage (without trailing zeros), and `surcharge`, that share of the amount
  // after its discounts rounded down to the cent; not discounted, taxed at the client's rate.
  surchargePct: string;
  surcharge: string;
  // What the client withholds for its tax office: its withholding percentage of the amount before tax,
  // rounded down to the cent ("0.00" when none, or when reverse charged). Never owed, so left out of
  // the amount due.
  withholding: string;
  // The client's withholding percentage (without trailing zeros); "0" when it withholds nothing.
  withholdingPct: string;
  // Whether a draft must be approved before it is sent: its grand total (before any withholding) is
  // over the workspace's approval limit, its discount takes off more than a fifth of the subtotal, or its
  // client is a HIGH payment risk,
  // and it has not been approved at exactly its total within the last 30 days. Credit notes never need approval.
  approval: 'NOT_NEEDED' | 'NEEDS_APPROVAL' | 'APPROVED';
  // The grand total it was approved at; null when it was not approved.
  approvedTotal: string | null;
  // The day it was approved (yyyy-mm-dd); null when it was not. The approval lapses after 30 days.
  approvedOn: string | null;
  // The amount due rounded down to the cash coin below (0.05, or whole yen), and the difference
  // that makes (signed); once settled in cash, the cash rounding it was settled with.
  cashDue: string;
  cashRounding: string;
  // The region where the work is done (the place of supply), taxed and reverse charged as for it
  // instead of the client's region; null for the client's region.
  supplyRegion: string | null;
  // Whether the invoice repeats every period: once sent, it can generate the next period's draft.
  recurring: boolean;
  // The id of the recurring invoice this draft was generated from as its next period; null when it was not.
  recurringFrom: number | null;
  // The last day a recurring invoice bills for (yyyy-mm-dd): a generated period stops on it; null for no end.
  recurUntil: string | null;
  // The client's purchase order number for the invoice; null when none was given.
  poNumber: string | null;
}

// One instalment of a plan: its number (from 1, or 0 for a down payment), the day it falls due (yyyy-mm-dd), and its payment,
// interest, principal and the balance left after it, decimal strings to the cent; what has been paid
// off it (payments pay the instalments in order, each itself first, then its late interest), the late interest it
// has been charged ("0.00" when none) and its status.
export type InstalmentStatus = 'PAID' | 'PART_PAID' | 'OVERDUE' | 'DUE';

export interface InstalmentView {
  number: number;
  dueDate: string | null;
  payment: string;
  interest: string;
  principal: string;
  balance: string;
  paid: string;
  lateInterest: string;
  status: InstalmentStatus;
}

// A payment to record against a sent invoice: the amount as entered, the date (yyyy-mm-dd) and the
// RF reference the client quoted ('' when none), checked again by the server.
// `currency` is the currency it was paid in; the server converts it into the client's currency.
// How a payment was made: a bank transfer, or cash counted out in coins (0.05, or whole yen).
export type PaymentMethod = 'BANK' | 'CASH' | 'CARD';

export interface NewPayment {
  amount: string;
  currency: Currency;
  date: string;
  reference: string;
  method: PaymentMethod;
  // The client's remittance text ('' when none): with no invoice open and no reference, it first pays
  // the open invoice whose purchase order number is most like it.
  remittance: string;
}

// A line to add to a draft: quantity, unit type, unit price, the line's own discount percentage and
// its own tax rate as entered (each left out when none), checked again by the server. A line with a
// unit type and no unit price is priced at the client's rate for that unit type.
export interface NewLine {
  description: string;
  qty: string;
  unitType?: string;
  unitPrice?: string;
  overtime: boolean;
  taxExempt: boolean;
  discountPct?: string;
  taxRate?: string;
  periodStart?: string;
  periodEnd?: string;
}

// A discount to give: either a percentage or a fixed amount, as entered, with an optional cap on
// what a percentage takes off and an optional reason.
export type NewDiscount = ({ discountPct: string } | { discountAmount: string }) & { discountCap?: string; discountReason?: string };

// What a client still owes across its sent invoices (drafts do not count), after discounts, tax and
// payments, less the client's unused credit (so it can be below zero), how much of what is due is
// overdue, and the unused credit; decimal strings to the cent, worked out by the server.
export interface ClientBalance {
  clientId: number;
  balance: string;
  overdue: string;
  credit: string;
}

// The invoices REST API.
@Injectable({ providedIn: 'root' })
export class InvoiceService {
  private readonly http = inject(HttpClient);
  // Completes once the latest payment spread over a client's invoices has been answered, so an
  // invoice opened meanwhile is read with it.
  private paymentSettled: Observable<unknown> = of(null);

  forClient(clientId: number): Observable<InvoiceSummary[]> {
    return this.paymentSettled.pipe(
      switchMap(() => this.http.get<InvoiceSummary[]>(`/api/clients/${clientId}/invoices`)),
    );
  }

  // The balance of each client with a sent invoice; a client not listed owes nothing.
  balances(): Observable<ClientBalance[]> {
    return this.http.get<ClientBalance[]>('/api/clients/balances');
  }

  // Starts an empty draft for the client, issued today and due by the client's terms, else their region's, else on the last day of the following month.
  newDraft(clientId: number): Observable<InvoiceSummary> {
    return this.http.post<InvoiceSummary>(`/api/clients/${clientId}/invoices`, null);
  }

  open(id: number): Observable<InvoiceDetail> {
    return this.paymentSettled.pipe(switchMap(() => this.http.get<InvoiceDetail>(`/api/invoices/${id}`)));
  }

  // Opens the invoice with a typed number; refused (400) when its check digit does not match.
  openByNumber(number: string): Observable<InvoiceDetail> {
    return this.http.get<InvoiceDetail>(`/api/invoices/by-number/${encodeURIComponent(number)}`);
  }

  // Adds a line to a draft invoice; answers the invoice with its new lines and totals.
  addLine(invoiceId: number, line: NewLine): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/invoices/${invoiceId}/lines`, line);
  }

  // Changes a line of a draft, or of a sent invoice with no payments (putting it back to draft), with
  // the same checks as adding one; refused (409) when the subtotal would be less than a fixed
  // discount. Answers the invoice with its lines and totals worked out again.
  updateLine(invoiceId: number, lineId: number, line: NewLine): Observable<InvoiceDetail> {
    return this.http.put<InvoiceDetail>(`/api/invoices/${invoiceId}/lines/${lineId}`, line);
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

  // Sets a draft's due date (yyyy-mm-dd) by hand; refused (409) when it is before the issue date or
  // more than 120 days after it. Answers the invoice with its new due date.
  // Sets the client's purchase order number on an invoice ('' for none).
  setPoNumber(invoiceId: number, poNumber: string): Observable<InvoiceDetail> {
    return this.http.put<InvoiceDetail>(`/api/invoices/${invoiceId}/po-number`, { poNumber });
  }

  setSupplyRegion(invoiceId: number, supplyRegion: string): Observable<InvoiceDetail> {
    return this.http.put<InvoiceDetail>(`/api/invoices/${invoiceId}/supply-region`, { supplyRegion });
  }

  setDueDate(invoiceId: number, dueDate: string): Observable<InvoiceDetail> {
    return this.http.put<InvoiceDetail>(`/api/invoices/${invoiceId}/due-date`, { dueDate });
  }

  // Sets the percentage of a draft's amount before tax held back as retention (0 to 20, up to two decimals),
  // with an optional cap on the amount held back; refused (400) outside that, or (409) when it is not a draft.
  setRetention(invoiceId: number, retentionPct: string, retentionCap?: string): Observable<InvoiceDetail> {
    return this.http.put<InvoiceDetail>(`/api/invoices/${invoiceId}/retention`, { retentionPct, retentionCap });
  }

  // Sets the rush surcharge of a draft, a percentage of its amount after its discounts (0 to 25, up to
  // two decimals); refused (400) outside that, or (409) when it is not a draft.
  setSurcharge(invoiceId: number, surchargePct: string): Observable<InvoiceDetail> {
    return this.http.put<InvoiceDetail>(`/api/invoices/${invoiceId}/surcharge`, { surchargePct });
  }

  // Spreads a draft over monthly instalments (2 to 24) at an annual interest rate (0 to 30, up to two
  // decimals), after a down payment of a percentage of the grand total (0 to 50, up to two decimals);
  // refused (400) outside that, or (409) when it is not a draft or holds back retention.
  setPlan(invoiceId: number, instalments: string, instalmentRate: string, planDownPct: string): Observable<InvoiceDetail> {
    return this.http.put<InvoiceDetail>(`/api/invoices/${invoiceId}/plan`, { instalments, instalmentRate, planDownPct });
  }

  // Releases an amount of the retention held back on a sent invoice (all still held when left out),
  // adding it to the amount due; refused (400) for an amount of zero or less or with more than two
  // decimals, or (409) more than is held, while anything else is due or when there is nothing to release.
  releaseRetention(invoiceId: number, amount?: string): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/invoices/${invoiceId}/release-retention`, { amount });
  }

  // Approves a draft whose grand total is over the workspace's approval limit, or whose discount is
  // more than a fifth of the subtotal. Refused (409) when it
  // needs no approval.
  approve(invoiceId: number): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/invoices/${invoiceId}/approve`, null);
  }

  // Writes off everything still owed on a sent invoice as a bad debt. Refused (409) on a draft, a void
  // invoice or one with nothing owed.
  writeOff(invoiceId: number): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/invoices/${invoiceId}/write-off`, null);
  }

  // Puts a sent invoice that still has something due in dispute. Refused (409) on a draft, an invoice
  // with nothing due or one already in dispute.
  dispute(invoiceId: number): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/invoices/${invoiceId}/dispute`, null);
  }

  // Puts one line of a sent invoice in dispute: from the dispute date it earns no interest and is not
  // overdue, while the rest of the invoice is owed as usual. Refused (409) as `dispute` is, and for a line
  // already in dispute.
  disputeLine(invoiceId: number, lineId: number): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/invoices/${invoiceId}/lines/${lineId}/dispute`, null);
  }

  // Ends the dispute on an invoice; interest runs again from today on what is still owed.
  resolveDispute(invoiceId: number): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/invoices/${invoiceId}/resolve-dispute`, null);
  }

  // Waives the late interest still unpaid on an overdue invoice; interest starts again
  // from today on what is still owed. Refused (409) when there is nothing to waive.
  waiveInterest(invoiceId: number): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/invoices/${invoiceId}/waive-interest`, null);
  }

  // Skips an instalment of a sent plan: it pays only its interest and the instalments after it are
  // worked out again. Refused (409) for an overdue, paid or last instalment.
  skipInstalment(invoiceId: number, instalment: number): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/invoices/${invoiceId}/instalments/${instalment}/skip`, null);
  }

  // Sends a draft invoice, issuing it today and due on the client's terms, or keeping a due date set
  // by hand; refused (409) when it has no lines or a due date set by hand is before today.
  send(invoiceId: number): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/invoices/${invoiceId}/send`, null);
  }

  // Sends every draft of the client that has at least one line, lowest id first; drafts with no
  // lines stay drafts. Answers the client's invoices with their new statuses.
  sendAllDrafts(clientId: number): Observable<InvoiceSummary[]> {
    return this.http.post<InvoiceSummary[]>(`/api/clients/${clientId}/invoices/send-drafts`, null);
  }

  // Applies the client's unused credit now to what they owe: the oldest payment's credit first, to their
  // sent invoices in the payment order, never a deposit held for a draft; refused (409) when there is no
  // credit to use or nothing owed. Answers the client's invoices with their new statuses.
  applyCredit(clientId: number): Observable<InvoiceSummary[]> {
    return this.http.post<InvoiceSummary[]>(`/api/clients/${clientId}/apply-credit`, null);
  }

  // Copies an invoice into a new draft for the same client: its lines and invoice discount, issued
  // today and due as any new invoice; payments are not copied. Answers the new draft.
  duplicate(invoiceId: number): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/invoices/${invoiceId}/duplicate`, null);
  }

  // Marks an invoice as recurring, or not; answers the invoice.
  setRecurring(invoiceId: number, recurring: boolean): Observable<InvoiceDetail> {
    return this.http.put<InvoiceDetail>(`/api/invoices/${invoiceId}/recurring`, { recurring });
  }

  // Sets the last day a recurring invoice bills for (null for no end); answers the invoice.
  setRecurUntil(invoiceId: number, recurUntil: string | null): Observable<InvoiceDetail> {
    return this.http.put<InvoiceDetail>(`/api/invoices/${invoiceId}/recur-until`, { recurUntil });
  }

  // Generates the next period's draft of a sent recurring invoice: its lines with a service period moved
  // to the calendar month after the latest period end, with its percentage discount and surcharge,
  // issued today and due as any new invoice. Answers the new draft.
  generate(invoiceId: number): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/invoices/${invoiceId}/generate`, null);
  }

  // Starts an empty draft credit note against a sent invoice, numbered CN-; answers the new draft.
  creditNote(invoiceId: number): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/invoices/${invoiceId}/credit-note`, null);
  }

  // Credits a line of a sent invoice: adds a copy of it to the invoice's draft credit note, starting
  // one when there is none; answers that draft.
  creditLine(invoiceId: number, lineId: number): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/invoices/${invoiceId}/lines/${lineId}/credit`, null);
  }

  // Voids a sent invoice with no payments; refused (409) for a draft, a void invoice or one with
  // payments. Answers the invoice with its new status.
  void(invoiceId: number): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/invoices/${invoiceId}/void`, null);
  }

  // Records a payment against a sent invoice that still has something due; refused (409) when it
  // is a draft or already paid (anything paid beyond what is due is held as credit), or the date is after today or before
  // the issue date, or the reference is mistyped (400) or another invoice's (409). Answers the invoice with its payments and new status.
  recordPayment(invoiceId: number, payment: NewPayment): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/invoices/${invoiceId}/payments`, payment);
  }

  // Records one payment spread automatically over the client's invoices: those whose references are
  // quoted (comma separated), or with none all its unpaid invoices, overdue first, then the largest due
  // first; refused when a reference is mistyped (400) or not the client's; what they do not owe is held as credit.
  // Answers the client's invoices with their new statuses.
  recordSplitPayment(clientId: number, payment: NewPayment): Observable<InvoiceSummary[]> {
    const settled = new ReplaySubject<null>(1);
    this.paymentSettled = settled;
    const done = () => {
      settled.next(null);
      settled.complete();
    };
    return this.http
      .post<InvoiceSummary[]>(`/api/clients/${clientId}/payments`, payment)
      .pipe(tap({ next: done, error: done, unsubscribe: done }));
  }

  // Records a deposit: the whole amount held on the client's account as credit, applied to no invoice
  // until one of the client's invoices is sent; refused (409) when dated after today. With a reference
  // it is held for that one of the client's drafts, refused (409) when it is not one. Answers the
  // client's invoices.
  recordDeposit(
    clientId: number,
    deposit: {
      amount: string;
      currency: Currency;
      date: string;
      reference?: string;
      method?: PaymentMethod;
    },
  ): Observable<InvoiceSummary[]> {
    return this.http.post<InvoiceSummary[]>(`/api/clients/${clientId}/deposits`, deposit);
  }

  // Refunds the client's unused credit, the oldest payment's first; refused (409) when dated after
  // today or before a payment it would come from, or more than the client's credit. Answers the
  // client's invoices. The amount is in the refund's currency, converted by the server into the client's.
  refundCredit(clientId: number, refund: { amount: string; currency: Currency; date: string }): Observable<InvoiceSummary[]> {
    return this.http.post<InvoiceSummary[]>(`/api/clients/${clientId}/refunds`, refund);
  }

  // Adds a rate to the client's rate card, or replaces the price for its unit type; the client's drafts priced
  // from the card are repriced. Answers the client's invoices.
  setRate(clientId: number, rate: { unitType: string; unitPrice: string }): Observable<InvoiceSummary[]> {
    return this.http.put<InvoiceSummary[]>(`/api/clients/${clientId}/rates`, rate);
  }

  // Reverses a payment (a bounced cheque) on every invoice it was applied to; refused (409) when it
  // has already been reversed or has had credit refunded. Answers the opened invoice with its payments and new status.
  reversePayment(invoiceId: number, paymentId: number): Observable<InvoiceDetail> {
    return this.http.post<InvoiceDetail>(`/api/invoices/${invoiceId}/payments/${paymentId}/reverse`, null);
  }
}
