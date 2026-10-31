import { HttpClient, HttpInterceptorFn } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';

/** The locales a user may choose: how the screen shows them figures. */
export const LOCALES = ['en', 'de'] as const;
export type Locale = (typeof LOCALES)[number];

/** The people-list columns a user may switch off on their screen. */
export const COLUMNS = [
  'claimed', 'due-total', 'statement-count', 'stated-total', 'statement-date', 'overdue-count', 'overdue-total',
  'patrons', 'backing', 'hold', 'retired', 'terms', 'contact', 'aliases', 'excess',
] as const;
export type ColumnKey = (typeof COLUMNS)[number];

// Who is signed in. There is no password: a name is typed, the server checks it and echoes it back
// with the user's saved locale and the people-list columns they have switched off.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);
  private readonly name = signal<string | null>(null);
  private readonly chosenLocale = signal<Locale>('en');
  readonly user = this.name.asReadonly();
  private readonly hidden = signal<ReadonlySet<string>>(new Set());
  readonly locale = this.chosenLocale.asReadonly();
  readonly hiddenColumns = this.hidden.asReadonly();

  signIn(name: string): Observable<{ name: string; locale: Locale; hiddenColumns: string[] }> {
    return this.http.post<{ name: string; locale: Locale; hiddenColumns: string[] }>('/api/session', { name }).pipe(
      tap((u) => {
        this.chosenLocale.set(u.locale ?? 'en');
        this.hidden.set(new Set(u.hiddenColumns ?? []));
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

  /** Whether the signed-in user's screen shows this people-list column. */
  shows(column: ColumnKey): boolean {
    return !this.hidden().has(column);
  }

  /** Switch a people-list column on or off for the signed-in user, and save the choice. */
  setColumn(column: ColumnKey, on: boolean): void {
    const next = new Set(this.hidden());
    if (on) next.delete(column);
    else next.add(column);
    this.hidden.set(next);
    this.http.put<{ hidden: string[] }>('/api/session/columns', { hidden: [...next] }).subscribe();
  }
}

// Every API call carries the signed-in name in the X-User header.
export const userHeader: HttpInterceptorFn = (req, next) => {
  const user = inject(Session).user();
  return next(user ? req.clone({ setHeaders: { 'X-User': user } }) : req);
};
