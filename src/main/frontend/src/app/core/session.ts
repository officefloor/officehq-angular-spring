import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

interface SheetView {
  id: string;
  today: string;
  floor: string;
  terms: number;
  latePct: string;
  policyDate: string | null;
  cap: string | null;
  dated: Dated;
  archived: boolean;
}

/** A value recorded for a dated setting from a date (yyyy-mm-dd) on; null when none. */
export interface DatedValue {
  from: string;
  value: string | null;
}

/** The values recorded for each dated setting (floor, late-pct, terms, terms-<grade>, clerk-limit, cap,
 * proof-threshold), by key. */
export type Dated = Partial<Record<string, DatedValue[]>>;

/** The values in force before any is recorded: a key without one has none. */
const STARTING: Partial<Record<string, string>> = { floor: '1.00', 'late-pct': '0.00', terms: '14' };

/**
 * What a claim's late charge and rounding depend on: the sheet's late percent in force on a date, its today, and
 * the policy date in force (the sheet's today when blank), before which a claim's live figures round half up.
 */
export interface Late {
  pctOn: (date: string) => string;
  today: string | null;
  policy: string | null;
}

// The signed-in user and the active sheet. Every other feature reads these to scope its calls.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheetId = signal<string | null>(null);
  /** The sheet's today (yyyy-mm-dd): every date decision and default date uses it, never the clock. */
  readonly today = signal<string | null>(null);
  /** The values recorded for the sheet's dated settings, by key. */
  readonly dated = signal<Dated>({});
  /** The sheet's floor in force today, two decimals: a claim's live due is at least the one in force on its date. */
  readonly floor = computed(() => this.valueOn('floor', this.today()) ?? '1.00');
  /** The sheet's terms in days in force today: a claim of a grade without its own is due by its date plus those in force then. */
  readonly terms = computed(() => Number(this.valueOn('terms', this.today()) ?? '14'));
  /** The sheet's late percent in force today, two decimals: an overdue claim's due gains, for each full 30 days, the one in force on that boundary date of its figure. */
  readonly latePct = computed(() => this.valueOn('late-pct', this.today()) ?? '0.00');
  /** The clerk limit in force today, two decimals; null when none. */
  readonly clerkLimit = computed(() => this.valueOn('clerk-limit', this.today()));
  /** The sheet's policy date (yyyy-mm-dd) as set; null when blank, which means the sheet's today. */
  readonly policyDate = signal<string | null>(null);
  /** The sheet's cap in force today, two decimals: a person's due-total is at most it; null when none. */
  readonly cap = computed(() => this.valueOn('cap', this.today()));
  /** The proof threshold in force today, two decimals; null when none. */
  readonly proofThreshold = computed(() => this.valueOn('proof-threshold', this.today()));
  /** The late percent by date, today and policy date together, as a claim's late charge and rounding need them. */
  readonly late = computed<Late>(() => ({
    pctOn: (date) => this.valueOn('late-pct', date) ?? '0.00',
    today: this.today(),
    policy: this.policyDate() ?? this.today(),
  }));
  /** The floor in force on a date, in cents. */
  readonly floorCentsOn = (date: string): number => {
    const [whole, frac = ''] = (this.valueOn('floor', date) ?? '1.00').split('.');
    return Number(whole) * 100 + Number(frac.padEnd(2, '0'));
  };

  /**
   * The key's value in force on the date: the one recorded with the latest from-date on or before it,
   * else its starting value (none for a key without one); null when none.
   */
  valueOn(key: string, date: string | null): string | null {
    let found: DatedValue | undefined;
    for (const d of this.dated()[key] ?? []) {
      if (date !== null && d.from <= date && (found === undefined || d.from > found.from)) found = d;
    }
    return found !== undefined ? found.value : (STARTING[key] ?? null);
  }

  /** Whether a steward has archived the sheet: it stays visible and every change to it is refused. */
  readonly archived = signal(false);

  /** Bumped on every sheet load, so features re-read the sheet from the store even for the same id. */
  readonly loads = signal(0);
  /**
   * The as-of date (yyyy-mm-dd) the signed-in user views the sheet at, or null: while it is set every
   * figure is shown as it stood at the end of that date, with it as the sheet's today, and every change
   * is refused. It belongs to this screen, and is left as it is by loading a sheet.
   */
  readonly asOf = signal<string | null>(null);
  /** Whether retired people are listed: off when the screen opens, left as it is by loading a sheet. */
  readonly showRetired = signal(false);

  async signIn(name: string): Promise<void> {
    const res = await firstValueFrom(this.http.post<{ name: string }>('/api/session', { name }));
    this.user.set(res.name);
  }

  async loadSheet(id: string): Promise<void> {
    const user = this.user();
    if (!user) throw new Error('not signed in');
    const res = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/load`, null, {
        headers: { 'X-User': user },
      }),
    );
    this.sheetId.set(res.id);
    this.today.set(res.today);
    this.dated.set(res.dated);
    this.policyDate.set(res.policyDate);
    this.archived.set(res.archived);
    this.loads.update((n) => n + 1);
  }

  /** View the active sheet as of a date (yyyy-mm-dd), or as it is now (null): it is loaded again. */
  async setAsOf(asOf: string | null): Promise<void> {
    const id = this.sheetId();
    if (!id) throw new Error('no sheet loaded');
    this.asOf.set(asOf);
    await this.loadSheet(id);
  }

  async setToday(today: string): Promise<void> {
    const user = this.user();
    const id = this.sheetId();
    if (!user || !id) throw new Error('no sheet loaded');
    const res = await firstValueFrom(
      this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/today`, { today }, {
        headers: { 'X-User': user },
      }),
    );
    if (this.sheetId() === res.id) this.today.set(res.today);
  }

  async archive(): Promise<void> {
    const user = this.user();
    const id = this.sheetId();
    if (!user || !id) throw new Error('no sheet loaded');
    const res = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/archive`, null, {
        headers: { 'X-User': user },
      }),
    );
    if (this.sheetId() === res.id) this.archived.set(res.archived);
  }
}
