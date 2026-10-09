import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Observable, tap } from 'rxjs';

/** A currency clients can be billed in, with the symbol its amounts are shown with and the step they round to. */
export interface Currency {
  code: string;
  symbol: string;
  /** Amounts in this currency are shown rounded to the nearest multiple of this (0.05 for five cents). */
  roundingStep: number;
}

@Injectable({ providedIn: 'root' })
export class CurrencyService {
  private readonly http = inject(HttpClient);

  /** The known currencies, loaded once and kept up to date as their rounding is changed. */
  readonly currencies = rxResource({ stream: () => this.http.get<Currency[]>('/api/currencies') });

  readonly list = computed(() => (this.currencies.hasValue() ? this.currencies.value() : []));

  private readonly byCode = computed(() => new Map(this.list().map((c) => [c.code, c])));

  /** The currency with this code, if it is known (and has loaded). */
  find(code: string): Currency | undefined {
    return this.byCode().get(code);
  }

  changeRounding(code: string, roundingStep: number): Observable<Currency> {
    return this.http
      .put<Currency>(`/api/currencies/${encodeURIComponent(code)}/rounding`, { roundingStep })
      .pipe(tap((updated) => this.currencies.update((list) => (list ?? []).map((c) => (c.code === updated.code ? updated : c)))));
  }
}
