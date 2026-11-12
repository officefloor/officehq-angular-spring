import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

export interface SheetView {
  id: string;
  today: string;
  /** The largest due a clerk may settle, two decimals; null when there is no limit. */
  clerkLimit: string | null;
  /** The least a claim's live due can be, two decimals. */
  floor: string;
  /** The number of days after a claim's date it is due by. */
  terms: number;
  /** The percent of its figure an overdue claim's due gains for every full 30 days, two decimals. */
  latePct: string;
  /** The policy date (YYYY-MM-DD); null when it is the sheet's today. */
  policyDate: string | null;
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
  /** The sheet's floor (two decimals): the least a claim's live due can be. */
  readonly floor = signal<string>('1.00');
  /** The sheet's terms: the number of days after a claim's date it is due by. */
  readonly terms = signal<number>(14);
  /** The sheet's late percent (two decimals): what an overdue claim's due gains of its figure for every
   *  full 30 days it is overdue. */
  readonly latePct = signal<string>('0.00');
  /** The sheet's policy date (YYYY-MM-DD); null when it is the sheet's today. A claim dated before it
   *  rounds half up, one dated on or after it half to even. */
  readonly policyDate = signal<string | null>(null);

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
      this.floor.set(sheet.floor);
      this.terms.set(sheet.terms);
      this.latePct.set(sheet.latePct);
      this.policyDate.set(sheet.policyDate);
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

  /** Change the sheet's floor; an error message when refused. */
  async setFloor(floor: string): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const f = floor.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (!/^\d+(\.\d{1,2})?$/.test(f)) return 'Enter a floor of 0.00 or more, with up to two decimals.';
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings/floor`, { floor: f }, {
          headers: { 'X-User': user },
        }),
      );
      if (this.sheetId() === sheet.id) this.floor.set(sheet.floor);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Change the sheet's terms (whole days); an error message when refused. */
  async setTerms(terms: string): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const t = terms.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (!/^\d{1,5}$/.test(t) || Number(t) > 36500) return 'Enter terms as a whole number of days, 0 or more.';
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings/terms`, { terms: Number(t) }, {
          headers: { 'X-User': user },
        }),
      );
      if (this.sheetId() === sheet.id) this.terms.set(sheet.terms);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Whether a claim dated on a date rounds half up: it is before the policy date (the sheet's today when
   *  there is none); otherwise it rounds half to even. */
  roundsHalfUp(date: string): boolean {
    const policy = this.policyDate() ?? this.today();
    return policy !== null && date < policy;
  }

  /** Change the sheet's policy date (blank: the sheet's today); an error message when refused. */
  async setPolicyDate(date: string): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const d = date.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (d && !isDate(d)) return 'Enter a policy date as YYYY-MM-DD, or leave it blank.';
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings/policy-date`, { policyDate: d || null }, {
          headers: { 'X-User': user },
        }),
      );
      if (this.sheetId() === sheet.id) this.policyDate.set(sheet.policyDate);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Change the sheet's late percent; an error message when refused. */
  async setLatePct(pct: string): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const p = pct.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (!/^\d{1,7}(\.\d{1,2})?$/.test(p)) return 'Enter a late percent of 0.00 or more, with up to two decimals.';
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings/late-pct`, { latePct: p }, {
          headers: { 'X-User': user },
        }),
      );
      if (this.sheetId() === sheet.id) this.latePct.set(sheet.latePct);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }
}

/** The number of days from one date (YYYY-MM-DD) to another, worked out on the calendar. */
export function daysBetween(from: string, to: string): number {
  const [fy, fm, fd] = from.split('-').map(Number);
  const [ty, tm, td] = to.split('-').map(Number);
  return dayNumber(ty, tm, td) - dayNumber(fy, fm, fd);
}

/** A date (YYYY-MM-DD) moved on a number of days, worked out on the calendar without the clock. */
export function addDays(date: string, days: number): string {
  const [y, m, d] = date.split('-').map(Number);
  return fromDayNumber(dayNumber(y, m, d) + days);
}

/** Days since 1970-01-01 of a calendar date. */
function dayNumber(y: number, m: number, d: number): number {
  const yy = m <= 2 ? y - 1 : y;
  const era = Math.floor(yy / 400);
  const yoe = yy - era * 400;
  const doy = Math.floor((153 * (m + (m > 2 ? -3 : 9)) + 2) / 5) + d - 1;
  const doe = yoe * 365 + Math.floor(yoe / 4) - Math.floor(yoe / 100) + doy;
  return era * 146097 + doe - 719468;
}

/** The calendar date (YYYY-MM-DD) of a number of days since 1970-01-01. */
function fromDayNumber(n: number): string {
  const z = n + 719468;
  const era = Math.floor(z / 146097);
  const doe = z - era * 146097;
  const yoe = Math.floor((doe - Math.floor(doe / 1460) + Math.floor(doe / 36524) - Math.floor(doe / 146096)) / 365);
  const doy = doe - (365 * yoe + Math.floor(yoe / 4) - Math.floor(yoe / 100));
  const mp = Math.floor((5 * doy + 2) / 153);
  const d = doy - Math.floor((153 * mp + 2) / 5) + 1;
  const m = mp < 10 ? mp + 3 : mp - 9;
  const y = yoe + era * 400 + (m <= 2 ? 1 : 0);
  return `${String(y).padStart(4, '0')}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
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
