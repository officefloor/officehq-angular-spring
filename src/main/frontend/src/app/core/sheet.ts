import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

export interface SheetView {
  id: string;
}

// The active sheet. Everything else in the app is kept per sheet; loading an id re-reads it from
// the store, and an id never loaded before starts an empty sheet.
@Injectable({ providedIn: 'root' })
export class Sheets {
  private readonly http = inject(HttpClient);
  private readonly active = signal<SheetView | null>(null);
  readonly current = this.active.asReadonly();

  load(id: string): Observable<SheetView> {
    return this.http
      .post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/load`, null)
      .pipe(tap((s) => this.active.set(s)));
  }
}
