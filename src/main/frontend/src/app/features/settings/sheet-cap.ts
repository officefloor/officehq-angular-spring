import { Injectable, computed, inject, signal } from '@angular/core';
import { Session } from '../../core/session';
import { DatedValue, valueOn } from './dated';

// The loaded sheet's cap, the most a person's due-total can be, shared by the screens that show due.
// It is dated: the cap is the one in force on the sheet's today.
@Injectable({ providedIn: 'root' })
export class SheetCap {
  private readonly session = inject(Session);

  /** The cap's values from their from-dates, in date order. */
  readonly history = signal<readonly DatedValue[]>([]);

  /** The cap in force on the sheet's today in cents, or null when there is no cap. */
  readonly cents = computed(() => {
    const value = valueOn(this.history(), this.session.sheet()?.today);
    return value === null ? null : Math.round(Number(value) * 100);
  });
}
