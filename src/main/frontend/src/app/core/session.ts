import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

interface SheetView {
  id: string;
  today: string;
  archived: boolean;
}

// The signed-in user and the active sheet. Every request after sign-in names the user in X-User.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheet = signal<string | null>(null);
  /** The active sheet's own today (yyyy-mm-dd), whatever date the screen shows it as of. */
  private readonly sheetToday = signal<string | null>(null);
  /**
   * The date this screen shows the sheet as of (yyyy-mm-dd), or null for the sheet as it stands. While set,
   * every read is of the sheet as it stood at the end of that date and every change is refused.
   */
  readonly asOf = signal<string | null>(null);
  /** The active sheet's today (yyyy-mm-dd): every date the screen decides on or defaults to; the as-of date while set. */
  readonly today = computed(() => this.asOf() ?? this.sheetToday());
  /** Whether the active sheet is archived: it stays visible and every change to it is refused. */
  readonly archived = signal(false);
  /** Bumped on every sheet load, so what is shown for the sheet is re-read even for the same id. */
  readonly loads = signal(0);

  async signIn(name: string): Promise<void> {
    const res = await firstValueFrom(this.http.post<{ name: string }>('/api/signin', { name }));
    this.sheet.set(null);
    this.sheetToday.set(null);
    this.asOf.set(null);
    this.archived.set(false);
    this.loads.update((n) => n + 1);
    this.user.set(res.name);
  }

  async loadSheet(id: string): Promise<void> {
    const res = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/load`, null, {
        headers: { 'X-User': this.user() ?? '' },
      }),
    );
    this.sheet.set(res.id);
    this.sheetToday.set(res.today);
    this.archived.set(res.archived);
    if (this.asOf() !== null) await this.readShown(res.id);
    this.loads.update((n) => n + 1);
  }

  /** Show the active sheet as of a date (yyyy-mm-dd), or as it stands (null): everything shown is re-read. */
  async setAsOf(date: string | null): Promise<void> {
    this.asOf.set(date);
    const id = this.sheet();
    if (id) await this.readShown(id);
    this.loads.update((n) => n + 1);
  }

  /** Re-read whether the sheet is archived, as it is shown (as of the as-of date while one is set). */
  private async readShown(id: string): Promise<void> {
    const res = await firstValueFrom(this.http.get<SheetView>(`/api/sheets/${encodeURIComponent(id)}`));
    if (this.sheet() === res.id) this.archived.set(res.archived);
  }

  async setToday(today: string): Promise<void> {
    const id = this.sheet();
    if (!id) return;
    const res = await firstValueFrom(
      this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/today`, { today }, {
        headers: { 'X-User': this.user() ?? '' },
      }),
    );
    if (this.sheet() === res.id) this.sheetToday.set(res.today);
  }

  async archive(): Promise<void> {
    const id = this.sheet();
    if (!id) return;
    const res = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/archive`, null, {
        headers: { 'X-User': this.user() ?? '' },
      }),
    );
    if (this.sheet() === res.id) this.archived.set(res.archived);
  }
}
