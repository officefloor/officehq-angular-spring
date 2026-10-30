import { Pipe, PipeTransform, inject } from '@angular/core';
import { Session } from './session';

/** A figure as the server sends it: digits, a dot, two decimals. */
const FIGURE = /^(-?)(\d+)\.(\d{2})$/;

/**
 * Shows a figure (an amount, total, rate, share or cut with two decimals) in the signed-in user's locale:
 * under de as 1.234,56, under en as it comes. Anything that is not such a figure is shown as it is.
 * Impure, as it reads the user's locale, which may change while the value does not.
 */
@Pipe({ name: 'figure', pure: false })
export class FigurePipe implements PipeTransform {
  private readonly session = inject(Session);

  transform<T>(value: T): T | string {
    return formatFigure(value, this.session.locale());
  }
}

export function formatFigure<T>(value: T, locale: string): T | string {
  if (locale !== 'de' || typeof value !== 'string') return value;
  const m = FIGURE.exec(value);
  if (!m) return value;
  return `${m[1]}${m[2].replace(/\B(?=(\d{3})+$)/g, '.')},${m[3]}`;
}
