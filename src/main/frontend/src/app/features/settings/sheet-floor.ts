import { Injectable, signal } from '@angular/core';

/** The floor a sheet starts with. */
export const DEFAULT_FLOOR = '1.00';

// The active sheet's floor, the least a claim's live due comes to: set by the settings when they are read
// or saved, and read wherever a due is shown.
@Injectable({ providedIn: 'root' })
export class SheetFloor {
  private readonly _value = signal(DEFAULT_FLOOR);
  readonly value = this._value.asReadonly();

  set(floor: string): void {
    this._value.set(floor);
  }
}
