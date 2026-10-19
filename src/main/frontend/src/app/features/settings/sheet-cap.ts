import { Injectable, signal } from '@angular/core';

// The active sheet's cap, the most a person's due-total comes to (null for none): set by the settings when
// they are read or saved, and read wherever a due-total is shown.
@Injectable({ providedIn: 'root' })
export class SheetCap {
  private readonly _value = signal<string | null>(null);
  readonly value = this._value.asReadonly();

  set(cap: string | null): void {
    this._value.set(cap);
  }
}
