import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** Money given back to a client against an invoice. */
export interface CreditNote {
  id: number;
  invoiceId: number;
  amount: number;
  /** ISO instant the credit note was raised. */
  issuedAt: string;
}

export interface NewCreditNote {
  amount: number;
}

@Injectable({ providedIn: 'root' })
export class CreditNoteService {
  private readonly http = inject(HttpClient);

  /** An invoice's credit notes, oldest first. */
  list(projectId: number, invoiceId: number): Observable<CreditNote[]> {
    return this.http.get<CreditNote[]>(`/api/projects/${projectId}/invoices/${invoiceId}/credit-notes`);
  }

  issue(projectId: number, invoiceId: number, creditNote: NewCreditNote): Observable<CreditNote> {
    return this.http.post<CreditNote>(`/api/projects/${projectId}/invoices/${invoiceId}/credit-notes`, creditNote);
  }
}
