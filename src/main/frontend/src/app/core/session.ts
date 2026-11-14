import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** The locales a user can choose for how figures are shown. */
export const LOCALES = ['en', 'de'] as const;
export type Locale = (typeof LOCALES)[number];

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
  /** The date this screen previews the sheet on (yyyy-mm-dd); null while it shows the sheet's today.
   *  It belongs to this screen alone: while it is set, every figure is shown as if the sheet's today
   *  were that date, over the sheet's current records, and every change still uses the real today. */
  readonly preview = signal<string | null>(null);
  /** The active sheet's today (yyyy-mm-dd) as changes use it, never the real clock; while an as-of
   *  date is set, that date stands in for it. */
  readonly realToday = computed(() => this.asOf() ?? this.sheetToday());
  /** The day the screen's figures are worked out on (yyyy-mm-dd): the sheet's today, or the
   *  as-of date, or the preview date while one is set. */
  readonly today = computed(() => this.asOf() ?? this.preview() ?? this.sheetToday());
  /** Whether the active sheet is archived: everything stays visible and every change is refused. */
  readonly archived = signal(false);
  /** The number of the active sheet's last settle batch; null when there has been none. */
  readonly lastBatch = signal<number | null>(null);
  /** The signed-in user's locale, which decides how the screen shows figures; en until read. */
  readonly locale = signal<Locale>('en');
  /** Counts sheet loads, so features re-read their data even when the same id is loaded again. */
  readonly loads = signal(0);

  signIn(name: string): boolean {
    const trimmed = name.trim();
    if (!trimmed) return false;
    this.user.set(trimmed);
    this.locale.set('en');
    void this.loadLocale().catch(() => undefined);
    return true;
  }

  /** Re-read the signed-in user's locale from the store. */
  async loadLocale(): Promise<void> {
    const user = this.user();
    const view = await firstValueFrom(this.http.get<{ locale: Locale }>('/api/me/locale'));
    if (this.user() === user) this.locale.set(view.locale);
  }

  /** Save the signed-in user's locale. */
  async saveLocale(locale: Locale): Promise<void> {
    const view = await firstValueFrom(this.http.put<{ locale: Locale }>('/api/me/locale', { locale }));
    this.locale.set(view.locale);
  }

  /** Load a sheet by id; a sheet never loaded before starts empty. */
  async loadSheet(id: string): Promise<void> {
    const locale = this.loadLocale().catch(() => undefined);
    const sheet = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/load`, null),
    );
    await locale;
    this.show(sheet);
    this.loads.update((n) => n + 1);
  }

  /** Re-read everything on the active sheet after a change that touched much of it. */
  reread(): void {
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

  /** Preview the sheet on a date (yyyy-mm-dd) on this screen, re-reading every figure. */
  setPreview(date: string): void {
    this.preview.set(date);
    this.loads.update((n) => n + 1);
  }

  /** Show the figures on the sheet's today again, re-reading them. */
  clearPreview(): void {
    this.preview.set(null);
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

  /** Clone the active sheet to another id never used (a steward only); gives back that id. */
  async cloneTo(to: string): Promise<string> {
    const id = this.sheetId();
    if (!id) throw new Error('No sheet loaded.');
    const made = await firstValueFrom(
      this.http.post<{ to: string }>(`/api/sheets/${encodeURIComponent(id)}/clone`, { to }),
    );
    return made.to;
  }

  private show(sheet: SheetView): void {
    this.sheetId.set(sheet.id);
    this.sheetToday.set(sheet.today);
    this.archived.set(sheet.archived);
    this.lastBatch.set(sheet.lastBatch);
  }
}
