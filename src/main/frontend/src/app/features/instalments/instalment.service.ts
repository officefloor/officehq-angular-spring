import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** A scheduled part of an invoice: an amount due on a date. */
export interface Instalment {
  id: number;
  invoiceId: number;
  amount: number;
  /** ISO date (yyyy-MM-dd) the instalment is due. */
  date: string;
  /** Whether the instalment has been paid. */
  paid: boolean;
  /** Days past its due date while still unpaid. */
  daysLate: number;
  /** Interest accrued for the days it is late. */
  interest: number;
}

/** The interest charged for each day an instalment of an invoice is paid late. */
export interface InstalmentInterest {
  interestPerDay: number;
}

/** The earliest instalment of an invoice still to be paid. */
export interface NextInstalment {
  id: number;
  amount: number;
  /** ISO date (yyyy-MM-dd) it is due. */
  date: string;
  /** Whether its due date has already passed. */
  overdue: boolean;
}

export interface NewInstalment {
  amount: number;
  date: string;
}

@Injectable({ providedIn: 'root' })
export class InstalmentService {
  private readonly http = inject(HttpClient);

  /** An invoice's instalments, earliest due first. */
  list(projectId: number, invoiceId: number): Observable<Instalment[]> {
    return this.http.get<Instalment[]>(`/api/projects/${projectId}/invoices/${invoiceId}/instalments`);
  }

  /** The next instalment due on an invoice, or null when nothing more is due on its plan. */
  next(projectId: number, invoiceId: number): Observable<NextInstalment | null> {
    return this.http.get<NextInstalment | null>(`/api/projects/${projectId}/invoices/${invoiceId}/instalments/next`);
  }

  /** The interest charged for each day an instalment of an invoice is paid late. */
  interest(projectId: number, invoiceId: number): Observable<InstalmentInterest> {
    return this.http.get<InstalmentInterest>(`/api/projects/${projectId}/invoices/${invoiceId}/instalments/interest`);
  }

  applyInterest(projectId: number, invoiceId: number, interestPerDay: number): Observable<InstalmentInterest> {
    return this.http.put<InstalmentInterest>(`/api/projects/${projectId}/invoices/${invoiceId}/instalments/interest`, {
      interestPerDay,
    });
  }

  schedule(projectId: number, invoiceId: number, instalment: NewInstalment): Observable<Instalment> {
    return this.http.post<Instalment>(`/api/projects/${projectId}/invoices/${invoiceId}/instalments`, instalment);
  }

  remove(projectId: number, invoiceId: number, instalmentId: number): Observable<void> {
    return this.http.delete<void>(`/api/projects/${projectId}/invoices/${invoiceId}/instalments/${instalmentId}`);
  }
}
