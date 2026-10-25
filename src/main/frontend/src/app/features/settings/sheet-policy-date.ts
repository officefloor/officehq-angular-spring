import { Injectable, signal } from '@angular/core';

// The loaded sheet's policy date, which decides how a claim's figures round (half up when the claim is
// dated before it, half to even on or after it), shared by the screens that show due.
@Injectable({ providedIn: 'root' })
export class SheetPolicyDate {
  /** The policy date the sheet set (yyyy-mm-dd), or null when blank: the sheet's today then. */
  readonly date = signal<string | null>(null);
}
