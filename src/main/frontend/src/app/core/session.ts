import { HttpClient } from '@angular/common/http';
import { DestroyRef, Injectable, computed, inject, linkedSignal, signal } from '@angular/core';
import { Observable, map, of, switchMap, tap } from 'rxjs';
import { AsOf } from './as-of';

interface User {
  name: string;
}

interface SheetView {
  id: string;
  today: string;
  archived: boolean;
  /** The signed-in user's role on the sheet; given only when it is loaded. */
  role?: string | null;
}

// Who is signed in and which sheet is active. Everything else in the app is kept per sheet, so
// features read `sheetId` and send `user` with their requests (the X-User header).
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);
  private readonly asOfDate = inject(AsOf);

  private readonly _user = signal<string | null>(null);
  private readonly _sheetId = signal<string | null>(null);
  readonly user = this._user.asReadonly();
  private readonly _today = signal<string | null>(null);
  readonly sheetId = this._sheetId.asReadonly();
  private readonly _sheetLoad = signal<{ id: string } | null>(null);
  /** The active sheet each time it is loaded (again), so features re-read its data; null while none is. */
  readonly sheetLoad = this._sheetLoad.asReadonly();
  /** The date this screen shows the sheet as of, or null for as it is now. The sheet's today reads that date
   * while it is set. */
  readonly asOf = this.asOfDate.date;
  /** The as-of date the active sheet was last loaded with. */
  private readonly _loadedAsOf = signal<string | null>(null);
  /** The active sheet's today (yyyy-mm-dd). Date-dependent features use this, never the clock. */
  readonly today = this._today.asReadonly();
  private readonly _archived = signal<boolean | null>(null);
  /** Whether the active sheet is archived; null while it is not known (an archive is waiting on the server, or
   * was refused, until the sheet is loaded again). An archived sheet refuses every change. */
  readonly archived = this._archived.asReadonly();
  private readonly _role = signal<string | null>(null);
  /** The signed-in user's role on the active sheet as it was loaded ("clerk", "steward" or "viewer"); null while
   * no sheet is loaded. A viewer changes nothing. */
  readonly role = this._role.asReadonly();
  /** The features that read a sheet's data when it is loaded, and those still reading the active one's. */
  private readonly loaders = new Set<symbol>();
  private readonly pending = signal<ReadonlySet<symbol>>(new Set());
  /** The active sheet once every feature has read its data for it; null until then. */
  readonly loadedSheetId = computed(() => (this.pending().size === 0 ? this._sheetId() : null));
  /** The date the sheet on screen is shown as of (null: as it is now): it moves once every feature has read the
   * sheet so. */
  readonly shownAsOf = linkedSignal<{ done: boolean; asOf: string | null }, string | null>({
    source: () => ({ done: this.pending().size === 0, asOf: this._loadedAsOf() }),
    computation: (src, prev) => (src.done ? src.asOf : (prev?.value ?? null)),
  });

  /** Register a feature that reads each sheet's data when it is loaded, for as long as it lives; it calls the
   * function returned once it has read the data for a sheet id. One registered while a sheet is active is
   * still reading that sheet's. */
  registerLoader(destroyRef: DestroyRef): (sheetId: string | null) => void {
    const key = Symbol();
    this.loaders.add(key);
    if (this._sheetId() !== null) this.pending.update((p) => new Set([...p, key]));
    const settle = () => this.pending.update((p) => new Set([...p].filter((k) => k !== key)));
    destroyRef.onDestroy(() => {
      this.loaders.delete(key);
      settle();
    });
    return (sheetId) => {
      if (sheetId === this._sheetId()) settle();
    };
  }

  signIn(name: string): Observable<string> {
    return this.http.post<User>('/api/session', { name }).pipe(
      map((u) => u.name),
      tap((n) => {
        this._user.set(n);
        this.pending.set(new Set());
        this._sheetId.set(null);
        this._sheetLoad.set(null);
        this.asOfDate.set(null);
        this._loadedAsOf.set(null);
        this._today.set(null);
        this._archived.set(null);
        this._role.set(null);
      }),
    );
  }

  /** Load a sheet; while an as-of date is set, it is then read as it stood at the end of that date. */
  loadSheet(id: string): Observable<string> {
    const headers = { 'X-User': this._user() ?? '' };
    return this.http.post<SheetView>('/api/sheets/load', { id }, { headers }).pipe(
      switchMap((s) =>
        this.asOfDate.date()
          ? this.http
              .get<SheetView>(`/api/sheets/${encodeURIComponent(s.id)}`, { headers })
              .pipe(map((then) => ({ ...then, role: s.role })))
          : of(s),
      ),
      tap((s) => {
        this.pending.set(new Set(this.loaders));
        this._sheetId.set(s.id);
        this._sheetLoad.set({ id: s.id });
        this._loadedAsOf.set(this.asOfDate.date());
        this._today.set(s.today);
        this._archived.set(s.archived);
        this._role.set(s.role ?? null);
      }),
      map((s) => s.id),
    );
  }

  /** Show the active sheet as of a date (null: as it is now), on this screen only, and read it again so. */
  setAsOf(date: string | null): Observable<string> {
    this.asOfDate.set(date);
    return this.loadSheet(this._sheetId() ?? '');
  }

  setToday(today: string): Observable<string> {
    const id = this._sheetId() ?? '';
    return this.http
      .put<SheetView>(
        `/api/sheets/${encodeURIComponent(id)}/today`,
        { today },
        { headers: { 'X-User': this._user() ?? '' } },
      )
      .pipe(
        tap((s) => {
          if (this._sheetId() !== s.id) return;
          this._today.set(s.today);
          this._archived.set(s.archived);
        }),
        map((s) => s.today),
      );
  }

  /** Archive the active sheet. Whether it is archived is not known until the server answers; a refusal leaves it
   * unknown until the sheet is loaded again. */
  archive(): Observable<boolean> {
    const id = this._sheetId() ?? '';
    this._archived.set(null);
    return this.http
      .post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/archive`, null, {
        headers: { 'X-User': this._user() ?? '' },
      })
      .pipe(
        tap((s) => {
          if (this._sheetId() === s.id) this._archived.set(s.archived);
        }),
        map((s) => s.archived),
      );
  }
}
