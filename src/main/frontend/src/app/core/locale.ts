import { HttpClient } from '@angular/common/http';
import { Injectable, Pipe, PipeTransform, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from './session';

/** The locales a user may choose. */
export const LOCALES = ['en', 'de'] as const;
export type Locale = (typeof LOCALES)[number];

interface LocaleView {
  locale: Locale;
}

/** A figure as the app computes and stores it: a number with two decimals. */
const FIGURE = /^(-?)(\d+)\.(\d{2})$/;

// The signed-in user's locale, saved per user: it decides how the screen shows them figures. Entries are typed, and
// exports and audit records written, the same under every locale.
@Injectable({ providedIn: 'root' })
export class UserLocale {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  /** The signed-in user's locale: en until it is read. */
  readonly locale = signal<Locale>('en');

  /** Re-read the signed-in user's locale. */
  async read(): Promise<void> {
    const user = this.session.user();
    const view = await firstValueFrom(this.http.get<LocaleView>('/api/me/locale', this.session.headers()));
    if (this.session.user() === user) this.locale.set(view.locale);
  }

  /** Save the signed-in user's locale. */
  async choose(locale: Locale): Promise<void> {
    const user = this.session.user();
    const view = await firstValueFrom(this.http.put<LocaleView>('/api/me/locale', { locale }, this.session.headers()));
    if (this.session.user() === user) this.locale.set(view.locale);
  }

  /** The text as shown under the user's locale: under de a figure reads 1.234,56; anything else is left as it is. */
  show(text: unknown): unknown {
    if (this.locale() !== 'de' || typeof text !== 'string') return text;
    const figure = FIGURE.exec(text);
    if (!figure) return text;
    const [, sign, whole, cents] = figure;
    return `${sign}${whole.replace(/\B(?=(\d{3})+$)/g, '.')},${cents}`;
  }
}

/** Shows a figure under the signed-in user's locale; impure, so it follows a change of locale. */
@Pipe({ name: 'fig', pure: false })
export class FigurePipe implements PipeTransform {
  private readonly locale = inject(UserLocale);

  transform<T>(value: T): T | string {
    return this.locale.show(value) as T | string;
  }
}
