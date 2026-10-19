import { Injectable, computed, signal } from '@angular/core';
import { Dated, valueOn } from './dated';

/** The floor a sheet starts with, in force before every from-date. */
export const DEFAULT_FLOOR = '1.00';

// The active sheet's floor, the least a claim's live due comes to, dated: set by the settings when they are
// read or saved, and read wherever a due is shown, as the floor in force on a claim's date.
@Injectable({ providedIn: 'root' })
export class SheetFloor {
  private readonly history = signal<readonly Dated[]>([]);
  /** The floor in force on a date. */
  readonly on = computed(() => {
    const history = this.history();
    return (date: string): string => valueOn(history, date, DEFAULT_FLOOR) ?? DEFAULT_FLOOR;
  });

  set(history: readonly Dated[]): void {
    this.history.set(history);
  }
}
