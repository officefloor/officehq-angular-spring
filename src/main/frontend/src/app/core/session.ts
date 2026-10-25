import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export interface SheetView {
  id: string;
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
}
