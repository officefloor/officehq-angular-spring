import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

interface SheetView {
  id: string;
  today: string;
}

// The signed-in user and the active sheet. Every request after sign-in names the user in X-User.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheet = signal<string | null>(null);
  /** The active sheet's today (yyyy-mm-dd): every date the screen decides on or defaults to. */
  readonly today = signal<string | null>(null);

  async signIn(name: string): Promise<void> {
    const res = await firstValueFrom(this.http.post<{ name: string }>('/api/signin', { name }));
    this.sheet.set(null);
    this.today.set(null);
    this.user.set(res.name);
  }

  async loadSheet(id: string): Promise<void> {
    const res = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/load`, null, {
        headers: { 'X-User': this.user() ?? '' },
      }),
    );
    this.sheet.set(res.id);
    this.today.set(res.today);
  }

  async setToday(today: string): Promise<void> {
    const id = this.sheet();
    if (!id) return;
    const res = await firstValueFrom(
      this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/today`, { today }, {
        headers: { 'X-User': this.user() ?? '' },
      }),
    );
    if (this.sheet() === res.id) this.today.set(res.today);
  }
}
