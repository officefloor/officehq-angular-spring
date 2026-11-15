import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import type { Plan } from '../features/plans/plans-api';
import type { ClaimTemplate } from '../features/templates/templates-api';

// Who is signed in and which sheet is active. Everything else in the app is kept per sheet.
/** The keys the people list can be sorted by on the screen. */
export type PeopleSort = 'added' | 'name' | 'grade' | 'due-total';
/** The columns of the people list a user may switch off on their screen. */
export const PEOPLE_COLUMNS = ['claimed', 'due-total', 'statement-count', 'stated-total', 'statement-date', 'overdue-count',
  'overdue-total', 'patrons', 'backing', 'hold', 'retired', 'terms', 'contact', 'aliases'] as const;
export type PeopleColumn = (typeof PEOPLE_COLUMNS)[number];

@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheetId = signal<string | null>(null);
  /** The active sheet's today (yyyy-mm-dd): every date decision and default date uses it. */
  readonly today = signal<string | null>(null);
  /** Every value recorded for the active sheet's dated settings (its clerk limit, floor, terms,
   *  terms per grade, late percent, cap, proof threshold and proof threshold per grade), by key, each key's by from-date. */
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
  /** The active sheet's note threshold (two decimals) in force on its today, null when there is
   *  none: a claim whose due is above the one in force on its date is opened only with a note. */
  readonly noteThreshold = computed(() => valueOn(this.dated(), 'note-threshold', this.today())?.value ?? null);
  /** The active sheet's review threshold (two decimals) in force on its today, null when there is
   *  none: a claim whose due is above the one in force on its date goes to REVIEW when opened. */
  readonly reviewThreshold = computed(() => valueOn(this.dated(), 'review-threshold', this.today())?.value ?? null);
  /** The active sheet's rounding mode (half-up or half-even) in force on its today, null when
   *  there is none: a claim with one in force on its date rounds its live figures by it, one with
   *  none by the policy date. */
  readonly rounding = computed(() => this.roundingOn(this.today()));
  /** The active sheet's pot (two decimals), null when blank (none): each balanced pool's members
   *  cut it by their shares. Not dated. */
  readonly pot = signal<string | null>(null);
  /** The active sheet's review SLA in days, null when blank (none): a REVIEW claim whose review
   *  day plus them is before its today is late. Not dated. */
  readonly reviewSla = signal<number | null>(null);
  /** The mid score threshold; null when blank. Not dated. */
  readonly scoreMid = signal<number | null>(null);
  /** The high score threshold; null when blank. Not dated. */
  readonly scoreHigh = signal<number | null>(null);
  /** The month (1 to 12) the active sheet's fiscal year starts in; 1 unless set. Not dated. A
   *  fiscal year is named FY<yyyy> by the calendar year its first day falls in. */
  readonly fiscalStartMonth = signal(1);
  /** The active sheet's void reasons, in the order they were added: while there are any, a claim
   *  is voided only with one of them. */
  readonly voidReasons = signal<string[]>([]);
  /** The active sheet's claim templates, by name. */
  readonly templates = signal<ClaimTemplate[]>([]);
  /** The active sheet's plans, in the order they were added, each with how many claims it has made. */
  readonly plans = signal<Plan[]>([]);
  /** Whether the active sheet is archived: everything stays visible and every change is refused. */
  readonly archived = signal(false);
  /** The id of the active sheet's parent, null when none: a grade with no rate of its own in force
   *  on a date takes the parent's (then its parent's, up to three levels up). Not dated. */
  readonly parent = signal<string | null>(null);
  /** The active sheet's proof kinds, comma separated, null when none: while set, a claim whose due
   *  is above the proof threshold needs a proof of each to be settled or approved. Not dated. */
  readonly proofKinds = signal<string | null>(null);
  /** The sheet's default grade: a person the quick-add bar adds is given it; null when blank (A). */
  readonly defaultGrade = signal<string | null>(null);
  /** Whether the people list shows retired people too: off at first, and kept as it is when a
   *  sheet is loaded. */
  readonly showRetired = signal(false);
  /** What this user's screen sorts the people list by: the order they were added at first, and
   *  kept as it is when a sheet is loaded. Only what the screen shows changes. */
  readonly sortBy = signal<PeopleSort>('added');
  /** The people-list columns each user has switched off on this screen, by user name: every column
   *  shows at first, and the choice is kept as it is when a sheet is loaded. Only what the screen shows changes. */
  private readonly hiddenByUser = signal<ReadonlyMap<string, ReadonlySet<PeopleColumn>>>(new Map());
  /** The people-list columns the signed-in user has switched off. */
  readonly hiddenColumns = computed(() => this.hiddenByUser().get(this.user() ?? '') ?? new Set<PeopleColumn>());
  /** The date (yyyy-mm-dd) this user's screen shows the sheet as of, null when it shows the sheet
   *  as it stands: while set, every figure is as it stood at the end of that date (its today that
   *  date) and every change is refused. Kept as it is when a sheet is loaded. */
  readonly asOf = signal<string | null>(null);
  /** The as-of date the sheet on screen was loaded with; null when it shows the sheet as it stands. */
  readonly shownAsOf = signal<string | null>(null);
  /** The signed-in user's locale: how the screen shows figures. en unless chosen, kept per user. */
  readonly locale = signal<UserLocale>('en');
  /** Bumped on every sheet load, so a load of the same id again re-reads what the sheet holds. */
  readonly loads = signal(0);

  /** The fiscal year (FY<yyyy>) a date (yyyy-mm-dd) falls in, by the sheet's fiscal start month. */
  fiscalYearOf(date: string): string {
    const year = Number(date.slice(0, 4));
    return `FY${Number(date.slice(5, 7)) >= this.fiscalStartMonth() ? year : year - 1}`;
  }

  /** The rounding mode (half-up or half-even) in force on a date: a claim dated then rounds its
   *  live figures by it; null when there is none (it rounds by the policy date). */
  roundingOn(date: string | null): string | null {
    return valueOn(this.dated(), 'rounding', date)?.value ?? null;
  }

  /** The floor (two decimals) in force on a date: a live due of a claim dated then is at least it;
   *  the starting 1.00 before every from-date. */
  floorOn(date: string | null): string {
    return valueOn(this.dated(), 'floor', date)?.value ?? '1.00';
  }

  /** The late percent (two decimals) in force on a date: an overdue claim gains it of its figure
   *  for each 30-day boundary past its due-by falling then; the starting 0.00 before every from-date. */
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

  /** A grade's grace days in force on a date; null when it has none then. */
  gradeGraceOn(grade: string, date: string | null): number | null {
    const own = valueOn(this.dated(), `grace-${grade}`, date)?.value ?? null;
    return own === null ? null : Number(own);
  }

  /** A grade's own clerk limit (two decimals) in force on a date; null when it has none then
   *  (a claim in it uses the sheet's). */
  gradeClerkLimitOn(grade: string, date: string | null): string | null {
    return valueOn(this.dated(), `clerk-limit-${grade}`, date)?.value ?? null;
  }

  /** A grade's own proof threshold (two decimals) in force on a date; null when it has none then
   *  (a claim in it uses the sheet's). */
  gradeProofThresholdOn(grade: string, date: string | null): string | null {
    return valueOn(this.dated(), `proof-threshold-${grade}`, date)?.value ?? null;
  }

  /** The grace days a claim dated on a date in a grade has: a claim is overdue only once its
   *  due-by plus them is before the sheet's today; 0 when the grade has none then. */
  graceOn(grade: string, date: string | null): number {
    return this.gradeGraceOn(grade, date) ?? 0;
  }

  /** Show or switch off a people-list column on the signed-in user's screen. */
  showColumn(column: PeopleColumn, shown: boolean): void {
    const user = this.user() ?? '';
    this.hiddenByUser.update((all) => {
      const hidden = new Set(all.get(user) ?? []);
      if (shown) hidden.delete(column); else hidden.add(column);
      return new Map(all).set(user, hidden);
    });
  }

  /** Sign in by name; resolves to an error message, or null on success. */
  async signIn(name: string): Promise<string | null> {
    try {
      const res = await firstValueFrom(this.http.post<{ name: string }>('/api/session', { name }));
      this.user.set(res.name);
      await this.loadLocale();
      return null;
    } catch (e) {
      return message(e, 'Enter a name to sign in.');
    }
  }

  /** Load a sheet by id (a new id starts an empty sheet); resolves to an error message, or null. */
  async loadSheet(id: string): Promise<string | null> {
    const user = this.user();
    if (!user) return 'Sign in first.';
    const asOf = this.asOf();
    try {
      const res = await firstValueFrom(
        this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/load`, null, {
          headers: { 'X-User': user },
        }),
      );
      await this.loadLocale();
      this.sheetId.set(res.id);
      this.today.set(res.today);
      this.dated.set(res.dated);
      this.policyDate.set(res.policyDate);
      this.archived.set(res.archived);
      this.parent.set(res.parent);
      this.proofKinds.set(res.proofKinds);
      this.defaultGrade.set(res.defaultGrade);
      this.shownAsOf.set(asOf);
      this.loads.update((n) => n + 1);
      return null;
    } catch (e) {
      return message(e, 'Enter a sheet id without spaces.');
    }
  }

  /** Re-read the signed-in user's locale (en when it cannot be read). */
  private async loadLocale(): Promise<void> {
    const user = this.user();
    if (!user) return;
    try {
      const res = await firstValueFrom(
        this.http.get<{ locale: UserLocale }>('/api/users/locale', { headers: { 'X-User': user } }),
      );
      this.locale.set(res.locale);
    } catch {
      this.locale.set('en');
    }
  }

  /** Save the signed-in user's locale; resolves to an error message, or null. */
  async setLocale(locale: UserLocale): Promise<string | null> {
    const user = this.user();
    if (!user) return 'Sign in first.';
    try {
      const res = await firstValueFrom(
        this.http.put<{ locale: UserLocale }>('/api/users/locale', { locale }, { headers: { 'X-User': user } }),
      );
      this.locale.set(res.locale);
      return null;
    } catch (e) {
      return message(e, 'Choose en or de.');
    }
  }

  /** Show the active sheet as of a date (null: as it stands), re-reading it; resolves to an error
   *  message, or null. */
  async showAsOf(date: string | null): Promise<string | null> {
    const id = this.sheetId();
    if (!id) return 'Load a sheet first.';
    this.asOf.set(date);
    return this.loadSheet(id);
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

  /** Archive the active sheet (a steward only); resolves to an error message, or null. */
  async archive(): Promise<string | null> {
    const user = this.user();
    const id = this.sheetId();
    if (!user || !id) return 'Load a sheet first.';
    try {
      const res = await firstValueFrom(
        this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/archive`, null, {
          headers: { 'X-User': user },
        }),
      );
      if (this.sheetId() === res.id) this.archived.set(res.archived);
      return null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 409) return 'The sheet is archived already.';
      if (e instanceof HttpErrorResponse && e.status === 403) return 'Only a steward may archive an open sheet.';
      return otherError(e);
    }
  }

  /** Clone the active sheet to another id that was never used (a steward only); resolves to an
   *  error message, or null. */
  async cloneSheet(toSheetId: string): Promise<string | null> {
    const user = this.user();
    const id = this.sheetId();
    if (!user || !id) return 'Load a sheet first.';
    try {
      await firstValueFrom(
        this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/clone`, { toSheetId }, {
          headers: { 'X-User': user },
        }),
      );
      return null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 409) return 'That sheet id is already in use.';
      if (e instanceof HttpErrorResponse && e.status === 403 && !e.headers.has('X-As-Of')) {
        return 'Only a steward may clone an open sheet.';
      }
      return message(e, 'Enter a sheet id without spaces.');
    }
  }
}

/** The locales a user may choose: en shows 1234.56, de shows 1.234,56. */
export const LOCALES = ['en', 'de'] as const;
export type UserLocale = (typeof LOCALES)[number];

interface SheetView {
  id: string;
  today: string;
  floor: string;
  terms: number;
  latePct: string;
  policyDate: string | null;
  cap: string | null;
  dated: DatedSettings;
  archived: boolean;
  parent: string | null;
  proofKinds: string | null;
  defaultGrade: string | null;
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
  return e instanceof HttpErrorResponse && e.status === 400 ? invalid : otherError(e);
}

/** The message for a failure no form names: a refusal (403) while the sheet is shown as of a date
 *  (the server echoes the date), or of a viewer, who may change nothing, else a plain try again. */
export function otherError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 403 && e.headers.has('X-As-Of')) {
    return 'The sheet is shown as of a date: clear the as-of date to change anything.';
  }
  if (e instanceof HttpErrorResponse && e.status === 403) return 'Your role may not change anything on this sheet.';
  return 'Something went wrong; try again.';
}
