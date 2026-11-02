import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Session } from './session';

/** Sends the day the screen shows the sheet as of with every sheet request: a read is answered as
 *  the sheet stood then, and a change is refused. */
export const asOfInterceptor: HttpInterceptorFn = (req, next) => {
  const asOf = inject(Session).asOf();
  if (asOf === null || !req.url.startsWith('/api/sheets/')) return next(req);
  return next(req.clone({ setHeaders: { 'X-As-Of': asOf } }));
};
