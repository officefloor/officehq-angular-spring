import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Session } from './session';

/** While the screen shows the sheet as of a date, every sheet request carries it: the server then
 *  answers each read with the sheet as it stood at the end of that date and refuses every change. */
export const asOfInterceptor: HttpInterceptorFn = (req, next) => {
  const asOf = inject(Session).asOf();
  return asOf !== null && req.url.startsWith('/api/sheets/')
    ? next(req.clone({ setHeaders: { 'X-As-Of': asOf } }))
    : next(req);
};
