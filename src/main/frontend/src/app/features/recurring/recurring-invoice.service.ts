import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** How often a recurring invoice repeats. */
export type RecurringFrequency = 'MONTHLY';

/** An invoice on a project that repeats on a schedule for a fixed amount. */
export interface RecurringInvoice {
  id: number;
  projectId: number;
  amount: number;
  frequency: RecurringFrequency;
  /** ISO date (yyyy-MM-dd) the next invoice falls on. */
  nextDate: string;
}

export interface NewRecurringInvoice {
  amount: number;
  frequency: RecurringFrequency;
  nextDate: string;
}

@Injectable({ providedIn: 'root' })
export class RecurringInvoiceService {
  private readonly http = inject(HttpClient);

  list(projectId: number): Observable<RecurringInvoice[]> {
    return this.http.get<RecurringInvoice[]>(`/api/projects/${projectId}/recurring-invoices`);
  }

  create(projectId: number, recurring: NewRecurringInvoice): Observable<RecurringInvoice> {
    return this.http.post<RecurringInvoice>(`/api/projects/${projectId}/recurring-invoices`, recurring);
  }
}
