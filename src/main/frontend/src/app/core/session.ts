import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export interface SheetView {
  id: string;
  /** The sheet's today (yyyy-mm-dd): every date-dependent decision and default date uses it. */
  today: string;
  /** The sheet's floor with two decimals: a claim's live due is at least it. */
  floor: string;
  /** The sheet's terms in days: a claim is due by its date plus them. */
  terms: number;
}

// The signed-in user (a typed name, no password) and the active sheet. Everything else in the
// app is kept per sheet, so features read the active sheet id from here.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheet = signal<string | null>(null);
  /** The active sheet's today; features use it, never the real clock. */
  readonly today = signal<string | null>(null);
  /** The active sheet's floor with two decimals: a claim's live due is at least it. */
  readonly floor = signal('1.00');
  /** The active sheet's terms in days: a claim is due by its date plus them. */
  readonly terms = signal(14);
  /** Counts sheet loads, so features re-read the store even when the same id is loaded again. */
  readonly loads = signal(0);

  signIn(name: string): void {
    this.user.set(name);
  }

  /** Load a sheet by id from the store; an id never used before starts an empty sheet. */
  async loadSheet(id: string): Promise<void> {
    const view = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/load`, null),
    );
    this.show(view);
    this.loads.update((n) => n + 1);
  }

  /** Change the active sheet's today in the store. */
  async setToday(date: string): Promise<void> {
    const id = this.sheet();
    if (!id) throw new Error('No sheet is loaded.');
    const view = await firstValueFrom(
      this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/today`, { today: date }),
    );
    this.show(view);
  }

  private show(view: SheetView): void {
    this.sheet.set(view.id);
    this.today.set(view.today);
    this.floor.set(view.floor);
    this.terms.set(view.terms);
  }
}
