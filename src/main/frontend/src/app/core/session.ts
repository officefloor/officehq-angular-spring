import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

interface SheetView {
  id: string;
}

// Who is signed in and which sheet is active. Every request to the server carries the signed-in
// name in the X-User header (see `headers()`); everything else in the app is kept per sheet.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheetId = signal<string | null>(null);
  readonly signedIn = computed(() => this.user() !== null);

  signIn(name: string): boolean {
    const user = name.trim();
    if (!user) return false;
    this.user.set(user);
    this.sheetId.set(null);
    return true;
  }

  headers(): HttpHeaders {
    return new HttpHeaders({ 'X-User': this.user() ?? '' });
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
      this.sheetId.set(view.id);
      return true;
    } catch {
      return false;
    }
  }
}
