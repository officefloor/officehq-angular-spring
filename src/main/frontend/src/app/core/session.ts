import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

interface SheetView {
  id: string;
}

// Who is signed in (a typed name, no password) and which sheet is active. Everything else in the
// app is kept per sheet, so features read the active sheet id from here.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheetId = signal<string | null>(null);

  signIn(name: string): boolean {
    const trimmed = name.trim();
    if (!trimmed) return false;
    this.user.set(trimmed);
    return true;
  }

  /** Load a sheet by id; a sheet never loaded before starts empty. */
  async loadSheet(id: string): Promise<void> {
    const sheet = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/load`, null),
    );
    this.sheetId.set(sheet.id);
  }
}
