import { HttpEvent, HttpInterceptorFn } from '@angular/common/http';
import { Observable, defer, finalize } from 'rxjs';

/** Settles once the last change sent so far has been answered. */
let lastChange: Promise<void> = Promise.resolve();

// Sends each change (any request but a read) only once the changes before it have been answered, so
// the store makes them, and records them in the audit file, in the order they were taken.
export const inOrderChangesInterceptor: HttpInterceptorFn = (req, next) => {
  if (req.method === 'GET' || req.method === 'HEAD') return next(req);
  const before = lastChange;
  let answered!: () => void;
  lastChange = new Promise<void>((resolve) => (answered = resolve));
  return new Observable<HttpEvent<unknown>>((subscriber) => {
    let sent = true;
    let inner: { unsubscribe(): void } | undefined;
    void before.then(() => {
      if (!sent) return;
      inner = defer(() => next(req)).pipe(finalize(answered)).subscribe(subscriber);
    });
    return () => {
      sent = false;
      if (inner) inner.unsubscribe();
      else void before.then(answered);
    };
  });
};
