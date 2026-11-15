import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Session } from './session';

/** While the screen shows the sheet as of a date, every sheet request carries it: the server then
 *  answers each read with the sheet as it stood at the end of that date and refuses every change.
 *  While the screen previews the sheet as of a date, every sheet request carries that too: the
 *  server answers each read as if the sheet's today were that date, and each change ignores it. */
export const asOfInterceptor: HttpInterceptorFn = (req, next) => {
  const session = inject(Session);
  if (!req.url.startsWith('/api/sheets/')) return next(req);
  const headers: Record<string, string> = {};
  const asOf = session.asOf();
  if (asOf !== null) headers['X-As-Of'] = asOf;
  const preview = session.preview();
  if (preview !== null) headers['X-Preview-Date'] = preview;
  return Object.keys(headers).length > 0 ? next(req.clone({ setHeaders: headers })) : next(req);
};
