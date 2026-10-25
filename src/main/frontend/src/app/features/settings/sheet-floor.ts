import { Injectable, signal } from '@angular/core';

/** The floor of a sheet that has not set one, in cents. */
export const DEFAULT_FLOOR_CENTS = 100;

// The loaded sheet's floor, the least a claim's live due can be, shared by the screens that show due.
@Injectable({ providedIn: 'root' })
export class SheetFloor {
  /** The floor in cents. */
  readonly cents = signal(DEFAULT_FLOOR_CENTS);
}
