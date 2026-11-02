import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

interface SignedIn {
  name: string;
}

interface SheetView {
  id: string;
  today: string;
}

// Who is signed in and which sheet is active. Everything else in the app is kept per sheet.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheetId = signal<string | null>(null);
  /** The active sheet's today (yyyy-mm-dd): every date decision and default date uses it. */
  readonly today = signal<string | null>(null);
  /** The sheet whose people and claims are on screen: set once a load of them has been read, so the
   *  sheet shows as loaded only when its rows are there to act on. */
  readonly shownId = signal<string | null>(null);
  /** Bumped on every sheet load, so what is kept per sheet is re-read from the store. */
  readonly loads = signal(0);

  async signIn(name: string): Promise<void> {
    const res = await firstValueFrom(this.http.post<SignedIn>('/api/signin', { name }));
    this.user.set(res.name);
    this.sheetId.set(null);
    this.shownId.set(null);
    this.today.set(null);
  }

  /** Record that the sheet's people and claims, as read for its latest load, are on screen. */
  markShown(id: string): void {
    if (this.sheetId() === id) this.shownId.set(id);
  }

  /** Load a sheet by id; one never loaded before starts empty. */
  async loadSheet(id: string): Promise<void> {
    const user = this.user();
    if (!user) throw new Error('not signed in');
    const res = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/load`, null, {
        headers: { 'X-User': user },
      }),
    );
    this.show(res);
    this.bumpLoads();
  }

  /** Change the active sheet's today. */
  async setToday(today: string): Promise<void> {
    const user = this.user();
    const id = this.sheetId();
    if (!user || !id) throw new Error('no sheet loaded');
    const res = await firstValueFrom(
      this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/today`, { today }, {
        headers: { 'X-User': user },
      }),
    );
    this.show(res);
  }

  private show(sheet: SheetView): void {
    this.sheetId.set(sheet.id);
    this.today.set(sheet.today);
  }

  private bumpLoads(): void {
    this.loads.update((n) => n + 1);
  }
}
