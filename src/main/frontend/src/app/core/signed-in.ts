import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Session } from './session';

/**
 * Tells the server who is signed in on every request, so changes are recorded against them, and the
 * date their screen shows the sheet as of, and the preview date its figures are read on, when set.
 */
export const signedInInterceptor: HttpInterceptorFn = (req, next) => {
  const session = inject(Session);
  const user = session.user();
  const asOf = session.asOf();
  const headers: Record<string, string> = {};
  if (user) headers['X-User'] = encodeURIComponent(user);
  const preview = session.preview();
  if (asOf) headers['X-As-Of'] = asOf;
  if (preview) headers['X-Preview'] = preview;
  return next(Object.keys(headers).length > 0 ? req.clone({ setHeaders: headers }) : req);
};
