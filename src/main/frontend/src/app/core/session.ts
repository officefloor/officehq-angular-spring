import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

interface SheetView {
  id: string;
  today: string;
  floor: string;
  terms: number;
  latePct: string;
}

/** What a claim's late charge depends on: the sheet's late percent, terms and today. */
export interface Late {
  pct: string;
  terms: number;
  today: string | null;
}

// The signed-in user and the active sheet. Every other feature reads these to scope its calls.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheetId = signal<string | null>(null);
  /** The sheet's today (yyyy-mm-dd): every date decision and default date uses it, never the clock. */
  readonly today = signal<string | null>(null);
  /** The sheet's floor, two decimals: a claim's live due is at least it. */
  readonly floor = signal('1.00');
  /** The sheet's terms in days: a claim's due-by is its date plus them. */
  readonly terms = signal(14);
  /** The sheet's late percent, two decimals: an overdue claim's due gains it of its figure for every full 30 days overdue. */
  readonly latePct = signal('0.00');
  /** The late percent, terms and today together, as a claim's late charge needs them. */
  readonly late = computed<Late>(() => ({ pct: this.latePct(), terms: this.terms(), today: this.today() }));
  /** Bumped on every sheet load, so features re-read the sheet from the store even for the same id. */
  readonly loads = signal(0);
  /** Whether retired people are listed: off when the screen opens, left as it is by loading a sheet. */
  readonly showRetired = signal(false);

  async signIn(name: string): Promise<void> {
    const res = await firstValueFrom(this.http.post<{ name: string }>('/api/session', { name }));
    this.user.set(res.name);
  }

  async loadSheet(id: string): Promise<void> {
    const user = this.user();
    if (!user) throw new Error('not signed in');
    const res = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/load`, null, {
        headers: { 'X-User': user },
      }),
    );
    this.sheetId.set(res.id);
    this.today.set(res.today);
    this.floor.set(res.floor);
    this.terms.set(res.terms);
    this.latePct.set(res.latePct);
    this.loads.update((n) => n + 1);
  }

  async setToday(today: string): Promise<void> {
    const user = this.user();
    const id = this.sheetId();
    if (!user || !id) throw new Error('no sheet loaded');
    const res = await firstValueFrom(
      this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/today`, { today }, {
        headers: { 'X-User': user },
      }),
    );
    if (this.sheetId() === res.id) this.today.set(res.today);
  }
}
