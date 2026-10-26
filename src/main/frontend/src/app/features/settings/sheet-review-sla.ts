import { Injectable, signal } from '@angular/core';

// The loaded sheet's review SLA, shared by the screens that show review-late claims: a REVIEW claim whose
// review day (the sheet's today when it went to REVIEW) plus these days is before the sheet's today is
// review-late. It is not dated.
@Injectable({ providedIn: 'root' })
export class SheetReviewSla {
  /** The SLA in whole days, or null when blank: none. */
  readonly days = signal<number | null>(null);
}
