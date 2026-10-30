import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Session } from './session';

/** Names the date the screen shows the sheet as of (X-As-Of) on every sheet request while one is set. */
export const asOfInterceptor: HttpInterceptorFn = (req, next) => {
  const asOf = inject(Session).asOf();
  if (asOf === null || !req.url.startsWith('/api/sheets/')) return next(req);
  return next(req.clone({ setHeaders: { 'X-As-Of': asOf } }));
};
