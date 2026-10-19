import { Injectable, computed, signal } from '@angular/core';
import { Dated, valueOn } from './dated';

/** The late percent a sheet starts with, in force before every from-date. */
export const DEFAULT_LATE_PCT = '0.00';

// The active sheet's late percent, what an overdue claim gains of its figure for every full 30 days it is
// overdue, dated: set by the settings when they are read or saved, and read wherever a due is shown, as the
// late percent in force on a claim's date.
@Injectable({ providedIn: 'root' })
export class SheetLatePct {
  private readonly history = signal<readonly Dated[]>([]);
  /** The late percent in force on a date. */
  readonly on = computed(() => {
    const history = this.history();
    return (date: string): string => valueOn(history, date, DEFAULT_LATE_PCT) ?? DEFAULT_LATE_PCT;
  });

  set(history: readonly Dated[]): void {
    this.history.set(history);
  }
}
