import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, map, tap } from 'rxjs';

interface User {
  name: string;
}

interface SheetView {
  id: string;
}

// Who is signed in and which sheet is active. Everything else in the app is kept per sheet, so
// features read `sheetId` and send `user` with their requests (the X-User header).
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  private readonly _user = signal<string | null>(null);
  private readonly _sheetId = signal<string | null>(null);
  readonly user = this._user.asReadonly();
  readonly sheetId = this._sheetId.asReadonly();

  signIn(name: string): Observable<string> {
    return this.http.post<User>('/api/session', { name }).pipe(
      map((u) => u.name),
      tap((n) => {
        this._user.set(n);
        this._sheetId.set(null);
      }),
    );
  }

  loadSheet(id: string): Observable<string> {
    return this.http
      .post<SheetView>('/api/sheets/load', { id }, { headers: { 'X-User': this._user() ?? '' } })
      .pipe(
        map((s) => s.id),
        tap((sid) => this._sheetId.set(sid)),
      );
  }
}
