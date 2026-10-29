import { HttpClient } from '@angular/common/http';
import { Injectable, Pipe, PipeTransform, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export const LOCALES = ['en', 'de'] as const;

// The signed-in user's locale, saved per user: how the screen shows them figures. Entries are typed, and exports
// and audit lines are written, the same under every locale.
@Injectable({ providedIn: 'root' })
export class UserLocale {
  private readonly http = inject(HttpClient);

  readonly locale = signal<string>('en');

  async load(): Promise<void> {
    const v = await firstValueFrom(this.http.get<{ locale: string }>('/api/me/locale'));
    this.locale.set(v.locale);
  }

  async save(locale: string): Promise<void> {
    const v = await firstValueFrom(this.http.put<{ locale: string }>('/api/me/locale', { locale }));
    this.locale.set(v.locale);
  }
}

const FIGURE = /^(-?)(\d+)\.(\d{2})$/;

// Shows a figure (a number with two decimals) in the user's locale; under de as 1.234,56. Anything else is shown as it is.
export function formatFigure(value: unknown, locale: string): string {
  const text = value === null || value === undefined ? '' : String(value);
  if (locale !== 'de') return text;
  const m = FIGURE.exec(text.trim());
  if (!m) return text;
  return m[1] + m[2].replace(/\B(?=(\d{3})+(?!\d))/g, '.') + ',' + m[3];
}

// Impure so it follows the locale signal it reads.
@Pipe({ name: 'fig', pure: false })
export class FigPipe implements PipeTransform {
  private readonly userLocale = inject(UserLocale);

  transform(value: unknown): string {
    return formatFigure(value, this.userLocale.locale());
  }
}
