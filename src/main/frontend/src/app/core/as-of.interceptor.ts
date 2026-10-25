import { HttpContextToken, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Session } from './session';

/** The header that tells the server the date this screen shows the sheet as of. */
export const AS_OF_HEADER = 'X-As-Of';

/** Marks a read that is always answered with the sheet as it is now, whatever the screen's as-of date. */
export const LIVE_READ = new HttpContextToken<boolean>(() => false);

// Sends the screen's as-of date, while one is set, with every request to the server: reads answer
// with the sheet as it stood then, and every change is refused.
export const asOfInterceptor: HttpInterceptorFn = (req, next) => {
  const asOf = inject(Session).asOf();
  return next(asOf && !req.context.get(LIVE_READ) ? req.clone({ setHeaders: { [AS_OF_HEADER]: asOf } }) : req);
};
