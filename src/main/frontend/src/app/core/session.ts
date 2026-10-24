import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

interface SheetView {
  id: string;
  /** The sheet's today (YYYY-MM-DD): every date-dependent decision and default uses it. */
  today: string;
}

// Who is signed in and which sheet is active. Every request to the server carries the signed-in
// name in the X-User header (see `headers()`); everything else in the app is kept per sheet.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheetId = signal<string | null>(null);
  /** The active sheet's today (YYYY-MM-DD); use it, never the browser's clock. */
  readonly today = signal<string | null>(null);
  /** Counts sheet loads, so a view re-reads the store even when the same id is loaded again. */
  readonly loads = signal(0);
  readonly signedIn = computed(() => this.user() !== null);

  signIn(name: string): boolean {
    const user = name.trim();
    if (!user) return false;
    this.user.set(user);
    this.sheetId.set(null);
    this.today.set(null);
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

  private show(view: SheetView): void {
    this.sheetId.set(view.id);
    this.today.set(view.today);
  }
}
