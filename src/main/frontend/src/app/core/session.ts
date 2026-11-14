import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

interface SheetView {
  id: string;
  today: string;
  archived: boolean;
  lastBatch: number | null;
}

// Who is signed in (a typed name, no password) and which sheet is active. Everything else in the
// app is kept per sheet, so features read the active sheet id from here.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheetId = signal<string | null>(null);
  /** The active sheet's today (yyyy-mm-dd) as the store holds it. */
  private readonly sheetToday = signal<string | null>(null);
  /** The date this screen shows the sheet as of (yyyy-mm-dd); null while it shows it as it is now.
   *  It belongs to this screen alone: while it is set, everything read is as it stood at the end of
   *  that day, and every change is refused. */
  readonly asOf = signal<string | null>(null);
  /** The active sheet's today (yyyy-mm-dd): every date-dependent decision and default date uses
   *  it, never the real clock; while an as-of date is set, that date stands in for it. */
  readonly today = computed(() => this.asOf() ?? this.sheetToday());
  /** Whether the active sheet is archived: everything stays visible and every change is refused. */
  readonly archived = signal(false);
  /** The number of the active sheet's last settle batch; null when there has been none. */
  readonly lastBatch = signal<number | null>(null);
  /** Counts sheet loads, so features re-read their data even when the same id is loaded again. */
  readonly loads = signal(0);

  signIn(name: string): boolean {
    const trimmed = name.trim();
    if (!trimmed) return false;
    this.user.set(trimmed);
    return true;
  }

  /** Load a sheet by id; a sheet never loaded before starts empty. */
  async loadSheet(id: string): Promise<void> {
    const sheet = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/load`, null),
    );
    this.show(sheet);
    this.loads.update((n) => n + 1);
  }

  /** Show the sheet as of a date (yyyy-mm-dd) on this screen, re-reading everything as it stood then. */
  setAsOf(date: string): void {
    this.asOf.set(date);
    this.loads.update((n) => n + 1);
  }

  /** Show the sheet as it is now again, re-reading everything. */
  clearAsOf(): void {
    this.asOf.set(null);
    this.loads.update((n) => n + 1);
  }

  /** Change the active sheet's today. */
  async setToday(today: string): Promise<void> {
    const id = this.sheetId();
    if (!id) throw new Error('No sheet loaded.');
    const sheet = await firstValueFrom(
      this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/today`, { today }),
    );
    if (this.sheetId() === sheet.id) this.show(sheet);
  }

  /** Archive the active sheet (a steward only). */
  async archive(): Promise<void> {
    const id = this.sheetId();
    if (!id) throw new Error('No sheet loaded.');
    const sheet = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/archive`, null),
    );
    if (this.sheetId() === sheet.id) this.show(sheet);
  }

  private show(sheet: SheetView): void {
    this.sheetId.set(sheet.id);
    this.sheetToday.set(sheet.today);
    this.archived.set(sheet.archived);
    this.lastBatch.set(sheet.lastBatch);
  }
}
