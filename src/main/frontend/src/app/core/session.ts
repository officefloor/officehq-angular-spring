import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

// The session: who is signed in (a name, no password) and which sheet is active. Everything else
// in the app is kept per sheet, so features read `sheet()` to know what to show.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheet = signal<string | null>(null);

  async signIn(name: string): Promise<void> {
    const res = await firstValueFrom(this.http.post<{ name: string }>('/api/signin', { name }));
    this.user.set(res.name);
    this.sheet.set(null);
  }

  async loadSheet(id: string): Promise<void> {
    const res = await firstValueFrom(
      this.http.post<{ id: string }>('/api/sheets/load', { id, user: this.user() }),
    );
    this.sheet.set(res.id);
  }
}
