import { HttpClient, HttpInterceptorFn } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

// Who is signed in. There is no password: a name is typed, the server checks it and echoes it back.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);
  private readonly name = signal<string | null>(null);
  readonly user = this.name.asReadonly();

  signIn(name: string): Observable<{ name: string }> {
    return this.http
      .post<{ name: string }>('/api/session', { name })
      .pipe(tap((u) => this.name.set(u.name)));
  }
}

// Every API call carries the signed-in name in the X-User header.
export const userHeader: HttpInterceptorFn = (req, next) => {
  const user = inject(Session).user();
  return next(user ? req.clone({ setHeaders: { 'X-User': user } }) : req);
};
