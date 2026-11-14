import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

// Who is signed in and which sheet is active. Everything else in the app is kept per sheet.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheetId = signal<string | null>(null);

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
        this.http.post<{ id: string }>(`/api/sheets/${encodeURIComponent(id)}/load`, null, {
          headers: { 'X-User': user },
        }),
      );
      this.sheetId.set(res.id);
      return null;
    } catch (e) {
      return message(e, 'Enter a sheet id without spaces.');
    }
  }
}

function message(e: unknown, invalid: string): string {
  return e instanceof HttpErrorResponse && e.status === 400 ? invalid : 'Something went wrong; try again.';
}
