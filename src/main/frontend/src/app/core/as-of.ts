import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Session } from './session';

/** While the signed-in user views the sheet as of a date, every request carries it in the X-As-Of header:
 *  the server answers what it reads as it stood at the end of that date, and refuses every change. */
export const asOfInterceptor: HttpInterceptorFn = (req, next) => {
  const asOf = inject(Session).asOf();
  return asOf === null || !req.url.startsWith('/api/') ? next(req) : next(req.clone({ setHeaders: { 'X-As-Of': asOf } }));
};
