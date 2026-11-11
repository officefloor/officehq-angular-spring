import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Session } from './session';

/** Sends the screen's as-of date with every request, so the sheet is read as it stood then and nothing is changed. */
export const asOfInterceptor: HttpInterceptorFn = (req, next) => {
  const asOf = inject(Session).asOf();
  return next(asOf === null ? req : req.clone({ setHeaders: { 'X-As-Of': asOf } }));
};
