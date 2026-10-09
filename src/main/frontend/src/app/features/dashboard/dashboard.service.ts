import { HttpClient } from '@angular/common/http';
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

@Injectable({ providedIn: 'root' })
export class DashboardService {
  private readonly http = inject(HttpClient);

  summary(): Observable<DashboardSummary> {
    return this.http.get<DashboardSummary>('/api/dashboard');
  }
}
