import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export type InvoiceStatus = 'DRAFT' | 'SENT';

// An invoice as listed for its client. `total` is a decimal string to the cent, worked out by the
// server: each line's quantity times unit price rounded half up to the cent, then added up.
export interface InvoiceSummary {
  id: number;
  status: InvoiceStatus;
  total: string;
  issueDate: string | null;
  dueDate: string | null;
}

// The invoices REST API.
@Injectable({ providedIn: 'root' })
export class InvoiceService {
  private readonly http = inject(HttpClient);

  forClient(clientId: number): Observable<InvoiceSummary[]> {
    return this.http.get<InvoiceSummary[]>(`/api/clients/${clientId}/invoices`);
  }
}
