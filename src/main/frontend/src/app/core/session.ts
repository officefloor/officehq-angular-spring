import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

interface SignedIn {
  name: string;
}

interface SheetView {
  id: string;
}

// Who is signed in and which sheet is active. Everything else in the app is kept per sheet.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheetId = signal<string | null>(null);

  async signIn(name: string): Promise<void> {
    const res = await firstValueFrom(this.http.post<SignedIn>('/api/signin', { name }));
    this.user.set(res.name);
    this.sheetId.set(null);
  }

  /** Load a sheet by id; one never loaded before starts empty. */
  async loadSheet(id: string): Promise<void> {
    const user = this.user();
    if (!user) throw new Error('not signed in');
    const res = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/load`, null, {
        headers: { 'X-User': user },
      }),
    );
    this.sheetId.set(res.id);
  }
}
