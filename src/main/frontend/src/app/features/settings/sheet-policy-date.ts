import { Injectable, signal } from '@angular/core';

// The active sheet's policy date (yyyy-mm-dd), null for none (the sheet's today): a claim dated before it rounds
// half up, one dated on or after it half to even. Set by the settings when they are read or saved, and read
// wherever a due is shown.
@Injectable({ providedIn: 'root' })
export class SheetPolicyDate {
  private readonly _value = signal<string | null>(null);
  readonly value = this._value.asReadonly();

  set(policyDate: string | null): void {
    this._value.set(policyDate);
  }
}
