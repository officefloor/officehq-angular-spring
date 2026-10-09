import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/** The app-wide settings. */
export interface Settings {
  /** The sales tax percentage a new invoice starts with. */
  defaultTaxPct: number;
  /** The currency foreign invoices are converted into for the business's own totals (read-only). */
  homeCurrency?: string;
}

@Injectable({ providedIn: 'root' })
export class SettingsService {
  private readonly http = inject(HttpClient);

  get(): Observable<Settings> {
    return this.http.get<Settings>('/api/settings');
  }

  update(settings: Settings): Observable<Settings> {
    return this.http.put<Settings>('/api/settings', settings);
  }
}
