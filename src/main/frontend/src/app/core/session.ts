import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export interface SheetView {
  id: string;
  /** The sheet's today (yyyy-mm-dd): every date decision and default date uses it, never the real clock. */
  today: string;
}

// Who is signed in (a typed name, no password) and which sheet is active. Everything else in the
// app is kept per sheet and reads the active sheet id from here.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  private readonly userState = signal<string | null>(null);
  private readonly sheetState = signal<SheetView | null>(null);

  readonly user = this.userState.asReadonly();
  readonly sheet = this.sheetState.asReadonly();

  signIn(name: string): void {
    this.userState.set(name);
  }

  /** Load a sheet by id, re-read from the store; a sheet never loaded before starts empty. */
  async loadSheet(id: string): Promise<void> {
    const sheet = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/load`, null),
    );
    this.sheetState.set(sheet);
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
}
