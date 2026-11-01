import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

// The signed-in user and the active sheet. Every other feature reads these to scope its calls.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheetId = signal<string | null>(null);

  async signIn(name: string): Promise<void> {
    const res = await firstValueFrom(this.http.post<{ name: string }>('/api/session', { name }));
    this.user.set(res.name);
  }

  async loadSheet(id: string): Promise<void> {
    const user = this.user();
    if (!user) throw new Error('not signed in');
    const res = await firstValueFrom(
      this.http.post<{ id: string }>(`/api/sheets/${encodeURIComponent(id)}/load`, null, {
        headers: { 'X-User': user },
      }),
    );
    this.sheetId.set(res.id);
  }
}
