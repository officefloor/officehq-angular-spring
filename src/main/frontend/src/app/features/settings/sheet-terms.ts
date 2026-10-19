import { Injectable, signal } from '@angular/core';

/** The terms a sheet starts with, in days. */
export const DEFAULT_TERMS = 14;

// The active sheet's terms, the days after its date a claim is due by: set by the settings when they are read
// or saved, and read wherever a due-by is shown.
@Injectable({ providedIn: 'root' })
export class SheetTerms {
  private readonly _value = signal(DEFAULT_TERMS);
  readonly value = this._value.asReadonly();

  set(terms: number): void {
    this._value.set(terms);
  }
}
