import { HttpContext, HttpContextToken, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Session } from './session';

/** The header that tells the server the date this screen shows the sheet as of. */
export const AS_OF_HEADER = 'X-As-Of';

/** Marks a read that is always answered with the sheet as it is now, whatever the screen's as-of date. */
export const LIVE_READ = new HttpContextToken<boolean>(() => false);

// Sends the screen's as-of date, while one is set, with every request to the server: reads answer
// with the sheet as it stood then, and every change is refused.
export const asOfInterceptor: HttpInterceptorFn = (req, next) => {
  const asOf = inject(Session).asOf();
  return next(asOf && !req.context.get(LIVE_READ) ? req.clone({ setHeaders: { [AS_OF_HEADER]: asOf } }) : req);
};

/** The options of a read answered with the sheet as it stood at the end of a date, whatever the screen's
 *  as-of date; none for a read as the screen shows it. */
export function readAsOf(date?: string): { headers?: Record<string, string>; context?: HttpContext } {
  return date === undefined ? {} : { headers: { [AS_OF_HEADER]: date }, context: new HttpContext().set(LIVE_READ, true) };
}

/** The header that tells the server the date this screen shows the sheet's figures as of. */
export const PREVIEW_HEADER = 'X-Preview';

// Sends the screen's preview date, while one is set, with every read: the server answers it over the
// sheet's current records as if the sheet's today were that date. Changes are sent without it.
export const previewInterceptor: HttpInterceptorFn = (req, next) => {
  const preview = inject(Session).preview();
  return next(preview && req.method === 'GET' ? req.clone({ setHeaders: { [PREVIEW_HEADER]: preview } }) : req);
};
