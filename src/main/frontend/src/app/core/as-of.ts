import { HttpContextToken, HttpInterceptorFn } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';

// The date this user's screen shows the sheet as of (yyyy-mm-dd), or null for as it stands now. It belongs to the
// screen: other users are unaffected.
@Injectable({ providedIn: 'root' })
export class AsOf {
  readonly date = signal<string | null>(null);
}

// Marks a request answered as the sheet stands now, whatever date the screen shows it as of.
export const LIVE = new HttpContextToken<boolean>(() => false);

// Sends the as-of date with every request (but one marked LIVE), so the server answers as of it and refuses every
// change.
export const asOfInterceptor: HttpInterceptorFn = (req, next) => {
  const date = req.context.get(LIVE) ? null : inject(AsOf).date();
  return next(date ? req.clone({ setHeaders: { 'X-As-Of': date } }) : req);
};
