import { HttpClient } from '@angular/common/http';
import { Injectable, Pipe, PipeTransform, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** The locales a user may choose; en is theirs until they choose another. */
export const LOCALES = ['en', 'de'] as const;
export type LocaleName = (typeof LOCALES)[number];

const FIGURE = /^([+-]?)(\d+)\.(\d{2})$/;

/**
 * A figure (a number with two decimals, as the store keeps it) as a user of the locale reads it:
 * under de with a dot every three digits and a comma before the decimals, under en as it is.
 * Anything that is not such a figure is shown as it is.
 */
export function showFigure(text: string, locale: string): string {
  if (locale !== 'de') return text;
  const m = FIGURE.exec(text);
  if (!m) return text;
  return m[1] + m[2].replace(/\B(?=(\d{3})+$)/g, '.') + ',' + m[3];
}

// The signed-in user's locale, saved per user in the store (the same on every sheet). It changes
// only how figures are shown: entries are typed, and exports and audit lines written, as before.
@Injectable({ providedIn: 'root' })
export class Locale {
  private readonly http = inject(HttpClient);

  readonly current = signal<LocaleName>('en');

  /** Re-read the signed-in user's locale from the store. */
  async load(): Promise<void> {
    const view = await firstValueFrom(this.http.get<{ locale: LocaleName }>('/api/user/locale'));
    this.current.set(view.locale);
  }

  /** Save the signed-in user's locale in the store. */
  async save(locale: string): Promise<void> {
    const view = await firstValueFrom(this.http.put<{ locale: LocaleName }>('/api/user/locale', { locale }));
    this.current.set(view.locale);
  }
}

/** Shows a figure in the signed-in user's locale (see showFigure); other values as they are. */
@Pipe({ name: 'figure', pure: false })
export class FigurePipe implements PipeTransform {
  private readonly locale = inject(Locale);

  transform(value: string | number | null | undefined): string {
    if (value === null || value === undefined) return '';
    return showFigure(String(value), this.locale.current());
  }
}
