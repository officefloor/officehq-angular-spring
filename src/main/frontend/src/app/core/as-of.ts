import { HttpContextToken, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Session } from './session';

/** Marks a sheet request that reads the live sheet whatever date the screen shows it as of. */
export const LIVE = new HttpContextToken<boolean>(() => false);

/**
 * Names the date the screen shows the sheet as of (X-As-Of) on every sheet request while one is set; otherwise
 * names the screen's preview date (X-Preview) on every sheet read while one is set. Changes are never previewed.
 */
export const asOfInterceptor: HttpInterceptorFn = (req, next) => {
  const session = inject(Session);
  const asOf = session.asOf();
  if (req.context.get(LIVE) || !req.url.startsWith('/api/sheets/')) return next(req);
  if (asOf !== null) return next(req.clone({ setHeaders: { 'X-As-Of': asOf } }));
  const preview = session.preview();
  if (preview === null || req.method !== 'GET') return next(req);
  return next(req.clone({ setHeaders: { 'X-Preview': preview } }));
};
