import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CurrencyCode } from '../clients/client.service';

/** A manual adjustment to the tax owed: positive adds to it, negative takes from it, in the home currency. */
export interface TaxAdjustment {
  id: number;
  /** ISO date (yyyy-mm-dd) in the tax period the adjustment belongs to. */
  date: string;
  amount: number;
  currency: CurrencyCode;
  reason: string | null;
}

export type TaxAdjustmentRequest = Pick<TaxAdjustment, 'date' | 'amount' | 'reason'>;

@Injectable({ providedIn: 'root' })
export class TaxAdjustmentService {
  private readonly http = inject(HttpClient);

  list(): Observable<TaxAdjustment[]> {
    return this.http.get<TaxAdjustment[]>('/api/tax-adjustments');
  }

  record(request: TaxAdjustmentRequest): Observable<TaxAdjustment> {
    return this.http.post<TaxAdjustment>('/api/tax-adjustments', request);
  }
}
