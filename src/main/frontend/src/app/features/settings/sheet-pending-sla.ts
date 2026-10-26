import { Injectable, signal } from '@angular/core';

// The loaded sheet's pending SLA, shared by the screens that show pending-late claims: a PENDING claim whose
// pending day (the sheet's today when it last went to PENDING) plus these days is before the sheet's today is
// pending-late. It is not dated.
@Injectable({ providedIn: 'root' })
export class SheetPendingSla {
  /** The SLA in whole days, or null when blank: none. */
  readonly days = signal<number | null>(null);
}
