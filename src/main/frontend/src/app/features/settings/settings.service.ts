import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** The app-wide settings. */
export interface Settings {
  /** The sales tax percentage a new invoice starts with. */
  defaultTaxPct: number;
  /** The currency foreign invoices are converted into for the business's own totals (read-only). */
  homeCurrency?: string;
  /** When revenue counts: when an invoice is sent, or once it is paid. */
  revenueRecognitionBasis?: RecognitionBasis;
  /** What the business aims to bill over the year, in the home currency; null when no target is set (read-only here). */
  billingTarget?: number | null;
}

/** When revenue counts: when an invoice is sent, or once it is paid. */
export type RecognitionBasis = 'sent' | 'paid';

@Injectable({ providedIn: 'root' })
export class SettingsService {
  private readonly http = inject(HttpClient);

  get(): Observable<Settings> {
    return this.http.get<Settings>('/api/settings');
  }

  update(settings: Settings): Observable<Settings> {
    // Kept alive so a save finishes even when the page is left or reloaded straight after.
    return this.http.put<Settings>('/api/settings', settings, { keepalive: true });
  }

  /** Sets the yearly billings target, or clears it when null. */
  updateBillingTarget(billingTarget: number | null): Observable<Settings> {
    return this.http.put<Settings>('/api/settings/billing-target', { billingTarget }, { keepalive: true });
  }
}
