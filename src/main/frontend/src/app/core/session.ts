import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

interface SignedIn {
  name: string;
  locale: string;
}

/** How the screen shows a user figures: en 1234.56, de 1.234,56. */
export type Locale = 'en' | 'de';
export const LOCALES: readonly Locale[] = ['en', 'de'];

function toLocale(value: string | null | undefined): Locale {
  return value === 'de' ? 'de' : 'en';
}

/** The people-list columns a user may switch off on their screen, in the order the chooser lists them. */
export const PERSON_COLUMNS = [
  'claimed', 'due-total', 'excess', 'statement-count', 'stated-total', 'statement-date', 'overdue-count',
  'overdue-total', 'patrons', 'backing', 'hold', 'retired', 'terms', 'contact', 'aliases',
] as const;
export type PersonColumn = (typeof PERSON_COLUMNS)[number];

interface SheetView {
  id: string;
  today: string;
  archived: boolean;
  parent: string | null;
}

// Who is signed in and which sheet is active. Everything else in the app is kept per sheet.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  /** The signed-in user's locale, kept per user in the store; en unless chosen. */
  readonly locale = signal<Locale>('en');
  /** The people-list columns the signed-in user's screen leaves out, kept per user in the store. */
  readonly hiddenColumns = signal<ReadonlySet<string>>(new Set());
  /** Column choices are written one after another, so the store ends with the last one made. */
  private columnWrites: Promise<unknown> = Promise.resolve();
  readonly sheetId = signal<string | null>(null);
  /** The active sheet's today (yyyy-mm-dd) as the store holds it. */
  readonly sheetToday = signal<string | null>(null);
  /** The day (yyyy-mm-dd) this user's screen shows every sheet as it stood at the end of; null when
   *  it shows the sheet as it is now. While it is set every change is refused. */
  readonly asOf = signal<string | null>(null);
  /** The day (yyyy-mm-dd) this user's screen shows every figure as if it were the sheet's today,
   *  over the sheet's current records; null when it previews nothing. Every change still uses the
   *  sheet's real today. */
  readonly preview = signal<string | null>(null);
  /** The day every figure on the screen is worked out on: the as-of day when one is set, or else the
   *  preview date when one is, or else the sheet's today. */
  readonly today = computed(() => this.asOf() ?? this.preview() ?? this.sheetToday());
  /** The day the sheet's changes run on: the as-of day when one is set, or else the sheet's today. */
  readonly changeToday = computed(() => this.asOf() ?? this.sheetToday());
  /** Whether the active sheet is archived: it stays visible and refuses every change. */
  readonly archived = signal(false);
  /** The active sheet's parent's id; null when it has none. */
  readonly parent = signal<string | null>(null);
  /** The sheet whose people and claims are on screen: set once a load of them has been read, so the
   *  sheet shows as loaded only when its rows are there to act on. */
  readonly shownId = signal<string | null>(null);
  /** Bumped on every sheet load, so what is kept per sheet is re-read from the store. */
  readonly loads = signal(0);

  async signIn(name: string): Promise<void> {
    const res = await firstValueFrom(this.http.post<SignedIn>('/api/signin', { name }));
    this.user.set(res.name);
    this.locale.set(toLocale(res.locale));
    this.hiddenColumns.set(new Set());
    this.sheetId.set(null);
    this.shownId.set(null);
    this.sheetToday.set(null);
    this.asOf.set(null);
    this.preview.set(null);
    this.archived.set(false);
    this.parent.set(null);
  }

  /** Whether a sheet load is under way: its rows are read again once it is done, so a read
   *  finished meanwhile does not show the sheet as loaded. */
  private loading = false;

  /** Record that the sheet's people and claims, as read for its latest load, are on screen. */
  markShown(id: string): void {
    if (!this.loading && this.sheetId() === id) this.shownId.set(id);
  }

  /** Load a sheet by id; one never loaded before starts empty. */
  async loadSheet(id: string): Promise<void> {
    const user = this.user();
    if (!user) throw new Error('not signed in');
    this.loading = true;
    try {
      const res = await firstValueFrom(
        this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/load`, null, {
          headers: { 'X-User': user },
        }),
      );
      this.show(res);
      await this.readLocale(user);
      await this.readColumns(user);
    } finally {
      this.loading = false;
    }
    this.bumpLoads();
  }

  /** Choose the signed-in user's locale; it is kept for them across every sheet. */
  async setLocale(locale: Locale): Promise<void> {
    const user = this.user();
    if (!user) throw new Error('not signed in');
    const res = await firstValueFrom(
      this.http.put<{ locale: string }>('/api/me/locale', { locale }, { headers: { 'X-User': user } }),
    );
    this.locale.set(toLocale(res.locale));
  }

  /** Re-read the user's locale from the store, as another screen of theirs may have changed it. */
  private async readLocale(user: string): Promise<void> {
    const res = await firstValueFrom(
      this.http.get<{ locale: string }>('/api/me/locale', { headers: { 'X-User': user } }),
    );
    this.locale.set(toLocale(res.locale));
  }

  /** Switch a people-list column on or off on the signed-in user's screen; kept for them across sheets. */
  async setColumnShown(column: PersonColumn, shown: boolean): Promise<void> {
    const user = this.user();
    if (!user) throw new Error('not signed in');
    const hidden = new Set(this.hiddenColumns());
    if (shown) hidden.delete(column);
    else hidden.add(column);
    this.hiddenColumns.set(hidden);
    const write = this.columnWrites.catch(() => undefined).then(() =>
      firstValueFrom(
        this.http.put<{ hidden: string[] }>('/api/me/columns', { hidden: [...hidden] }, { headers: { 'X-User': user } }),
      ),
    );
    this.columnWrites = write;
    await write;
  }

  /** Re-read the user's column choice from the store, once any of theirs still being written is in. */
  private async readColumns(user: string): Promise<void> {
    await this.columnWrites.catch(() => undefined);
    const res = await firstValueFrom(
      this.http.get<{ hidden: string[] }>('/api/me/columns', { headers: { 'X-User': user } }),
    );
    this.hiddenColumns.set(new Set(res.hidden));
  }

  /** Change the active sheet's today. */
  async setToday(today: string): Promise<void> {
    const user = this.user();
    const id = this.sheetId();
    if (!user || !id) throw new Error('no sheet loaded');
    const res = await firstValueFrom(
      this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/today`, { today }, {
        headers: { 'X-User': user },
      }),
    );
    this.show(res);
  }

  /** Archive the active sheet (a steward only). */
  async archive(): Promise<void> {
    const user = this.user();
    const id = this.sheetId();
    if (!user || !id) throw new Error('no sheet loaded');
    const res = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/archive`, null, {
        headers: { 'X-User': user },
      }),
    );
    this.show(res);
  }

  /** Clone the active sheet to another id, never used before (a steward only); the id cloned to. */
  async cloneSheet(to: string): Promise<string> {
    const user = this.user();
    const id = this.sheetId();
    if (!user || !id) throw new Error('no sheet loaded');
    const res = await firstValueFrom(
      this.http.post<{ to: string }>(`/api/sheets/${encodeURIComponent(id)}/clone`, { to }, {
        headers: { 'X-User': user },
      }),
    );
    return res.to;
  }

  /** Show every sheet as it stood at the end of a day (null: as it is now), re-reading it. */
  setAsOf(day: string | null): void {
    this.asOf.set(day);
    this.bumpLoads();
  }

  /** Show every figure as if the sheet's today were a day (null: the sheet's today), re-reading it. */
  setPreview(day: string | null): void {
    this.preview.set(day);
    this.bumpLoads();
  }

  private show(sheet: SheetView): void {
    this.sheetId.set(sheet.id);
    this.sheetToday.set(sheet.today);
    this.archived.set(sheet.archived);
    this.parent.set(sheet.parent);
  }

  private bumpLoads(): void {
    this.loads.update((n) => n + 1);
  }
}
