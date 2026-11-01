import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Session } from './session';

/**
 * While the signed-in user has an as-of date set, every call to a sheet carries it: the server shows
 * the sheet as it stood at the end of that date and refuses every change.
 */
export const asOfInterceptor: HttpInterceptorFn = (req, next) => {
  const asOf = inject(Session).asOf();
  if (asOf === null || !req.url.startsWith('/api/sheets/')) return next(req);
  return next(req.clone({ setHeaders: { 'X-As-Of': asOf } }));
};
