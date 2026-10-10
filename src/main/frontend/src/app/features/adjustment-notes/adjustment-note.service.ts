import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** A correction to a sent invoice: an amount added to (or, when negative, taken off) its total, and why. */
export interface AdjustmentNote {
  id: number;
  invoiceId: number;
  amount: number;
  reason: string;
  /** ISO instant the adjustment note was issued. */
  issuedAt: string;
}

export interface NewAdjustmentNote {
  amount: number;
  reason: string;
}

@Injectable({ providedIn: 'root' })
export class AdjustmentNoteService {
  private readonly http = inject(HttpClient);

  /** An invoice's adjustment notes, oldest first. */
  list(projectId: number, invoiceId: number): Observable<AdjustmentNote[]> {
    return this.http.get<AdjustmentNote[]>(`/api/projects/${projectId}/invoices/${invoiceId}/adjustment-notes`);
  }

  issue(projectId: number, invoiceId: number, adjustmentNote: NewAdjustmentNote): Observable<AdjustmentNote> {
    return this.http.post<AdjustmentNote>(`/api/projects/${projectId}/invoices/${invoiceId}/adjustment-notes`, adjustmentNote);
  }
}
