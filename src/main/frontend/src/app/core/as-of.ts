import { HttpContextToken, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Session } from './session';

/** Marks a sheet request that reads the live sheet whatever date the screen shows it as of. */
export const LIVE = new HttpContextToken<boolean>(() => false);

/** Names the date the screen shows the sheet as of (X-As-Of) on every sheet request while one is set. */
export const asOfInterceptor: HttpInterceptorFn = (req, next) => {
  const asOf = inject(Session).asOf();
  if (asOf === null || req.context.get(LIVE) || !req.url.startsWith('/api/sheets/')) return next(req);
  return next(req.clone({ setHeaders: { 'X-As-Of': asOf } }));
};
