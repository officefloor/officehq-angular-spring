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
}

/** What is still owed in one currency; different currencies are never added together. */
export interface CurrencyTotal {
  currency: CurrencyCode;
  amount: number;
}

export interface DashboardSummary {
  clients: number;
  projects: number;
  /** What is still owed, one total per currency anything is owed in. */
  outstanding: CurrencyTotal[];
  overdue: number;
  /** The currency the overdue amount is given in. */
  homeCurrency: CurrencyCode;
  /** What is left to pay on overdue invoices plus the late fees they have accrued, in the home currency. */
  overdueAmount: number;
  topClients: TopClient[];
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

@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly http = inject(HttpClient);

  summary(): Observable<DashboardSummary> {
    return this.http.get<DashboardSummary>('/api/dashboard');
  }

  taxSummary(from: string, to: string): Observable<TaxSummary> {
    return this.http.get<TaxSummary>('/api/dashboard/tax-summary', { params: new HttpParams().set('from', from).set('to', to) });
  }
}
