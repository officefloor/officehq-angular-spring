import { Pipe, PipeTransform, inject } from '@angular/core';
import { Session } from './session';

const FIGURE = /^(-?)(\d+)\.(\d{2})$/;

/** A figure (a number with two decimals, as the store gives it) as the user's locale shows it:
 *  en 1234.56, de 1.234,56. Anything else is shown as it is. */
export function formatFigure(value: string, locale: string): string {
  const m = locale === 'de' ? FIGURE.exec(value) : null;
  if (!m) return value;
  return `${m[1]}${m[2].replace(/\B(?=(\d{3})+(?!\d))/g, '.')},${m[3]}`;
}

/** Shows a figure in the signed-in user's locale; impure so a change of locale re-renders it. */
@Pipe({ name: 'figure', pure: false })
export class FigurePipe implements PipeTransform {
  private readonly session = inject(Session);

  transform(value: string | number | null | undefined): string {
    if (value === null || value === undefined) return '';
    return formatFigure(String(value), this.session.locale());
  }
}
