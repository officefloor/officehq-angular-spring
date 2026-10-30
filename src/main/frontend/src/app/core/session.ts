import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

// The signed-in user and the active sheet. Every request after sign-in names the user in X-User.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheet = signal<string | null>(null);

  async signIn(name: string): Promise<void> {
    const res = await firstValueFrom(this.http.post<{ name: string }>('/api/signin', { name }));
    this.sheet.set(null);
    this.user.set(res.name);
  }

  async loadSheet(id: string): Promise<void> {
    const res = await firstValueFrom(
      this.http.post<{ id: string }>(`/api/sheets/${encodeURIComponent(id)}/load`, null, {
        headers: { 'X-User': this.user() ?? '' },
      }),
    );
    this.sheet.set(res.id);
  }
}
