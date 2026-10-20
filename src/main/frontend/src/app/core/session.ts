import { HttpClient } from '@angular/common/http';
import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { Observable, map, tap } from 'rxjs';

interface User {
  name: string;
}

interface SheetView {
  id: string;
  today: string;
  archived: boolean;
}

// Who is signed in and which sheet is active. Everything else in the app is kept per sheet, so
// features read `sheetId` and send `user` with their requests (the X-User header).
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  private readonly _user = signal<string | null>(null);
  private readonly _sheetId = signal<string | null>(null);
  readonly user = this._user.asReadonly();
  private readonly _today = signal<string | null>(null);
  readonly sheetId = this._sheetId.asReadonly();
  /** The active sheet's today (yyyy-mm-dd). Date-dependent features use this, never the clock. */
  readonly today = this._today.asReadonly();
  private readonly _archived = signal<boolean | null>(null);
  /** Whether the active sheet is archived; null while it is not known (an archive is waiting on the server, or
   * was refused, until the sheet is loaded again). An archived sheet refuses every change. */
  readonly archived = this._archived.asReadonly();
  /** The features that read a sheet's data when it is loaded, and those still reading the active one's. */
  private readonly loaders = new Set<symbol>();
  private readonly pending = signal<ReadonlySet<symbol>>(new Set());
  /** The active sheet once every feature has read its data for it; null until then. */
  readonly loadedSheetId = computed(() => (this.pending().size === 0 ? this._sheetId() : null));

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
        this._today.set(null);
        this._archived.set(null);
      }),
    );
  }

  loadSheet(id: string): Observable<string> {
    return this.http
      .post<SheetView>('/api/sheets/load', { id }, { headers: { 'X-User': this._user() ?? '' } })
      .pipe(
        tap((s) => {
          this.pending.set(new Set(this.loaders));
          this._sheetId.set(s.id);
          this._today.set(s.today);
          this._archived.set(s.archived);
        }),
        map((s) => s.id),
      );
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
