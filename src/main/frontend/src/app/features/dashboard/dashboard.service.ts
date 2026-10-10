import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CurrencyCode } from '../clients/client.service';

export interface TopClient {
  id: number;
  name: string;
  /** The client's currency; what they owe is in it. */
  currency: CurrencyCode;
  outstanding: number;
  /** What they owe converted into the home currency; the clients are ranked by it. */
  outstandingHome: number;
}

/** What is still owed in one currency; different currencies are never added together. */
export interface CurrencyTotal {
  currency: CurrencyCode;
  amount: number;
}

/** What is overdue, in the home currency, by how many days past due. */
export interface OverdueBuckets {
  days0To30: number;
  days31To60: number;
  days60Plus: number;
}

export interface DashboardSummary {
  clients: number;
  projects: number;
  /** What is still owed, one total per currency anything is owed in. */
  outstanding: CurrencyTotal[];
  /** Everything outstanding as one total in the home currency, each invoice converted at its issue date's rate. */
  outstandingHome: number;
  overdue: number;
  /** The currency the overdue amount is given in. */
  homeCurrency: CurrencyCode;
  /** What is left to pay on overdue invoices plus the late fees and instalment interest they have built up, in the home currency. */
  overdueAmount: number;
  /** The overdue amount split by how many days past due each invoice is. */
  overdueBuckets: OverdueBuckets;
  topClients: TopClient[];
  /** How many tasks not yet done are past their due date. */
  overdueTasks: number;
}

/** The tax charged on invoices issued on or between two dates, in the home currency. */
export interface TaxSummary {
  from: string;
  to: string;
  homeCurrency: CurrencyCode;
  /** How many invoices the tax came from. */
  invoices: number;
  tax: number;
  /** The levy (second tax). */
  levy: number;
  /** The tax and levy together. */
  total: number;
}

/** The sales tax charged at one rate, in the home currency. */
export interface RateTax {
  /** The tax rate as a percentage. */
  rate: number;
  /** How many invoices were taxed at the rate. */
  invoices: number;
  /** What the tax at the rate was charged on. */
  taxableBase: number;
  tax: number;
}

/** The sales tax charged on invoices issued on or between two dates, in the home currency, broken down by rate. */
export interface TaxReport {
  from: string;
  to: string;
  homeCurrency: CurrencyCode;
  /** How many invoices the tax came from. */
  invoices: number;
  taxableBase: number;
  tax: number;
  /** The lowest rate first; only the rates something was taxed at. */
  rates: RateTax[];
}

/** The revenue billed for one job (project), in the home currency. */
export interface JobRevenue {
  projectId: number;
  projectName: string;
  clientName: string;
  /** How many invoices the job's revenue came from. */
  invoices: number;
  amount: number;
}

/** The revenue billed on invoices issued in one calendar month, in the home currency. */
export interface MonthRevenue {
  /** The month as yyyy-MM. */
  month: string;
  /** How many invoices the month's revenue came from. */
  invoices: number;
  amount: number;
}

/**
 * The revenue billed on invoices issued on or between two dates (over all time when the dates are null), in the
 * home currency, broken down by job with the highest-earning job first, and by month with the earliest first.
 */
export interface RevenueReport {
  from: string | null;
  to: string | null;
  homeCurrency: CurrencyCode;
  /** How many invoices the revenue came from. */
  invoices: number;
  total: number;
  jobs: JobRevenue[];
  /** Only the months something was billed in. */
  months: MonthRevenue[];
}

/** One instalment expected in, in its invoice's currency. */
export interface ForecastEntry {
  id: number;
  date: string;
  amount: number;
  currency: CurrencyCode;
  invoiceId: number;
  projectId: number;
  projectName: string;
  clientName: string;
}

/** The money expected in from instalments still to be paid, earliest due first, with the total in the home currency. */
export interface Forecast {
  homeCurrency: CurrencyCode;
  entries: ForecastEntry[];
  total: number;
}

/**
 * How old the debt across all clients is as at a date, in the home currency: what is left to pay on sent invoices
 * split into current (not yet due, or up to 30 days overdue), 31 to 60 days and more than 60 days overdue.
 */
export interface AgingReport {
  asOf: string;
  homeCurrency: CurrencyCode;
  current: number;
  days30To60: number;
  days60Plus: number;
  total: number;
  invoices: AgingReportLine[];
}

/** The age bucket an invoice falls in on the aging report. */
export type AgingBucket = 'CURRENT' | 'DAYS_30_60' | 'DAYS_60_PLUS';

/** One invoice behind the aging report: what is left to pay on it in the home currency, and its bucket. */
export interface AgingReportLine {
  invoiceId: number;
  projectId: number;
  clientId: number;
  clientName: string;
  dueDate: string | null;
  daysOverdue: number;
  bucket: AgingBucket;
  amount: number;
}

@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly http = inject(HttpClient);

  summary(): Observable<DashboardSummary> {
    return this.http.get<DashboardSummary>('/api/dashboard');
  }

  taxSummary(from: string, to: string): Observable<TaxSummary> {
    return this.http.get<TaxSummary>('/api/dashboard/tax-summary', { params: new HttpParams().set('from', from).set('to', to) });
  }

  taxReport(from: string, to: string): Observable<TaxReport> {
    return this.http.get<TaxReport>('/api/dashboard/tax-report', { params: new HttpParams().set('from', from).set('to', to) });
  }

  /** The revenue report over the given range, or over all time when no range is given. */
  revenueReport(range?: { from: string; to: string }): Observable<RevenueReport> {
    const params = range ? new HttpParams().set('from', range.from).set('to', range.to) : new HttpParams();
    return this.http.get<RevenueReport>('/api/dashboard/revenue-report', { params });
  }

  agingReport(): Observable<AgingReport> {
    return this.http.get<AgingReport>('/api/dashboard/aging-report');
  }

  forecast(): Observable<Forecast> {
    return this.http.get<Forecast>('/api/dashboard/forecast');
  }
}
