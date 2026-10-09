import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** An exchange rate: from its date, one unit of the currency is worth `rate` units of the home currency. */
export interface FxRate {
  id: number;
  currency: string;
  /** ISO date (yyyy-mm-dd) the rate applies from. */
  date: string;
  rate: number;
}

export type FxRateRequest = Omit<FxRate, 'id'>;

@Injectable({ providedIn: 'root' })
export class FxRateService {
  private readonly http = inject(HttpClient);

  list(): Observable<FxRate[]> {
    return this.http.get<FxRate[]>('/api/fx-rates');
  }

  record(request: FxRateRequest): Observable<FxRate> {
    return this.http.post<FxRate>('/api/fx-rates', request);
  }
}
