import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, map } from 'rxjs';
import { SessionService } from './session.service';

interface SheetView {
  id: string;
  today: string;
}

// The active sheet. Everything else in the app is kept per sheet; loading an id re-reads it from
// the store (a sheet never loaded before starts empty).
//
// Each sheet has its own "today" (yyyy-MM-dd). Every date-dependent decision and every default
// date uses it, never the real clock.
@Injectable({ providedIn: 'root' })
export class SheetService {
  private readonly http = inject(HttpClient);
  private readonly session = inject(SessionService);
  private readonly current = signal<string | null>(null);
  private readonly currentToday = signal<string | null>(null);
  private readonly loadCount = signal(0);

  readonly sheetId = this.current.asReadonly();
  readonly today = this.currentToday.asReadonly();
  /** Bumped on every load, so what is kept per sheet is re-read even when the same id is loaded again. */
  readonly loads = this.loadCount.asReadonly();

  load(id: string): Observable<string> {
    return this.http
      .post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/load`, null, {
        headers: this.session.headers(),
      })
      .pipe(map((s) => (this.show(s), this.loadCount.update((n) => n + 1), s.id)));
  }

  setToday(today: string): Observable<string> {
    const id = this.current();
    if (!id) throw new Error('no sheet loaded');
    return this.http
      .put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/today`, { today }, {
        headers: this.session.headers(),
      })
      .pipe(map((s) => (this.show(s), s.today)));
  }

  private show(s: SheetView): string {
    this.current.set(s.id);
    this.currentToday.set(s.today);
    return s.id;
  }
}
