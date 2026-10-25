import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** A locale a user may choose. */
export type Locale = 'en' | 'de';

export const LOCALES: readonly Locale[] = ['en', 'de'];

interface SheetView {
  id: string;
  /** The sheet's today (YYYY-MM-DD): every date-dependent decision and default uses it. */
  today: string;
  /** Whether the sheet is archived: everything stays visible and every change is refused. */
  archived: boolean;
  /** The number of the sheet's last settle batch (a bulk settle that settled a claim); null while it
   *  has had none. */
  lastSettleBatch: number | null;
  /** The id of the sheet's parent, whose rates are used where it has none in force; null when none. */
  parent: string | null;
}

// Who is signed in and which sheet is active. Every request to the server carries the signed-in
// name in the X-User header (see `headers()`); everything else in the app is kept per sheet. While an
// as-of date is set, every request also carries it in the X-As-Of header: the server then shows each
// sheet as it stood at the end of that date (that date standing in for its today) and refuses every
// change. The as-of date belongs to this screen alone. While a preview date is set, every request also
// carries it in the X-Preview header: the server then works every figure out as if the sheet's today were
// that date, over the sheet's current records, while every change still uses its real today. The preview
// date belongs to this screen alone too.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheetId = signal<string | null>(null);
  /** The active sheet's today (YYYY-MM-DD) as the server gives it. While an as-of date is set, it is
   *  that date. */
  readonly sheetToday = signal<string | null>(null);
  /** The date (YYYY-MM-DD) the figures are previewed on; null when they are shown on the sheet's today. */
  readonly preview = signal<string | null>(null);
  /** The today every figure is worked out on (YYYY-MM-DD); use it, never the browser's clock. While an
   *  as-of date is set, it is that date; otherwise while a preview date is set, it is that date. */
  readonly today = computed(() => (this.asOf() ? null : this.preview()) ?? this.sheetToday());
  /** Whether the active sheet is archived. */
  readonly archived = signal(false);
  /** The number of the active sheet's last settle batch; null while it has had none. */
  readonly lastSettleBatch = signal<number | null>(null);
  /** The id of the active sheet's parent; null when it has none. */
  readonly parent = signal<string | null>(null);
  /** The date (YYYY-MM-DD) the sheets are shown as of; null when they are shown as they stand. */
  readonly asOf = signal<string | null>(null);
  /** Counts sheet loads, so a view re-reads the store even when the same id is loaded again. */
  readonly loads = signal(0);
  /** The signed-in user's locale on the active sheet: how figures are shown to them ('en' or 'de'). */
  readonly locale = signal<Locale>('en');
  /** The people-list columns the signed-in user has switched off on the active sheet. */
  readonly hiddenColumns = signal<readonly string[]>([]);
  readonly signedIn = computed(() => this.user() !== null);

  signIn(name: string): boolean {
    const user = name.trim();
    if (!user) return false;
    this.user.set(user);
    this.sheetId.set(null);
    this.sheetToday.set(null);
    this.preview.set(null);
    this.archived.set(false);
    this.lastSettleBatch.set(null);
    this.parent.set(null);
    this.asOf.set(null);
    this.locale.set('en');
    this.hiddenColumns.set([]);
    return true;
  }

  headers(): HttpHeaders {
    const headers: Record<string, string> = { 'X-User': this.user() ?? '' };
    const asOf = this.asOf();
    if (asOf) headers['X-As-Of'] = asOf;
    const preview = this.preview();
    if (preview) headers['X-Preview'] = preview;
    return new HttpHeaders(headers);
  }

  /** Preview the figures on a date (YYYY-MM-DD), re-reading the active sheet; resolves false when the
   *  date is not one. */
  async setPreview(date: string): Promise<boolean> {
    const preview = date.trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(preview)) return false;
    this.preview.set(preview);
    const sheet = this.sheetId();
    return sheet ? this.loadSheet(sheet) : true;
  }

  /** Show the figures on the sheet's today again, re-reading the active sheet. */
  async clearPreview(): Promise<boolean> {
    this.preview.set(null);
    const sheet = this.sheetId();
    return sheet ? this.loadSheet(sheet) : true;
  }

  /** Show the sheets as of a date (YYYY-MM-DD), re-reading the active one; resolves false when the
   *  date is not one. */
  async setAsOf(date: string): Promise<boolean> {
    const asOf = date.trim();
    if (!/^\d{4}-\d{2}-\d{2}$/.test(asOf)) return false;
    this.asOf.set(asOf);
    const sheet = this.sheetId();
    return sheet ? this.loadSheet(sheet) : true;
  }

  /** Show the sheets as they stand again, re-reading the active one. */
  async clearAsOf(): Promise<boolean> {
    this.asOf.set(null);
    const sheet = this.sheetId();
    return sheet ? this.loadSheet(sheet) : true;
  }

  /** Load a sheet by id (starting it empty if never used); resolves false when refused. */
  async loadSheet(id: string): Promise<boolean> {
    const sheet = id.trim();
    if (!sheet || !this.user()) return false;
    try {
      const view = await firstValueFrom(
        this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(sheet)}/load`, null, {
          headers: this.headers(),
        }),
      );
      const { locale } = await firstValueFrom(
        this.http.get<{ locale: Locale }>(`/api/sheets/${encodeURIComponent(view.id)}/locale`, {
          headers: this.headers(),
        }),
      );
      const { hidden } = await firstValueFrom(
        this.http.get<{ hidden: string[] }>(`/api/sheets/${encodeURIComponent(view.id)}/columns`, {
          headers: this.headers(),
        }),
      );
      this.show(view);
      this.locale.set(locale);
      this.hiddenColumns.set(hidden);
      this.loads.update((n) => n + 1);
      return true;
    } catch {
      return false;
    }
  }

  /** Change the active sheet's today; resolves false when refused. */
  async setToday(date: string): Promise<boolean> {
    const sheet = this.sheetId();
    const today = date.trim();
    if (!sheet || !/^\d{4}-\d{2}-\d{2}$/.test(today)) return false;
    try {
      const view = await firstValueFrom(
        this.http.put<SheetView>(
          `/api/sheets/${encodeURIComponent(sheet)}/today`,
          { today },
          { headers: this.headers() },
        ),
      );
      if (this.sheetId() === view.id) this.show(view);
      return true;
    } catch {
      return false;
    }
  }

  /** Archive the active sheet; resolves false when refused. */
  async archive(): Promise<boolean> {
    const sheet = this.sheetId();
    if (!sheet) return false;
    try {
      const view = await firstValueFrom(
        this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(sheet)}/archive`, null, {
          headers: this.headers(),
        }),
      );
      if (this.sheetId() === view.id) this.show(view);
      return true;
    } catch {
      return false;
    }
  }

  /** Clone the active sheet to another id, one never used; resolves the clone's id, or null when refused. */
  async cloneSheet(to: string): Promise<string | null> {
    const sheet = this.sheetId();
    const target = to.trim();
    if (!sheet || !target) return null;
    try {
      const view = await firstValueFrom(
        this.http.post<SheetView>(
          `/api/sheets/${encodeURIComponent(sheet)}/clone`,
          { to: target },
          { headers: this.headers() },
        ),
      );
      return view.id;
    } catch {
      return null;
    }
  }

  /** Choose the signed-in user's locale on the active sheet; resolves false when refused. */
  async setLocale(locale: string): Promise<boolean> {
    const sheet = this.sheetId();
    if (!sheet || !LOCALES.includes(locale as Locale)) return false;
    try {
      const view = await firstValueFrom(
        this.http.put<{ locale: Locale }>(
          `/api/sheets/${encodeURIComponent(sheet)}/locale`,
          { locale },
          { headers: this.headers() },
        ),
      );
      if (this.sheetId() === sheet) this.locale.set(view.locale);
      return true;
    } catch {
      return false;
    }
  }

  /** Switch off exactly these people-list columns for the signed-in user on the active sheet; resolves
   *  false when refused. */
  async setHiddenColumns(hidden: readonly string[]): Promise<boolean> {
    const sheet = this.sheetId();
    if (!sheet) return false;
    const was = this.hiddenColumns();
    this.hiddenColumns.set(hidden);
    try {
      const view = await firstValueFrom(
        this.http.put<{ hidden: string[] }>(
          `/api/sheets/${encodeURIComponent(sheet)}/columns`,
          { hidden },
          { headers: this.headers() },
        ),
      );
      if (this.sheetId() === sheet) this.hiddenColumns.set(view.hidden);
      return true;
    } catch {
      if (this.sheetId() === sheet) this.hiddenColumns.set(was);
      return false;
    }
  }

  private show(view: SheetView): void {
    this.sheetId.set(view.id);
    this.sheetToday.set(view.today);
    this.archived.set(view.archived);
    this.lastSettleBatch.set(view.lastSettleBatch);
    this.parent.set(view.parent ?? null);
  }
}
