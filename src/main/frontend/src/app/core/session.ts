import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AsOf } from './as-of';
import { UserLocale } from './locale';

// Who is signed in and which sheet is active. Everything else in the app is kept per sheet.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);
  private readonly asOf = inject(AsOf);
  private readonly userLocale = inject(UserLocale);

  readonly user = signal<string | null>(null);
  readonly sheetId = signal<string | null>(null);
  // The active sheet's today (yyyy-mm-dd): every date decision and default date uses it.
  readonly sheetToday = signal<string | null>(null);
  // Whether the active sheet is archived: it stays visible and every change on it is refused.
  readonly sheetArchived = signal(false);
  // The number of the active sheet's last bulk settle batch, or null when there has been none.
  readonly sheetLastBatch = signal<number | null>(null);
  // Counts sheet loads, so a view re-reads the store even when the same id is loaded again.
  readonly loads = signal(0);
  // The date the screen shows the sheet as of, or null: while set, every figure is as the sheet stood at the end of
  // it, the date stands in for the sheet's today, and every change is refused.
  readonly sheetAsOf = this.asOf.date.asReadonly();

  async signIn(name: string): Promise<void> {
    const u = await firstValueFrom(this.http.post<{ name: string }>('/api/signin', { name }));
    await this.userLocale.load();
    this.user.set(u.name);
  }

  async loadSheet(id: string): Promise<void> {
    const s = await firstValueFrom(
      this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(id)}`, null),
    );
    this.sheetId.set(s.id);
    this.sheetToday.set(s.today);
    this.sheetArchived.set(s.archived);
    this.sheetLastBatch.set(s.lastBatch);
    this.loads.update((n) => n + 1);
  }

  // Shows the sheet as of the date (null: as it stands now), re-read from the store.
  async setAsOf(date: string | null): Promise<void> {
    this.asOf.date.set(date);
    const id = this.sheetId();
    if (id) await this.loadSheet(id);
  }

  async setToday(today: string): Promise<void> {
    const id = this.sheetId();
    if (!id) return;
    const s = await firstValueFrom(
      this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/today`, { today }),
    );
    this.sheetToday.set(s.today);
  }

  // Clones the active sheet to another id, never used before.
  async cloneSheet(toSheetId: string): Promise<void> {
    const id = this.sheetId();
    if (!id) return;
    await firstValueFrom(this.http.post(`/api/sheets/${encodeURIComponent(id)}/clone`, { toSheetId }));
  }

  async archive(): Promise<void> {
    const id = this.sheetId();
    if (!id) return;
    const s = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/archive`, null),
    );
    this.sheetArchived.set(s.archived);
  }
}

interface SheetView {
  id: string;
  today: string;
  archived: boolean;
  lastBatch: number | null;
}
