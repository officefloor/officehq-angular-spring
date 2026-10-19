import { Injectable, computed, inject, signal } from '@angular/core';
import { Session } from '../../core/session';
import { Dated, valueOn } from './dated';

// The active sheet's cap, the most a person's due-total comes to (null for none), dated: set by the settings
// when they are read or saved, and read wherever a due-total is shown, as the cap in force on the sheet's today.
@Injectable({ providedIn: 'root' })
export class SheetCap {
  private readonly session = inject(Session);
  private readonly history = signal<readonly Dated[]>([]);
  /** The cap in force on the sheet's today. */
  readonly value = computed(() => {
    const today = this.session.today();
    return today === null ? null : valueOn(this.history(), today, null);
  });

  set(history: readonly Dated[]): void {
    this.history.set(history);
  }
}
