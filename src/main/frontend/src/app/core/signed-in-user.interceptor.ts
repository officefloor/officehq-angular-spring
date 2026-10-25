import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Session } from './session';

/** The header that tells the server who is signed in, so it can record who made each change. */
export const USER_HEADER = 'X-User';

// Sends the signed-in user's name (URI-encoded) with every request to the server.
export const signedInUserInterceptor: HttpInterceptorFn = (req, next) => {
  const user = inject(Session).user();
  return next(user ? req.clone({ setHeaders: { [USER_HEADER]: encodeURIComponent(user) } }) : req);
};
