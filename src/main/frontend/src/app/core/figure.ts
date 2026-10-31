import { Pipe, PipeTransform, inject } from '@angular/core';
import { Locale, Session } from './session';

const TWO_DECIMALS = /^(-?)(\d+)\.(\d{2})$/;

/** A figure (a number with two decimals) as the given locale shows it; any other text is left as it is. */
export function formatFigure(text: string, locale: Locale): string {
  const m = TWO_DECIMALS.exec(text);
  if (!m || locale !== 'de') return text;
  const [, sign, whole, cents] = m;
  return sign + whole.replace(/\B(?=(\d{3})+(?!\d))/g, '.') + ',' + cents;
}

/**
 * Shows a figure in the signed-in user's locale: under de as 1.234,56, under en as it comes.
 * Text that is not a number with two decimals is shown unchanged. Impure, so a change of locale shows at once.
 */
@Pipe({ name: 'figure', pure: false })
export class FigurePipe implements PipeTransform {
  private readonly session = inject(Session);

  transform(value: string | number | null | undefined): string {
    if (value === null || value === undefined) return '';
    return formatFigure(String(value), this.session.locale());
  }
}
