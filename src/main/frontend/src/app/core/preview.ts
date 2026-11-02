import { HttpContextToken, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Session } from './session';

/** Marks a call that must see the sheet's real today, whatever the user's preview date. */
export const REAL_TODAY = new HttpContextToken<boolean>(() => false);

/**
 * While the signed-in user has a preview date set, every call to a sheet carries it: the server shows
 * each read as if the sheet's today were that date, and makes every change on its real today.
 */
export const previewInterceptor: HttpInterceptorFn = (req, next) => {
  const preview = inject(Session).preview();
  if (preview === null || req.context.get(REAL_TODAY) || !req.url.startsWith('/api/sheets/')) return next(req);
  return next(req.clone({ setHeaders: { 'X-Preview': preview } }));
};
