import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export interface SheetView {
  id: string;
  /** The sheet's today (yyyy-mm-dd): every date decision and default date uses it, never the real clock. */
  today: string;
  /** An archived sheet stays visible, and every change on it is refused. */
  archived: boolean;
  /** The number of the sheet's last bulk settle batch; null when it has had none. */
  lastSettleBatch: number | null;
  /** The role the signed-in user acts in on the sheet: clerk, steward, viewer or restricted. */
  role: string;
}

// Who is signed in (a typed name, no password) and which sheet is active. Everything else in the
// app is kept per sheet and reads the active sheet id from here.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  private readonly userState = signal<string | null>(null);
  private readonly sheetState = signal<SheetView | null>(null);
  private readonly loadState = signal<SheetView | null>(null);
  private readonly asOfState = signal<string | null>(null);
  private readonly previewState = signal<string | null>(null);
  /** The re-reads of the active sheet under way: the load itself, and what each load sets off. */
  private readonly reads = new Set<Promise<unknown>>();

  readonly user = this.userState.asReadonly();
  readonly sheet = this.sheetState.asReadonly();
  /** The sheet as it was when it was last loaded: changes on each load, but not on a change of its
   *  today, so what is kept per sheet is re-read from the store only when a sheet is loaded. */
  readonly loaded = this.loadState.asReadonly();
  /** The date this screen shows every sheet as of (yyyy-mm-dd), or null for as it is now. It
   *  belongs to this screen only: it is sent with each request, and every change is refused while
   *  it is set. */
  readonly asOf = this.asOfState.asReadonly();
  /** The date this screen shows every figure as of, as if it were the sheet's today (yyyy-mm-dd), or
   *  null for the sheet's today. It belongs to this screen only: it is sent with each read, over the
   *  sheet's current records; every change still uses the sheet's real today. */
  readonly preview = this.previewState.asReadonly();
  /** The today the screen's figures are worked out on: the preview date while one is set, else the
   *  sheet's today. Default dates and changes use the sheet's real today ({@link sheet}). */
  readonly figuresToday = computed(() => {
    const sheet = this.sheetState();
    return sheet ? (this.previewState() ?? sheet.today) : undefined;
  });

  signIn(name: string): void {
    this.userState.set(name);
  }

  /** Load a sheet by id, re-read from the store; a sheet never loaded before starts empty. */
  async loadSheet(id: string): Promise<void> {
    const sheet = await this.reading(firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/load`, null),
    ));
    this.sheetState.set(sheet);
    this.loadState.set(sheet);
  }

  /** Note a re-read of what the screen holds for the sheet, so {@link settled} waits for it. */
  reading<T>(read: Promise<T>): Promise<T> {
    this.reads.add(read);
    const done = () => this.reads.delete(read);
    read.then(done, done);
    return read;
  }

  /** Resolves once every re-read under way has answered, those a load just answered sets off included,
   *  so what the screen holds is the sheet as it now shows it (as of its as-of date, while one is set). */
  async settled(): Promise<void> {
    do {
      // Lets a load just answered set off its re-reads before they are waited for.
      await new Promise((resolve) => setTimeout(resolve));
      await Promise.allSettled([...this.reads]);
    } while (this.reads.size > 0);
  }

  /** Show the active sheet as of a date, or as it is now (null): it is re-read from the store. */
  async setAsOf(date: string | null): Promise<void> {
    const was = this.asOfState();
    this.asOfState.set(date);
    const current = this.sheetState();
    if (!current) return;
    try {
      await this.loadSheet(current.id);
    } catch (e) {
      this.asOfState.set(was);
      throw e;
    }
  }

  /** Show the active sheet's figures as if its today were a date, or as they are (null): it is re-read
   *  from the store. */
  async setPreview(date: string | null): Promise<void> {
    const was = this.previewState();
    this.previewState.set(date);
    const current = this.sheetState();
    if (!current) return;
    try {
      await this.loadSheet(current.id);
    } catch (e) {
      this.previewState.set(was);
      throw e;
    }
  }

  /** Change the active sheet's today; the store's answer replaces what the screen holds. */
  async setToday(today: string): Promise<void> {
    const current = this.sheetState();
    if (!current) throw new Error('No sheet is loaded.');
    const sheet = await firstValueFrom(
      this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(current.id)}/today`, { today }),
    );
    if (this.sheetState()?.id === sheet.id) this.sheetState.set(sheet);
  }

  /** Note a bulk settle batch made on the sheet: it is now the sheet's last. */
  settleBatchMade(sheetId: string, batch: number): void {
    const current = this.sheetState();
    if (current?.id === sheetId) this.sheetState.set({ ...current, lastSettleBatch: batch });
  }

  /** Archive the active sheet; the store's answer replaces what the screen holds. */
  async archive(): Promise<void> {
    const current = this.sheetState();
    if (!current) throw new Error('No sheet is loaded.');
    const sheet = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(current.id)}/archive`, null),
    );
    if (this.sheetState()?.id === sheet.id) this.sheetState.set(sheet);
  }

  /** Clone the active sheet to another id, which must never have been used; the id of the clone made. */
  async cloneSheet(to: string): Promise<string> {
    const current = this.sheetState();
    if (!current) throw new Error('No sheet is loaded.');
    const made = await firstValueFrom(
      this.http.post<{ to: string }>(`/api/sheets/${encodeURIComponent(current.id)}/clone`, { to }),
    );
    return made.to;
  }
}
