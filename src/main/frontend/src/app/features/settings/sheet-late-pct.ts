import { Injectable, signal } from '@angular/core';

/** The late percent of a sheet that has not set one. */
export const DEFAULT_LATE_PCT = '0.00';

// The loaded sheet's late percent, what an overdue claim gains of its figure for every full 30 days
// overdue, shared by the screens that show due.
@Injectable({ providedIn: 'root' })
export class SheetLatePct {
  /** The percent with two decimals. */
  readonly pct = signal(DEFAULT_LATE_PCT);
}
