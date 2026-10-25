import { Injectable, signal } from '@angular/core';

// The loaded sheet's cap, the most a person's due-total can be, shared by the screens that show due.
@Injectable({ providedIn: 'root' })
export class SheetCap {
  /** The cap in cents, or null when there is no cap. */
  readonly cents = signal<number | null>(null);
}
