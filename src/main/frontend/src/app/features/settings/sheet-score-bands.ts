import { Injectable, signal } from '@angular/core';

// The loaded sheet's score thresholds, shared by the screens that show a person's band: while both are
// set, a score at or above the high one is HIGH, at or above the mid one MID, else LOW. They are not dated.
@Injectable({ providedIn: 'root' })
export class SheetScoreBands {
  /** The mid threshold, a whole number, or null when blank. */
  readonly mid = signal<number | null>(null);
  /** The high threshold, a whole number, or null when blank. */
  readonly high = signal<number | null>(null);
}
