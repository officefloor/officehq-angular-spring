import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Session } from './session';

/** The header that tells the server who is signed in, so it can record who made each change. */
export const USER_HEADER = 'X-User';

// Sends the signed-in user's name with every API request. The name is URI-encoded, since a header
// carries only plain ASCII; the server decodes it.
export const userHeader: HttpInterceptorFn = (req, next) => {
  const user = inject(Session).user();
  return next(user ? req.clone({ setHeaders: { [USER_HEADER]: encodeURIComponent(user) } }) : req);
};

/** The header that tells the server the date this screen shows the sheet as of. */
export const AS_OF_HEADER = 'X-As-Of';

// Sends the screen's as-of date, while one is set, with every API request: the server answers reads
// as the sheet stood at the end of that day and refuses every change.
export const asOfHeader: HttpInterceptorFn = (req, next) => {
  const asOf = inject(Session).asOf();
  return next(asOf ? req.clone({ setHeaders: { [AS_OF_HEADER]: asOf } }) : req);
};
