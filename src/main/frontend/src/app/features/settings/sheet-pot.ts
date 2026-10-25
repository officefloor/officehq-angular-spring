import { Injectable, signal } from '@angular/core';

// The loaded sheet's pot, which each balanced pool shares out among its members by their shares,
// shared by the screens that show the cuts. It is not dated.
@Injectable({ providedIn: 'root' })
export class SheetPot {
  /** The pot the sheet set, with two decimals, or null when blank: no pot. */
  readonly pot = signal<string | null>(null);
}
