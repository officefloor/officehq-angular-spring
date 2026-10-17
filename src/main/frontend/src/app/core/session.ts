import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

interface SheetView {
  id: string;
  /** The sheet's "today" (yyyy-mm-dd): every date decision and default date uses it. */
  today: string;
  /** The most due a clerk can settle a claim for, two decimals; null for no limit. */
  clerkLimit: string | null;
  /** The least any live due can be, two decimals. */
  floor: string;
}

// The signed-in user (a typed name, no password) and the active sheet. Everything else in the app
// is kept per sheet.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheetId = signal<string | null>(null);
  /** The active sheet's "today" — use this, never the real clock, for dates in the sheet. */
  readonly today = signal<string | null>(null);
  /** The active sheet's clerk limit (two decimals); null for no limit. */
  readonly clerkLimit = signal<string | null>(null);
  /** The active sheet's floor (two decimals): every live due is at least it. */
  readonly floor = signal<string | null>(null);
  /** Counts sheet loads, so per-sheet data re-reads the store on every (re-)load. */
  readonly loads = signal(0);
  /** True while a sheet is being (re-)loaded, so nothing read from the store before is shown. */
  readonly loading = signal(false);

  signIn(name: string): void {
    this.user.set(name.trim());
  }

  /** Load (or re-load) a sheet by id; a never-used id starts an empty sheet. */
  async loadSheet(id: string): Promise<void> {
    const user = this.user();
    if (!user) throw new Error('not signed in');
    this.loading.set(true);
    try {
      const sheet = await firstValueFrom(
        this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id.trim())}/load`, null, {
          headers: { 'X-User': user },
        }),
      );
      this.show(sheet);
      this.loads.update((n) => n + 1);
    } finally {
      this.loading.set(false);
    }
  }

  /** Change the active sheet's "today". */
  async setToday(today: string): Promise<void> {
    const user = this.user();
    const id = this.sheetId();
    if (!user || !id) throw new Error('no sheet loaded');
    const sheet = await firstValueFrom(
      this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/today`, { today }, {
        headers: { 'X-User': user },
      }),
    );
    this.show(sheet);
  }

  /** Change the active sheet's clerk limit (null for no limit) and floor. */
  async setSettings(clerkLimit: string | null, floor: string): Promise<void> {
    const user = this.user();
    const id = this.sheetId();
    if (!user || !id) throw new Error('no sheet loaded');
    const sheet = await firstValueFrom(
      this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/settings`, { clerkLimit, floor }, {
        headers: { 'X-User': user },
      }),
    );
    this.show(sheet);
  }

  private show(sheet: SheetView): void {
    this.sheetId.set(sheet.id);
    this.today.set(sheet.today);
    this.clerkLimit.set(sheet.clerkLimit);
    this.floor.set(sheet.floor);
  }
}
