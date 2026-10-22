import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Session } from './session';

/** Tells the server who is signed in on every request, so changes are recorded against them. */
export const signedInInterceptor: HttpInterceptorFn = (req, next) => {
  const user = inject(Session).user();
  return next(user ? req.clone({ setHeaders: { 'X-User': encodeURIComponent(user) } }) : req);
};
