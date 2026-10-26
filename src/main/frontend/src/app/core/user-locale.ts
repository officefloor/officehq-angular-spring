import { HttpClient } from '@angular/common/http';
import { Injectable, effect, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/** The locales a user can choose: how the screen shows them figures. */
export const LOCALES = ['en', 'de'] as const;
export type Locale = (typeof LOCALES)[number];

// The signed-in user's locale, saved per user in the store (en until they choose one). It changes
// only how figures are shown; entries are typed with a dot, and exports are unchanged.
@Injectable({ providedIn: 'root' })
export class UserLocale {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  private readonly localeState = signal<Locale>('en');
  readonly locale = this.localeState.asReadonly();

  constructor() {
    effect(() => {
      const user = this.session.user();
      this.localeState.set('en');
      if (user) void this.load(user);
    });
  }

  private async load(user: string): Promise<void> {
    const view = await firstValueFrom(this.http.get<{ locale: Locale }>('/api/me/locale'));
    if (this.session.user() === user) this.localeState.set(view.locale);
  }

  /** Save the signed-in user's locale; the store's answer is what the screen then uses. */
  async save(locale: Locale): Promise<void> {
    const view = await firstValueFrom(this.http.put<{ locale: Locale }>('/api/me/locale', { locale }));
    this.localeState.set(view.locale);
  }
}
