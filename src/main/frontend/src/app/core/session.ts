import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export interface SheetView {
  id: string;
  /** The sheet's today (yyyy-mm-dd): every date decision and default date uses it, never the real clock. */
  today: string;
  /** An archived sheet stays visible, and every change on it is refused. */
  archived: boolean;
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

  readonly user = this.userState.asReadonly();
  readonly sheet = this.sheetState.asReadonly();
  /** The sheet as it was when it was last loaded: changes on each load, but not on a change of its
   *  today, so what is kept per sheet is re-read from the store only when a sheet is loaded. */
  readonly loaded = this.loadState.asReadonly();
  /** The date this screen shows every sheet as of (yyyy-mm-dd), or null for as it is now. It
   *  belongs to this screen only: it is sent with each request, and every change is refused while
   *  it is set. */
  readonly asOf = this.asOfState.asReadonly();

  signIn(name: string): void {
    this.userState.set(name);
  }

  /** Load a sheet by id, re-read from the store; a sheet never loaded before starts empty. */
  async loadSheet(id: string): Promise<void> {
    const sheet = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/load`, null),
    );
    this.sheetState.set(sheet);
    this.loadState.set(sheet);
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

  /** Change the active sheet's today; the store's answer replaces what the screen holds. */
  async setToday(today: string): Promise<void> {
    const current = this.sheetState();
    if (!current) throw new Error('No sheet is loaded.');
    const sheet = await firstValueFrom(
      this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(current.id)}/today`, { today }),
    );
    if (this.sheetState()?.id === sheet.id) this.sheetState.set(sheet);
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
}
