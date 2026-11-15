import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

// Who is signed in and which sheet is active. Everything else in the app is kept per sheet.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheetId = signal<string | null>(null);
  /** The active sheet's today (yyyy-mm-dd): every date decision and default date uses it. */
  readonly today = signal<string | null>(null);
  /** Every value recorded for the active sheet's dated settings (its clerk limit, floor, terms,
   *  terms per grade, late percent, cap and proof threshold), by key, each key's by from-date. */
  readonly dated = signal<DatedSettings>({});
  /** The active sheet's floor (two decimals) in force on its today. */
  readonly floor = computed(() => this.floorOn(this.today()));
  /** The active sheet's terms in days in force on its today: a claim created in a grade without terms of its own is due by its date plus them. */
  readonly terms = computed(() => this.termsOn(this.today()));
  /** The active sheet's late percent (two decimals) in force on its today. */
  readonly latePct = computed(() => this.latePctOn(this.today()));
  /** The active sheet's clerk limit (two decimals) in force on its today, null when there is none:
   *  a clerk settles only claims whose due is at or below it. */
  readonly clerkLimit = computed(() => valueOn(this.dated(), 'clerk-limit', this.today())?.value ?? null);
  /** The active sheet's policy date (yyyy-mm-dd), null when blank (the sheet's today is then the
   *  policy date): a claim dated before it rounds half up, one on or after it half to even. */
  readonly policyDate = signal<string | null>(null);
  /** The active sheet's cap (two decimals) in force on its today, null when there is none: a person's due-total is at most it. */
  readonly cap = computed(() => valueOn(this.dated(), 'cap', this.today())?.value ?? null);
  /** The active sheet's proof threshold (two decimals) in force on its today, null when there is
   *  none: a claim whose due is above the one in force on its date is settled only with a proof. */
  readonly proofThreshold = computed(() => valueOn(this.dated(), 'proof-threshold', this.today())?.value ?? null);
  /** Whether the people list shows retired people too: off at first, and kept as it is when a
   *  sheet is loaded. */
  readonly showRetired = signal(false);
  /** Bumped on every sheet load, so a load of the same id again re-reads what the sheet holds. */
  readonly loads = signal(0);

  /** The floor (two decimals) in force on a date: a live due of a claim dated then is at least it;
   *  the starting 1.00 before every from-date. */
  floorOn(date: string | null): string {
    return valueOn(this.dated(), 'floor', date)?.value ?? '1.00';
  }

  /** The late percent (two decimals) in force on a date: an overdue claim dated then gains it for
   *  every full 30 days overdue; the starting 0.00 before every from-date. */
  latePctOn(date: string | null): string {
    return valueOn(this.dated(), 'late-pct', date)?.value ?? '0.00';
  }

  /** The sheet's terms in days in force on a date; the starting 14 before every from-date. */
  termsOn(date: string | null): number {
    return Number(valueOn(this.dated(), 'terms', date)?.value ?? 14);
  }

  /** A grade's own terms in days in force on a date; null when it has none then (it uses the sheet's). */
  gradeTermsOn(grade: string, date: string | null): number | null {
    const own = valueOn(this.dated(), `terms-${grade}`, date)?.value ?? null;
    return own === null ? null : Number(own);
  }

  /** Sign in by name; resolves to an error message, or null on success. */
  async signIn(name: string): Promise<string | null> {
    try {
      const res = await firstValueFrom(this.http.post<{ name: string }>('/api/session', { name }));
      this.user.set(res.name);
      return null;
    } catch (e) {
      return message(e, 'Enter a name to sign in.');
    }
  }

  /** Load a sheet by id (a new id starts an empty sheet); resolves to an error message, or null. */
  async loadSheet(id: string): Promise<string | null> {
    const user = this.user();
    if (!user) return 'Sign in first.';
    try {
      const res = await firstValueFrom(
        this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/load`, null, {
          headers: { 'X-User': user },
        }),
      );
      this.sheetId.set(res.id);
      this.today.set(res.today);
      this.dated.set(res.dated);
      this.policyDate.set(res.policyDate);
      this.loads.update((n) => n + 1);
      return null;
    } catch (e) {
      return message(e, 'Enter a sheet id without spaces.');
    }
  }

  /** Change the active sheet's today; resolves to an error message, or null. */
  async setToday(today: string): Promise<string | null> {
    const user = this.user();
    const id = this.sheetId();
    if (!user || !id) return 'Load a sheet first.';
    try {
      const res = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/today`, { today }, {
          headers: { 'X-User': user },
        }),
      );
      if (this.sheetId() === res.id) this.today.set(res.today);
      return null;
    } catch (e) {
      return message(e, 'Enter a date as yyyy-mm-dd.');
    }
  }
}

interface SheetView {
  id: string;
  today: string;
  floor: string;
  terms: number;
  latePct: string;
  policyDate: string | null;
  cap: string | null;
  dated: DatedSettings;
}

/** A value recorded for a dated setting: in force from its from-date (yyyy-mm-dd) until the next
 *  one of its key; null for none. */
export interface DatedValue {
  from: string;
  value: string | null;
}

/** Every value recorded for a sheet's dated settings, by key, each key's by from-date. */
export type DatedSettings = Record<string, DatedValue[]>;

/** The value of a dated setting in force on a date: the one recorded from the latest from-date on
 *  or before it; null before every from-date (or with no date). */
export function valueOn(dated: DatedSettings, key: string, date: string | null): DatedValue | null {
  if (date === null) return null;
  let found: DatedValue | null = null;
  for (const d of dated[key] ?? []) {
    if (d.from <= date && (found === null || d.from > found.from)) found = d;
  }
  return found;
}

function message(e: unknown, invalid: string): string {
  return e instanceof HttpErrorResponse && e.status === 400 ? invalid : 'Something went wrong; try again.';
}
