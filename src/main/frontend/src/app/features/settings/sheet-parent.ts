import { Injectable, signal } from '@angular/core';

// The loaded sheet's parent, whose rates (then its parent's, up to three levels up) are used where the
// sheet has none in force, shared by the screens that work out dues. It is not dated.
@Injectable({ providedIn: 'root' })
export class SheetParent {
  /** The parent sheet's id, or null when the sheet has none. */
  readonly parent = signal<string | null>(null);
  /** Counts the changes of the parent saved on this screen, so the rates the dues use are re-read. */
  readonly changes = signal(0);
}
