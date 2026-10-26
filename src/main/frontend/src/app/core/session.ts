import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

// Who is signed in and which sheet is active. Everything else in the app is kept per sheet.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheetId = signal<string | null>(null);
  // The active sheet's today (yyyy-mm-dd): every date decision and default date uses it.
  readonly sheetToday = signal<string | null>(null);

  async signIn(name: string): Promise<void> {
    const u = await firstValueFrom(this.http.post<{ name: string }>('/api/signin', { name }));
    this.user.set(u.name);
  }

  async loadSheet(id: string): Promise<void> {
    const s = await firstValueFrom(
      this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(id)}`, null),
    );
    this.sheetId.set(s.id);
    this.sheetToday.set(s.today);
  }

  async setToday(today: string): Promise<void> {
    const id = this.sheetId();
    if (!id) return;
    const s = await firstValueFrom(
      this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/today`, { today }),
    );
    this.sheetToday.set(s.today);
  }
}

interface SheetView {
  id: string;
  today: string;
}
