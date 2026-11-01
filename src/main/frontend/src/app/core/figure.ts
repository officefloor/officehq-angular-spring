import { Pipe, PipeTransform, inject } from '@angular/core';
import { Locale, Session } from './session';

/** A figure as the store and every export and audit line write it: a number with two decimals. */
const FIGURE = /^(-?)(\d+)\.(\d{2})$/;

/**
 * A figure shown in the locale: under de with a dot every three digits and a comma before the decimals
 * (1.234,56), under en as it is (1234.56). Any other text is shown as it is.
 */
export function formatFigure(text: string, locale: Locale): string {
  const m = FIGURE.exec(text);
  if (!m || locale === 'en') return text;
  return `${m[1]}${m[2].replace(/\B(?=(\d{3})+(?!\d))/g, '.')},${m[3]}`;
}

/**
 * Shows a value that is a figure in the signed-in user's locale; anything else (a count, n/a, blank) as it is.
 * Impure so it follows a change of locale; it reads the locale signal, so the view re-renders with it.
 */
@Pipe({ name: 'figure', pure: false })
export class FigurePipe implements PipeTransform {
  private readonly session = inject(Session);

  transform(value: string | number | null | undefined): string {
    if (value === null || value === undefined) return '';
    return formatFigure(String(value), this.session.locale());
  }
}
