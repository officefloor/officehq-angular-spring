import { HttpContextToken, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Session } from './session';

/**
 * While the signed-in user has an as-of date set, every call to a sheet carries it: the server shows
 * the sheet as it stood at the end of that date and refuses every change. A call marked {@link LIVE}
 * reads what the store holds now, whatever the as-of date.
 */
/** Marks a read that is of the store as it is now, never as of the user's date. */
export const LIVE = new HttpContextToken<boolean>(() => false);

export const asOfInterceptor: HttpInterceptorFn = (req, next) => {
  const asOf = inject(Session).asOf();
  if (asOf === null || req.context.get(LIVE) || !req.url.startsWith('/api/sheets/')) return next(req);
  return next(req.clone({ setHeaders: { 'X-As-Of': asOf } }));
};
