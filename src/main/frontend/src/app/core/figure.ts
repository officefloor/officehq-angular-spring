import { Pipe, PipeTransform, inject } from '@angular/core';
import { Session } from './session';

/** A figure as the screen holds it: a number with two decimals, a dot before them, no grouping. */
const FIGURE = /^(-?)(\d+)\.(\d{2})$/;

/**
 * Shows a figure (an amount, a due figure, a total, a rate, a share, a cut) in the signed-in
 * user's locale: under de as 1.234,56 (a dot every three digits, a comma before the decimals),
 * under en as it is. Any other value is shown as it is. Only the display changes: entries, exports
 * and audit lines keep the plain form. Impure, so a change of locale re-renders every figure.
 */
@Pipe({ name: 'figure', pure: false })
export class FigurePipe implements PipeTransform {
  private readonly session = inject(Session);

  transform(value: string | number | null | undefined): string {
    const text = value === null || value === undefined ? '' : String(value);
    return this.session.locale() === 'de' ? germanFigure(text) : text;
  }
}

/** A figure written as 1.234,56; any other text as it is. */
export function germanFigure(text: string): string {
  const m = FIGURE.exec(text);
  if (!m) return text;
  return m[1] + m[2].replace(/\B(?=(\d{3})+$)/g, '.') + ',' + m[3];
}
