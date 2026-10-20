import { HttpInterceptorFn } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';

// The date the signed-in user's screen shows the sheet as of (yyyy-mm-dd), or null for as it is now. It belongs to
// this screen only: every request carries it (the X-As-Of header), so the server answers each read from the sheet
// as it stood at the end of that date and refuses every change.
@Injectable({ providedIn: 'root' })
export class AsOf {
  private readonly _date = signal<string | null>(null);
  readonly date = this._date.asReadonly();

  set(date: string | null): void {
    this._date.set(date);
  }
}

export const asOfInterceptor: HttpInterceptorFn = (req, next) => {
  const date = inject(AsOf).date();
  return next(date ? req.clone({ setHeaders: { 'X-As-Of': date } }) : req);
};
