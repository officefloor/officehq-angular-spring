import { Injectable, signal } from '@angular/core';

// The active sheet's entry date (yyyy-mm-dd), null when off: while set, a new claim is dated it by default instead
// of the sheet's today. Set by the settings when they are read or saved, and read by the claim form.
@Injectable({ providedIn: 'root' })
export class SheetEntryDate {
  private readonly _value = signal<string | null>(null);
  readonly value = this._value.asReadonly();

  set(entryDate: string | null): void {
    this._value.set(entryDate);
  }
}
