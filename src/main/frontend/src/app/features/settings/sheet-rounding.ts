import { Injectable, signal } from '@angular/core';
import { DatedValue, valueOn } from './dated';

/** The rounding modes a sheet may set. */
export const ROUNDING_MODES = ['half-up', 'half-even'] as const;
export type RoundingMode = (typeof ROUNDING_MODES)[number];

// The loaded sheet's rounding mode, shared by the screens that show due. It is dated: a claim dated while
// one is in force rounds its live figures by it; with none in force the policy date decides.
@Injectable({ providedIn: 'root' })
export class SheetRounding {
  /** The rounding mode's values from their from-dates, in date order. */
  readonly history = signal<readonly DatedValue[]>([]);

  /** The rounding mode in force on the date, or null when there is none then. */
  modeOn(date: string | undefined): RoundingMode | null {
    const value = valueOn(this.history(), date);
    return ROUNDING_MODES.find((m) => m === value) ?? null;
  }
}
