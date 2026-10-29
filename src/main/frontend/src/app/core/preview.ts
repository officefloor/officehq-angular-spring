import { HttpInterceptorFn } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';

// The date this user's screen previews the sheet on (yyyy-mm-dd), or null for none. It belongs to the screen: other
// users are unaffected.
@Injectable({ providedIn: 'root' })
export class Preview {
  readonly date = signal<string | null>(null);
}

// Sends the preview date with every request, so the server answers each read as if the sheet's today were that date;
// a change ignores it and uses the sheet's real today.
export const previewInterceptor: HttpInterceptorFn = (req, next) => {
  const date = inject(Preview).date();
  return next(date ? req.clone({ setHeaders: { 'X-Preview': date } }) : req);
};
