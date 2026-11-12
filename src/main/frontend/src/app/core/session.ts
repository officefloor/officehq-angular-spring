import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export interface SheetView {
  id: string;
}

// The signed-in user (a typed name, no password) and the active sheet. Every request to the
// server carries the user's name in the X-User header.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheetId = signal<string | null>(null);

  signIn(name: string): boolean {
    const n = name.trim();
    if (!n) return false;
    this.user.set(n);
    return true;
  }

  async loadSheet(id: string): Promise<string | null> {
    const user = this.user();
    const sid = id.trim();
    if (!user) return 'Sign in first.';
    if (!sid) return 'Enter a sheet id.';
    try {
      const sheet = await firstValueFrom(
        this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/load`, null, {
          headers: { 'X-User': user },
        }),
      );
      this.sheetId.set(sheet.id);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not load sheet (${e.status}).` : 'Could not load sheet.';
    }
  }
}
