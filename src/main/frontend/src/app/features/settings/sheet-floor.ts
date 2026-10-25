import { Injectable, signal } from '@angular/core';
import { DatedValue, valueOn } from './dated';

/** The floor of a sheet that has not set one, in cents. */
export const DEFAULT_FLOOR_CENTS = 100;

// The loaded sheet's floor, the least a claim's live due can be, shared by the screens that show due.
// It is dated: a claim's floor is the one in force on the claim's date.
@Injectable({ providedIn: 'root' })
export class SheetFloor {
  /** The floor's values from their from-dates, in date order. */
  readonly history = signal<readonly DatedValue[]>([]);

  /** The floor in force on the date, in cents; the starting floor before every from-date. */
  centsOn(date: string | undefined): number {
    const value = valueOn(this.history(), date);
    return value === null ? DEFAULT_FLOOR_CENTS : Math.round(Number(value) * 100);
  }
}
