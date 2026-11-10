import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

// The session: who is signed in (a name, no password) and which sheet is active. Everything else
// in the app is kept per sheet, so features read `sheet()` to know what to show, and `today()` for
// the sheet's today: every date-dependent decision and default date uses it, never the real clock.
interface SheetResponse {
  id: string;
  today: string;
  archived: boolean;
  lastBatch: number | null;
}

/** The locales a user may choose: how the screen shows them figures (see FigurePipe). */
export const LOCALES = ['en', 'de'] as const;
export type Locale = (typeof LOCALES)[number];

/** The keys the people list can be sorted by on the screen. */
export type PeopleSort = 'added' | 'name' | 'grade' | 'due-total';

/** The columns of the people list a user may switch off on their screen. */
export const PEOPLE_COLUMNS = [
  'claimed', 'due-total', 'statement-count', 'stated-total', 'statement-date', 'overdue-count', 'overdue-total',
  'patrons', 'backing', 'excess', 'hold', 'retired', 'terms', 'contact', 'aliases',
] as const;
export type PeopleColumn = (typeof PEOPLE_COLUMNS)[number];

@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  /** The signed-in user's locale, saved per user: read at sign-in, en unless they chose otherwise. */
  readonly locale = signal<Locale>('en');
  readonly sheet = signal<string | null>(null);
  readonly today = signal<string | null>(null);
  /** Whether the sheet is archived: it stays visible and every change to it is refused. */
  readonly archived = signal(false);
  /** The number of the sheet's last settle batch (a bulk settle that settled a claim); none when there has been none. */
  readonly lastBatch = signal<number | null>(null);
  /** The id of the sheet's parent, whose rates stand in for a grade with none in force here; blank for none.
   *  Set from the sheet's settings as they are read and saved. */
  readonly parent = signal('');
  /** Whether retired people are listed: off when the screen opens, and left as it is by loading a sheet. */
  readonly showRetired = signal(false);
  /** What this screen sorts the people list by: off (added) when the screen opens, and left as it is by loading a sheet. */
  readonly sortBy = signal<PeopleSort>('added');
  /** The people-list columns each user has switched off on this screen: all shown until they choose, and left as they are by loading a sheet. */
  private readonly hiddenColumns = signal<ReadonlyMap<string, ReadonlySet<PeopleColumn>>>(new Map());
  /** The columns the signed-in user has switched off on this screen. */
  readonly hidden = computed(() => this.hiddenColumns().get(this.user() ?? '') ?? new Set<PeopleColumn>());

  /** Show or hide one column of the people list for the signed-in user on this screen. */
  showColumn(column: PeopleColumn, shown: boolean): void {
    const user = this.user() ?? '';
    this.hiddenColumns.update((all) => {
      const off = new Set(all.get(user));
      if (shown) off.delete(column);
      else off.add(column);
      return new Map(all).set(user, off);
    });
  }
  /**
   * The date this screen shows the sheet as of (none: as it is now). Sent with every request
   * (see asOfInterceptor): the store answers as the sheet stood at the end of that date, its today
   * the date, and refuses every change. Kept when another sheet is loaded.
   */
  readonly asOf = signal<string | null>(null);
  /** Counts sheet loads, so features re-read the store each time a sheet is (re-)loaded. */
  readonly loads = signal(0);

  async signIn(name: string): Promise<void> {
    const res = await firstValueFrom(this.http.post<{ name: string; locale: Locale }>('/api/signin', { name }));
    this.user.set(res.name);
    this.locale.set(res.locale);
    this.sheet.set(null);
    this.today.set(null);
    this.archived.set(false);
    this.lastBatch.set(null);
    this.asOf.set(null);
  }

  /** Save the signed-in user's locale. */
  async saveLocale(locale: Locale): Promise<void> {
    const res = await firstValueFrom(
      this.http.post<{ locale: Locale }>('/api/users/locale', { user: this.user(), locale }),
    );
    this.locale.set(res.locale);
  }

  async loadSheet(id: string): Promise<void> {
    const res = await firstValueFrom(
      this.http.post<SheetResponse>('/api/sheets/load', { id, user: this.user() }),
    );
    this.show(res);
    this.loads.update((n) => n + 1);
  }

  /** Show the active sheet as of a date, re-read from the store. */
  async setAsOf(date: string): Promise<void> {
    this.asOf.set(date);
    const id = this.sheet();
    if (id) await this.loadSheet(id);
  }

  /** Show the active sheet as it is now, re-read from the store. */
  async clearAsOf(): Promise<void> {
    this.asOf.set(null);
    const id = this.sheet();
    if (id) await this.loadSheet(id);
  }

  async setToday(today: string): Promise<void> {
    const res = await firstValueFrom(
      this.http.post<SheetResponse>('/api/sheets/today', { id: this.sheet(), user: this.user(), today }),
    );
    this.show(res);
  }

  /** Archive the active sheet (a steward only). */
  async archive(): Promise<void> {
    const res = await firstValueFrom(
      this.http.post<SheetResponse>('/api/sheets/archive', { id: this.sheet(), user: this.user() }),
    );
    this.show(res);
  }

  /** Clone the active sheet to another id never used (a steward only); answers the clone's id. */
  async cloneSheet(to: string): Promise<string> {
    const res = await firstValueFrom(
      this.http.post<SheetResponse>('/api/sheets/clone', { id: this.sheet(), user: this.user(), to }),
    );
    return res.id;
  }

  private show(res: SheetResponse): void {
    if (res.id !== this.sheet()) this.parent.set('');
    this.sheet.set(res.id);
    this.today.set(res.today);
    this.archived.set(res.archived);
    this.lastBatch.set(res.lastBatch);
  }
}
