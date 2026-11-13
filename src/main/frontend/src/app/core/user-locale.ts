import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/** The locales a user may choose; the first is every user's until they choose another. */
export const LOCALES = ['en', 'de'] as const;

// The signed-in user's locale, kept per user on the server and re-read each time a sheet is loaded. It
// only changes how the screen shows figures (see LocaleFigures); entries, exports and audit lines keep
// the dot.
@Injectable({ providedIn: 'root' })
export class UserLocale {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly locale = signal<string>(LOCALES[0]);
  private loads = 0;

  /** Re-read the signed-in user's locale from the server; an error message when that failed. */
  async load(): Promise<string | null> {
    const user = this.session.user();
    const load = ++this.loads;
    if (!user) return null;
    try {
      const view = await firstValueFrom(
        this.http.get<{ locale: string }>('/api/user/locale', { headers: { 'X-User': user } }),
      );
      if (load === this.loads) this.locale.set(view.locale);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not load your locale (${e.status}).` : 'Could not load your locale.';
    }
  }

  /** Choose the signed-in user's locale; an error message when refused. */
  async choose(locale: string): Promise<string | null> {
    const user = this.session.user();
    if (!user) return 'Sign in first.';
    try {
      const view = await firstValueFrom(
        this.http.put<{ locale: string }>('/api/user/locale', { locale }, { headers: { 'X-User': user } }),
      );
      ++this.loads;
      this.locale.set(view.locale);
      return null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && typeof e.error?.message === 'string') return e.error.message;
      return e instanceof HttpErrorResponse ? `Could not save your locale (${e.status}).` : 'Could not save your locale.';
    }
  }
}
