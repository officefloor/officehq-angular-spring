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

  schedule(projectId: number, invoiceId: number, instalment: NewInstalment): Observable<Instalment> {
    return this.http.post<Instalment>(`/api/projects/${projectId}/invoices/${invoiceId}/instalments`, instalment);
  }

  remove(projectId: number, invoiceId: number, instalmentId: number): Observable<void> {
    return this.http.delete<void>(`/api/projects/${projectId}/invoices/${invoiceId}/instalments/${instalmentId}`);
  }
}
