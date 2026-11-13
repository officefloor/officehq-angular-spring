import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Session } from './session';

/** While the signed-in user previews the sheet on a date, every read carries it in the X-Preview header: the
 *  server reckons what it reads over the sheet as it is now, as if its today were that date. A change never
 *  carries it, so it still uses the sheet's real today. */
export const previewInterceptor: HttpInterceptorFn = (req, next) => {
  const preview = inject(Session).preview();
  return preview === null || req.method !== 'GET' || !req.url.startsWith('/api/')
    ? next(req)
    : next(req.clone({ setHeaders: { 'X-Preview': preview } }));
};
