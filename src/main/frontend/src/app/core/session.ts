import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

// The session: who is signed in (a name, no password) and which sheet is active. Everything else
// in the app is kept per sheet, so features read `sheet()` to know what to show, and `today()` for
// the sheet's today: every date-dependent decision and default date uses it, never the real clock.
interface SheetResponse {
  id: string;
  today: string;
  archived: boolean;
  lastBatch: number | null;
}

@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheet = signal<string | null>(null);
  readonly today = signal<string | null>(null);
  /** Whether the sheet is archived: it stays visible and every change to it is refused. */
  readonly archived = signal(false);
  /** The number of the sheet's last settle batch (a bulk settle that settled a claim); none when there has been none. */
  readonly lastBatch = signal<number | null>(null);
  /** The id of the sheet's parent, whose rates stand in for a grade with none in force here; blank for none.
   *  Set from the sheet's settings as they are read and saved. */
  readonly parent = signal('');
  /** Whether retired people are listed: off when the screen opens, and left as it is by loading a sheet. */
  readonly showRetired = signal(false);
  /**
   * The date this screen shows the sheet as of (none: as it is now). Sent with every request
   * (see asOfInterceptor): the store answers as the sheet stood at the end of that date, its today
   * the date, and refuses every change. Kept when another sheet is loaded.
   */
  readonly asOf = signal<string | null>(null);
  /** Counts sheet loads, so features re-read the store each time a sheet is (re-)loaded. */
  readonly loads = signal(0);

  async signIn(name: string): Promise<void> {
    const res = await firstValueFrom(this.http.post<{ name: string }>('/api/signin', { name }));
    this.user.set(res.name);
    this.sheet.set(null);
    this.today.set(null);
    this.archived.set(false);
    this.lastBatch.set(null);
    this.asOf.set(null);
  }

  async loadSheet(id: string): Promise<void> {
    const res = await firstValueFrom(
      this.http.post<SheetResponse>('/api/sheets/load', { id, user: this.user() }),
    );
    this.show(res);
    this.loads.update((n) => n + 1);
  }

  /** Show the active sheet as of a date, re-read from the store. */
  async setAsOf(date: string): Promise<void> {
    this.asOf.set(date);
    const id = this.sheet();
    if (id) await this.loadSheet(id);
  }

  /** Show the active sheet as it is now, re-read from the store. */
  async clearAsOf(): Promise<void> {
    this.asOf.set(null);
    const id = this.sheet();
    if (id) await this.loadSheet(id);
  }

  async setToday(today: string): Promise<void> {
    const res = await firstValueFrom(
      this.http.post<SheetResponse>('/api/sheets/today', { id: this.sheet(), user: this.user(), today }),
    );
    this.show(res);
  }

  /** Archive the active sheet (a steward only). */
  async archive(): Promise<void> {
    const res = await firstValueFrom(
      this.http.post<SheetResponse>('/api/sheets/archive', { id: this.sheet(), user: this.user() }),
    );
    this.show(res);
  }

  /** Clone the active sheet to another id never used (a steward only); answers the clone's id. */
  async cloneSheet(to: string): Promise<string> {
    const res = await firstValueFrom(
      this.http.post<SheetResponse>('/api/sheets/clone', { id: this.sheet(), user: this.user(), to }),
    );
    return res.id;
  }

  private show(res: SheetResponse): void {
    if (res.id !== this.sheet()) this.parent.set('');
    this.sheet.set(res.id);
    this.today.set(res.today);
    this.archived.set(res.archived);
    this.lastBatch.set(res.lastBatch);
  }
}
