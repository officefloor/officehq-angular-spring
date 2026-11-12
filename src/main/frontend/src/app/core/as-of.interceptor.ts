import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Session } from './session';

/**
 * Sends the screen's as-of date with every request, so the sheet is read as it stood then and nothing is changed. A
 * request that names its own as-of date (a read of how the sheet stood at an earlier day) keeps it.
 */
export const asOfInterceptor: HttpInterceptorFn = (req, next) => {
  const asOf = inject(Session).asOf();
  return next(asOf === null || req.headers.has('X-As-Of') ? req : req.clone({ setHeaders: { 'X-As-Of': asOf } }));
};
