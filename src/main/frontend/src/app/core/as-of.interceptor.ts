import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Session } from './session';

/** Sends the date the screen shows the sheet as of with every API request, when one is set. */
export const asOfInterceptor: HttpInterceptorFn = (req, next) => {
  const asOf = inject(Session).asOf();
  return asOf && req.url.startsWith('/api/') ? next(req.clone({ setHeaders: { 'X-As-Of': asOf } })) : next(req);
};

/** Sends the date the signed-in user previews the sheet's figures on with every API request, when one is set. */
export const previewInterceptor: HttpInterceptorFn = (req, next) => {
  const preview = inject(Session).preview();
  return preview && req.url.startsWith('/api/') ? next(req.clone({ setHeaders: { 'X-Preview': preview } })) : next(req);
};
