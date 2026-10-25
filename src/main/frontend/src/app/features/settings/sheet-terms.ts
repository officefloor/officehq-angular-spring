import { Injectable, signal } from '@angular/core';

/** The terms of a sheet that has not set them, in days. */
export const DEFAULT_TERMS = 14;

// The loaded sheet's terms, the days after a claim's date it is due by, shared by the screens that
// show due-by and overdue.
@Injectable({ providedIn: 'root' })
export class SheetTerms {
  /** The terms in days. */
  readonly days = signal(DEFAULT_TERMS);
}
