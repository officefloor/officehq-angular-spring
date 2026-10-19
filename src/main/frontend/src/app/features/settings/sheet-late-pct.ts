import { Injectable, signal } from '@angular/core';

/** The late percent a sheet starts with. */
export const DEFAULT_LATE_PCT = '0.00';

// The active sheet's late percent, what an overdue claim gains of its figure for every full 30 days it is
// overdue: set by the settings when they are read or saved, and read wherever a due is shown.
@Injectable({ providedIn: 'root' })
export class SheetLatePct {
  private readonly _value = signal(DEFAULT_LATE_PCT);
  readonly value = this._value.asReadonly();

  set(latePct: string): void {
    this._value.set(latePct);
  }
}
