import { Pipe, PipeTransform, inject } from '@angular/core';
import { Session } from './session';

const FIGURE = /^(-?)(\d+)\.(\d{2})$/;

/** Shows a figure (a number with two decimals, as the server and the app's sums give it) in the
 *  signed-in user's locale: under de as 1.234,56, under en unchanged. Anything else (blank, n/a, a
 *  count) passes through as it is. Only what the screen shows changes; entries, exports and audit
 *  records keep the dot. */
@Pipe({ name: 'figure', pure: false })
export class FigurePipe implements PipeTransform {
  private readonly session = inject(Session);

  transform(value: string | number | null | undefined): string {
    const text = value === null || value === undefined ? '' : String(value);
    return formatFigure(text, this.session.locale());
  }
}

/** A figure's text in a locale; text that is not a figure is returned unchanged. */
export function formatFigure(text: string, locale: string): string {
  if (locale !== 'de') return text;
  const m = FIGURE.exec(text);
  if (!m) return text;
  return `${m[1]}${m[2].replace(/\B(?=(\d{3})+$)/g, '.')},${m[3]}`;
}
