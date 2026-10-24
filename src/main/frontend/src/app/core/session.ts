import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

interface SheetView {
  id: string;
  /** The sheet's today (YYYY-MM-DD): every date-dependent decision and default uses it. */
  today: string;
  /** Whether the sheet is archived: everything stays visible and every change is refused. */
  archived: boolean;
  /** The number of the sheet's last settle batch (a bulk settle that settled a claim); null while it
   *  has had none. */
  lastSettleBatch: number | null;
}

// Who is signed in and which sheet is active. Every request to the server carries the signed-in
// name in the X-User header (see `headers()`); everything else in the app is kept per sheet. While an
// as-of date is set, every request also carries it in the X-As-Of header: the server then shows each
// sheet as it stood at the end of that date (that date standing in for its today) and refuses every
// change. The as-of date belongs to this screen alone.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheetId = signal<string | null>(null);
  /** The active sheet's today (YYYY-MM-DD); use it, never the browser's clock. While an as-of date is
   *  set, it is that date. */
  readonly today = signal<string | null>(null);
  /** Whether the active sheet is archived. */
  readonly archived = signal(false);
  /** The number of the active sheet's last settle batch; null while it has had none. */
  readonly lastSettleBatch = signal<number | null>(null);
  /** The date (YYYY-MM-DD) the sheets are shown as of; null when they are shown as they stand. */
  readonly asOf = signal<string | null>(null);
  /** Counts sheet loads, so a view re-reads the store even when the same id is loaded again. */
  readonly loads = signal(0);
  readonly signedIn = computed(() => this.user() !== null);

  signIn(name: string): boolean {
    const user = name.trim();
    if (!user) return false;
    this.user.set(user);
    this.sheetId.set(null);
    this.today.set(null);
    this.archived.set(false);
    this.lastSettleBatch.set(null);
    this.asOf.set(null);
    return true;
  }

  headers(): HttpHeaders {
    const asOf = this.asOf();
    return new HttpHeaders(asOf ? { 'X-User': this.user() ?? '', 'X-As-Of': asOf } : { 'X-User': this.user() ?? '' });
  }

  /** Show the sheets as of a date (YYYY-MM-DD), re-reading the active one; resolves false when the
   *  date is not one. */
  async setAsOf(date: string): Promise<boolean> {
    const asOf = date.trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(asOf)) return false;
    this.asOf.set(asOf);
    const sheet = this.sheetId();
    return sheet ? this.loadSheet(sheet) : true;
  }

  /** Show the sheets as they stand again, re-reading the active one. */
  async clearAsOf(): Promise<boolean> {
    this.asOf.set(null);
    const sheet = this.sheetId();
    return sheet ? this.loadSheet(sheet) : true;
  }

  /** Load a sheet by id (starting it empty if never used); resolves false when refused. */
  async loadSheet(id: string): Promise<boolean> {
    const sheet = id.trim();
    if (!sheet || !this.user()) return false;
    try {
      const view = await firstValueFrom(
        this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(sheet)}/load`, null, {
          headers: this.headers(),
        }),
      );
      this.show(view);
      this.loads.update((n) => n + 1);
      return true;
    } catch {
      return false;
    }
  }

  /** Change the active sheet's today; resolves false when refused. */
  async setToday(date: string): Promise<boolean> {
    const sheet = this.sheetId();
    const today = date.trim();
    if (!sheet || !/^\d{4}-\d{2}-\d{2}$/.test(today)) return false;
    try {
      const view = await firstValueFrom(
        this.http.put<SheetView>(
          `/api/sheets/${encodeURIComponent(sheet)}/today`,
          { today },
          { headers: this.headers() },
        ),
      );
      if (this.sheetId() === view.id) this.show(view);
      return true;
    } catch {
      return false;
    }
  }

  /** Archive the active sheet; resolves false when refused. */
  async archive(): Promise<boolean> {
    const sheet = this.sheetId();
    if (!sheet) return false;
    try {
      const view = await firstValueFrom(
        this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(sheet)}/archive`, null, {
          headers: this.headers(),
        }),
      );
      if (this.sheetId() === view.id) this.show(view);
      return true;
    } catch {
      return false;
    }
  }

  private show(view: SheetView): void {
    this.sheetId.set(view.id);
    this.today.set(view.today);
    this.archived.set(view.archived);
    this.lastSettleBatch.set(view.lastSettleBatch);
  }
}
