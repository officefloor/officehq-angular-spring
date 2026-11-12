import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export interface SheetView {
  id: string;
  today: string;
  /** The largest due a clerk may settle, two decimals; null when there is no limit. */
  clerkLimit: string | null;
}

// The signed-in user (a typed name, no password) and the active sheet. Every request to the
// server carries the user's name in the X-User header.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheetId = signal<string | null>(null);
  /** The sheet's today (YYYY-MM-DD): every date decision and default date uses it, never the clock. */
  readonly today = signal<string | null>(null);
  /** The sheet's clerk limit (two decimals); null when there is none. */
  readonly clerkLimit = signal<string | null>(null);

  signIn(name: string): boolean {
    const n = name.trim();
    if (!n) return false;
    this.user.set(n);
    return true;
  }

  async loadSheet(id: string): Promise<string | null> {
    const user = this.user();
    const sid = id.trim();
    if (!user) return 'Sign in first.';
    if (!sid) return 'Enter a sheet id.';
    try {
      const sheet = await firstValueFrom(
        this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/load`, null, {
          headers: { 'X-User': user },
        }),
      );
      this.sheetId.set(sheet.id);
      this.today.set(sheet.today);
      this.clerkLimit.set(sheet.clerkLimit);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not load sheet (${e.status}).` : 'Could not load sheet.';
    }
  }

  async setToday(date: string): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const d = date.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (!isDate(d)) return 'Enter a date as YYYY-MM-DD.';
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/today`, { today: d }, {
          headers: { 'X-User': user },
        }),
      );
      if (this.sheetId() === sheet.id) this.today.set(sheet.today);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not change today (${e.status}).` : 'Could not change today.';
    }
  }

  /** Change the sheet's clerk limit (blank: no limit); an error message when refused. */
  async setClerkLimit(limit: string): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const l = limit.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (l && !/^\d+(\.\d{1,2})?$/.test(l)) return 'Enter a clerk limit of 0.00 or more, with up to two decimals, or leave it blank.';
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings`, { clerkLimit: l || null }, {
          headers: { 'X-User': user },
        }),
      );
      if (this.sheetId() === sheet.id) this.clerkLimit.set(sheet.clerkLimit);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }
}

/** A real calendar date written YYYY-MM-DD. */
export function isDate(s: string): boolean {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s);
  if (!m) return false;
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
  const leap = (y % 4 === 0 && y % 100 !== 0) || y % 400 === 0;
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return mo >= 1 && mo <= 12 && d >= 1 && d <= days[mo - 1];
}
