import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

interface SheetView {
  id: string;
}

// The signed-in user (a typed name, no password) and the active sheet. Everything else in the app
// is kept per sheet.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheetId = signal<string | null>(null);

  signIn(name: string): void {
    this.user.set(name.trim());
  }

  /** Load (or re-load) a sheet by id; a never-used id starts an empty sheet. */
  async loadSheet(id: string): Promise<void> {
    const user = this.user();
    if (!user) throw new Error('not signed in');
    const sheet = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id.trim())}/load`, null, {
        headers: { 'X-User': user },
      }),
    );
    this.sheetId.set(sheet.id);
  }
}
