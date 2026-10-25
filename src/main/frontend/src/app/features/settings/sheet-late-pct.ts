import { Injectable, signal } from '@angular/core';
import { DatedValue, valueOn } from './dated';

/** The late percent of a sheet that has not set one. */
export const DEFAULT_LATE_PCT = '0.00';

// The loaded sheet's late percent, what an overdue claim gains of its figure for every full 30 days
// overdue, shared by the screens that show due. It is dated: each 30-day step of a claim's charge takes the
// percent in force on that step's boundary date.
@Injectable({ providedIn: 'root' })
export class SheetLatePct {
  /** The late percent's values from their from-dates, in date order. */
  readonly history = signal<readonly DatedValue[]>([]);

  /** The percent in force on the date, with two decimals; the starting percent before every from-date. */
  pctOn(date: string | undefined): string {
    return valueOn(this.history(), date) ?? DEFAULT_LATE_PCT;
  }
}
