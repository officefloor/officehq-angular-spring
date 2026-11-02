import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

interface SignedIn {
  name: string;
}

interface SheetView {
  id: string;
  today: string;
  archived: boolean;
}

// Who is signed in and which sheet is active. Everything else in the app is kept per sheet.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheetId = signal<string | null>(null);
  /** The active sheet's today (yyyy-mm-dd) as the store holds it. */
  readonly sheetToday = signal<string | null>(null);
  /** The day (yyyy-mm-dd) this user's screen shows every sheet as it stood at the end of; null when
   *  it shows the sheet as it is now. While it is set every change is refused. */
  readonly asOf = signal<string | null>(null);
  /** The day every date decision and default date uses: the as-of day when one is set, or else the
   *  sheet's today. */
  readonly today = computed(() => this.asOf() ?? this.sheetToday());
  /** Whether the active sheet is archived: it stays visible and refuses every change. */
  readonly archived = signal(false);
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
    this.sheetToday.set(null);
    this.asOf.set(null);
    this.archived.set(false);
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

  /** Archive the active sheet (a steward only). */
  async archive(): Promise<void> {
    const user = this.user();
    const id = this.sheetId();
    if (!user || !id) throw new Error('no sheet loaded');
    const res = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/archive`, null, {
        headers: { 'X-User': user },
      }),
    );
    this.show(res);
  }

  /** Show every sheet as it stood at the end of a day (null: as it is now), re-reading it. */
  setAsOf(day: string | null): void {
    this.asOf.set(day);
    this.bumpLoads();
  }

  private show(sheet: SheetView): void {
    this.sheetId.set(sheet.id);
    this.sheetToday.set(sheet.today);
    this.archived.set(sheet.archived);
  }

  private bumpLoads(): void {
    this.loads.update((n) => n + 1);
  }
}
