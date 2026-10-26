import { Pipe, PipeTransform, inject } from '@angular/core';
import { UserLocale } from './user-locale';

const FIGURE = /^(-?)(\d+)\.(\d{2})$/;

/** A figure (a number with two decimals, as the store gives it) shown in the signed-in user's
 *  locale: under de as 1.234,56, under en as it is. Anything else is shown as it is. */
export function showFigure(value: string, locale: string): string {
  if (locale !== 'de') return value;
  const m = FIGURE.exec(value.trim());
  if (!m) return value;
  return `${m[1]}${m[2].replace(/\B(?=(\d{3})+$)/g, '.')},${m[3]}`;
}

// Impure, so a change of the user's locale (a signal) re-shows every figure.
@Pipe({ name: 'figure', pure: false })
export class FigurePipe implements PipeTransform {
  private readonly userLocale = inject(UserLocale);

  transform(value: unknown): string {
    const text = value === null || value === undefined ? '' : String(value);
    return showFigure(text, this.userLocale.locale());
  }
}
