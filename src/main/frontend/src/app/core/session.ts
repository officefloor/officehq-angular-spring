import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

// Who is signed in and which sheet is active. Everything else in the app is kept per sheet.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheetId = signal<string | null>(null);
  /** The active sheet's today (yyyy-mm-dd): every date decision and default date uses it. */
  readonly today = signal<string | null>(null);
  /** Whether the people list shows retired people too: off at first, and kept as it is when a
   *  sheet is loaded. */
  readonly showRetired = signal(false);
  /** Bumped on every sheet load, so a load of the same id again re-reads what the sheet holds. */
  readonly loads = signal(0);

  /** Sign in by name; resolves to an error message, or null on success. */
  async signIn(name: string): Promise<string | null> {
    try {
      const res = await firstValueFrom(this.http.post<{ name: string }>('/api/session', { name }));
      this.user.set(res.name);
      return null;
    } catch (e) {
      return message(e, 'Enter a name to sign in.');
    }
  }

  /** Load a sheet by id (a new id starts an empty sheet); resolves to an error message, or null. */
  async loadSheet(id: string): Promise<string | null> {
    const user = this.user();
    if (!user) return 'Sign in first.';
    try {
      const res = await firstValueFrom(
        this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/load`, null, {
          headers: { 'X-User': user },
        }),
      );
      this.sheetId.set(res.id);
      this.today.set(res.today);
      this.loads.update((n) => n + 1);
      return null;
    } catch (e) {
      return message(e, 'Enter a sheet id without spaces.');
    }
  }

  /** Change the active sheet's today; resolves to an error message, or null. */
  async setToday(today: string): Promise<string | null> {
    const user = this.user();
    const id = this.sheetId();
    if (!user || !id) return 'Load a sheet first.';
    try {
      const res = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/today`, { today }, {
          headers: { 'X-User': user },
        }),
      );
      if (this.sheetId() === res.id) this.today.set(res.today);
      return null;
    } catch (e) {
      return message(e, 'Enter a date as yyyy-mm-dd.');
    }
  }
}

interface SheetView {
  id: string;
  today: string;
}

function message(e: unknown, invalid: string): string {
  return e instanceof HttpErrorResponse && e.status === 400 ? invalid : 'Something went wrong; try again.';
}
