import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Session } from './session';

/** Sends the day the screen shows the sheet as of with every sheet request: a read is answered as
 *  the sheet stood then, and a change is refused. */
export const asOfInterceptor: HttpInterceptorFn = (req, next) => {
  const session = inject(Session);
  const asOf = session.asOf();
  if (!req.url.startsWith('/api/sheets/')) return next(req);
  const headers: Record<string, string> = {};
  if (asOf !== null) headers['X-As-Of'] = asOf;
  // a read is answered as if the sheet's today were the preview date; a change uses the real one
  const preview = session.preview();
  if (preview !== null && req.method === 'GET') headers['X-Preview'] = preview;
  return Object.keys(headers).length === 0 ? next(req) : next(req.clone({ setHeaders: headers }));
};
