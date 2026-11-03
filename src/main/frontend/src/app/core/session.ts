import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

// The session: who is signed in (a name, no password) and which sheet is active. Everything else
// in the app is kept per sheet, so features read `sheet()` to know what to show, and `today()` for
// the sheet's today: every date-dependent decision and default date uses it, never the real clock.
interface SheetResponse {
  id: string;
  today: string;
}

@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheet = signal<string | null>(null);
  readonly today = signal<string | null>(null);
  /** Counts sheet loads, so features re-read the store each time a sheet is (re-)loaded. */
  readonly loads = signal(0);

  async signIn(name: string): Promise<void> {
    const res = await firstValueFrom(this.http.post<{ name: string }>('/api/signin', { name }));
    this.user.set(res.name);
    this.sheet.set(null);
    this.today.set(null);
  }

  async loadSheet(id: string): Promise<void> {
    const res = await firstValueFrom(
      this.http.post<SheetResponse>('/api/sheets/load', { id, user: this.user() }),
    );
    this.sheet.set(res.id);
    this.today.set(res.today);
    this.loads.update((n) => n + 1);
  }

  async setToday(today: string): Promise<void> {
    const res = await firstValueFrom(
      this.http.post<SheetResponse>('/api/sheets/today', { id: this.sheet(), user: this.user(), today }),
    );
    this.sheet.set(res.id);
    this.today.set(res.today);
  }
}
