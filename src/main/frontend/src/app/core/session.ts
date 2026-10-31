import { HttpClient, HttpInterceptorFn } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

/** The locales a user may choose: how the screen shows them figures. */
export const LOCALES = ['en', 'de'] as const;
export type Locale = (typeof LOCALES)[number];

// Who is signed in. There is no password: a name is typed, the server checks it and echoes it back
// with the user's saved locale.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);
  private readonly name = signal<string | null>(null);
  private readonly chosenLocale = signal<Locale>('en');
  readonly user = this.name.asReadonly();
  readonly locale = this.chosenLocale.asReadonly();

  signIn(name: string): Observable<{ name: string; locale: Locale }> {
    return this.http.post<{ name: string; locale: Locale }>('/api/session', { name }).pipe(
      tap((u) => {
        this.chosenLocale.set(u.locale ?? 'en');
        this.name.set(u.name);
      }),
    );
  }

  /** Save the signed-in user's locale. */
  setLocale(locale: Locale): Observable<{ locale: Locale }> {
    return this.http
      .put<{ locale: Locale }>('/api/session/locale', { locale })
      .pipe(tap((l) => this.chosenLocale.set(l.locale)));
  }
}

// Every API call carries the signed-in name in the X-User header.
export const userHeader: HttpInterceptorFn = (req, next) => {
  const user = inject(Session).user();
  return next(user ? req.clone({ setHeaders: { 'X-User': user } }) : req);
};
