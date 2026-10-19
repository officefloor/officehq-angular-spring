import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, map, tap } from 'rxjs';

interface User {
  name: string;
}

interface SheetView {
  id: string;
  today: string;
}

// Who is signed in and which sheet is active. Everything else in the app is kept per sheet, so
// features read `sheetId` and send `user` with their requests (the X-User header).
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  private readonly _user = signal<string | null>(null);
  private readonly _sheetId = signal<string | null>(null);
  readonly user = this._user.asReadonly();
  private readonly _today = signal<string | null>(null);
  readonly sheetId = this._sheetId.asReadonly();
  /** The active sheet's today (yyyy-mm-dd). Date-dependent features use this, never the clock. */
  readonly today = this._today.asReadonly();

  signIn(name: string): Observable<string> {
    return this.http.post<User>('/api/session', { name }).pipe(
      map((u) => u.name),
      tap((n) => {
        this._user.set(n);
        this._sheetId.set(null);
        this._today.set(null);
      }),
    );
  }

  loadSheet(id: string): Observable<string> {
    return this.http
      .post<SheetView>('/api/sheets/load', { id }, { headers: { 'X-User': this._user() ?? '' } })
      .pipe(
        tap((s) => {
          this._sheetId.set(s.id);
          this._today.set(s.today);
        }),
        map((s) => s.id),
      );
  }

  setToday(today: string): Observable<string> {
    const id = this._sheetId() ?? '';
    return this.http
      .put<SheetView>(
        `/api/sheets/${encodeURIComponent(id)}/today`,
        { today },
        { headers: { 'X-User': this._user() ?? '' } },
      )
      .pipe(
        tap((s) => {
          if (this._sheetId() === s.id) this._today.set(s.today);
        }),
        map((s) => s.today),
      );
  }
}
