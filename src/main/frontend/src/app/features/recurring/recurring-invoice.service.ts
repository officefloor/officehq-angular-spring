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

/** A recurring invoice that is coming up, with the project and client it bills and the currency it is in. */
export interface UpcomingRecurringInvoice extends RecurringInvoice {
  projectName: string;
  clientId: number;
  clientName: string;
  currency: string;
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

  /** The recurring invoices across all projects coming up from today, soonest first. */
  upcoming(): Observable<UpcomingRecurringInvoice[]> {
    return this.http.get<UpcomingRecurringInvoice[]>('/api/recurring-invoices/upcoming');
  }

  create(projectId: number, recurring: NewRecurringInvoice): Observable<RecurringInvoice> {
    return this.http.post<RecurringInvoice>(`/api/projects/${projectId}/recurring-invoices`, recurring);
  }
}
