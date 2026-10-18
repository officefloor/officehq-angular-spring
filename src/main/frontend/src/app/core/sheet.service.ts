import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, map } from 'rxjs';
import { SessionService } from './session.service';

// The active sheet. Everything else in the app is kept per sheet; loading an id re-reads it from
// the store (a sheet never loaded before starts empty).
@Injectable({ providedIn: 'root' })
export class SheetService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionService);
  private readonly current = signal<string | null>(null);

  readonly sheetId = this.current.asReadonly();

  load(id: string): Observable<string> {
    return this.http
      .post<{ id: string }>(`/api/sheets/${encodeURIComponent(id)}/load`, null, {
        headers: this.session.headers(),
      })
      .pipe(map((s) => (this.current.set(s.id), s.id)));
  }
}
