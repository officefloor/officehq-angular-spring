import { Pipe, PipeTransform, inject } from '@angular/core';
import { Session } from './session';

const FIGURE = /^(-?)(\d+)\.(\d{2})$/;

/** Shows a figure (a value whose whole text is a number with two decimals) in the signed-in user's
 * locale: under 'de' as 1.234,56, under 'en' as it is. Any other value is shown as it is. Impure, so a
 * change of locale re-renders every figure. */
@Pipe({ name: 'figure', pure: false })
export class FigurePipe implements PipeTransform {
  private readonly session = inject(Session);

  transform<T>(value: T): T | string {
    if (this.session.locale() !== 'de') return value;
    if (typeof value !== 'string' && typeof value !== 'number') return value;
    const m = FIGURE.exec(String(value).trim());
    if (!m) return value;
    return `${m[1]}${m[2].replace(/\B(?=(\d{3})+(?!\d))/g, '.')},${m[3]}`;
  }
}
