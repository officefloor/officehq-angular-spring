import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { finalize } from 'rxjs';
import { Session } from './session';

/** Counts the API requests still waiting for their answer, so an export can wait for the screen's reads to settle. */
export const inFlightInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api/')) return next(req);
  const session = inject(Session);
  session.requestStarted();
  return next(req).pipe(finalize(() => session.requestEnded()));
};
