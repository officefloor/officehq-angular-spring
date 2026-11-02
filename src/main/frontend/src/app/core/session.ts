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
  lastBatch: number | null;
  reviewSla: number | null;
  role: Role;
  scoreMid: number | null;
  scoreHigh: number | null;
}

/** The role a user acts in on a sheet. */
export type Role = 'clerk' | 'steward' | 'viewer' | 'restricted';

/** The people-list columns a user can switch off on their screen. */
export const PEOPLE_COLUMNS = [
  'claimed', 'due-total', 'excess', 'statement-count', 'stated-total', 'statement-date', 'overdue-count',
  'overdue-total', 'patrons', 'backing', 'hold', 'retired', 'terms', 'contact', 'aliases',
] as const;
export type PeopleColumn = (typeof PEOPLE_COLUMNS)[number];

/** A value recorded for a dated setting from a date (yyyy-mm-dd) on; null when none. */
export interface DatedValue {
  from: string;
  value: string | null;
}

/** The values recorded for each dated setting (floor, late-pct, terms, terms-<grade>, grace-<grade>, clerk-limit, clerk-limit-<grade>, cap,
 * proof-threshold, proof-threshold-<grade>, note-threshold, review-threshold, rounding), by key. */
export type Dated = Partial<Record<string, DatedValue[]>>;

/** The values in force before any is recorded: a key without one has none. */
const STARTING: Partial<Record<string, string>> = { floor: '1.00', 'late-pct': '0.00', terms: '14' };

/**
 * What a claim's late charge and rounding depend on: the sheet's late percent in force on a date, its today,
 * the rounding mode in force on a date (half-up or half-even; null when none), and the policy date in force
 * (the sheet's today when blank), before which a claim with no rounding mode in force rounds half up.
 */
export interface Late {
  pctOn: (date: string) => string;
  today: string | null;
  policy: string | null;
  roundingOn: (date: string) => string | null;
}

/** A claim template on the sheet: a claim made from it takes its amount (two decimals) and note then. */
export interface ClaimTemplate {
  id: number;
  name: string;
  amount: string;
  note: string;
}

/** A person on the sheet as other sections need them: who they are and whether they are retired. */
export interface PersonRef {
  id: number;
  name: string;
  /** Their name as the screen shows it: <name> (<grade in force on the sheet's today>). */
  shown: string;
  retired: boolean;
}

// The signed-in user and the active sheet. Every other feature reads these to scope its calls.
/** What the people list can be sorted by on the screen. */
export type PeopleSort = 'added' | 'name' | 'grade' | 'due-total';

/** The locales a user can choose: under de a figure shows as 1.234,56, under en as 1234.56. */
export type Locale = 'en' | 'de';
export const LOCALES: readonly Locale[] = ['en', 'de'];

@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  /** The signed-in user's locale, saved per user: how the screen shows them figures (en unless chosen). */
  readonly locale = signal<Locale>('en');
  readonly sheetId = signal<string | null>(null);
  /** The role the signed-in user acts in on the sheet on its today (as of the as-of date while one is set). */
  readonly role = signal<Role | null>(null);
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
  /** The sheet's cap in force today, two decimals: a person's due-total is at most it less their cut total (not below 0.00); null when none. */
  readonly cap = computed(() => this.valueOn('cap', this.today()));
  /** The proof threshold in force today, two decimals; null when none. */
  readonly proofThreshold = computed(() => this.valueOn('proof-threshold', this.today()));
  /** The note threshold in force today, two decimals; null when none. */
  readonly noteThreshold = computed(() => this.valueOn('note-threshold', this.today()));
  /** The review threshold in force today, two decimals; null when none. */
  readonly reviewThreshold = computed(() => this.valueOn('review-threshold', this.today()));
  /** The late percent by date, today and policy date together, as a claim's late charge and rounding need them. */
  readonly late = computed<Late>(() => ({
    pctOn: (date) => this.valueOn('late-pct', date) ?? '0.00',
    today: this.today(),
    policy: this.policyDate() ?? this.today(),
    roundingOn: (date) => this.valueOn('rounding', date) || null,
  }));
  /** The sheet's rounding mode in force today (half-up or half-even); null when none, when the policy date decides. */
  readonly rounding = computed(() => this.valueOn('rounding', this.today()) || null);
  /** The grade's grace days in force on a date: a claim of a person of that grade then is overdue only past its due-by plus them; 0 when none. */
  graceOn(grade: string, date: string): number {
    return Number(this.valueOn(`grace-${grade}`, date) ?? '0');
  }
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

  /**
   * The sheet's void reasons, in the order they were added: while any are on the list, a claim is voided
   * only with one of them. Kept by the void reasons section as it reads and adds them.
   */
  readonly voidReasons = signal<string[]>([]);

  /** The sheet's claim templates, by name. Kept by the templates section as it reads and saves them. */
  readonly templates = signal<ClaimTemplate[]>([]);

  /** The people on the sheet, in the order they were added. Kept by the people section as it reads and changes them. */
  readonly people = signal<PersonRef[]>([]);

  /** Whether a steward has archived the sheet: it stays visible and every change to it is refused. */
  readonly archived = signal(false);

  /** The number of the sheet's last bulk settle batch, or null while there has been none. */
  readonly lastBatch = signal<number | null>(null);
  /** The proof kinds (not dated), comma separated: a claim above the proof threshold needs a proof of each; empty when none. */
  readonly proofKinds = signal('');
  /** The review SLA in days (not dated): a REVIEW claim whose review day plus it is before today is late for review; null when none. */
  readonly reviewSla = signal<number | null>(null);
  /** The score thresholds (not dated, whole numbers); null when blank. */
  readonly scoreMid = signal<number | null>(null);
  readonly scoreHigh = signal<number | null>(null);
  /** The band of a score: HIGH at or above score high, MID at or above score mid, else LOW; blank while either is. */
  bandOf(score: number): string {
    const mid = this.scoreMid();
    const high = this.scoreHigh();
    if (mid === null || high === null) return '';
    return score >= high ? 'HIGH' : score >= mid ? 'MID' : 'LOW';
  }
  /** Whether a claim is late for review: REVIEW, and its review day plus the review SLA is before the sheet's today. */
  reviewLate(claim: { status: string; reviewOn: string | null }): boolean {
    const sla = this.reviewSla();
    const today = this.today();
    if (claim.status !== 'REVIEW' || sla === null || today === null || claim.reviewOn === null) return false;
    return Math.round((Date.parse(today) - Date.parse(claim.reviewOn)) / 86_400_000) > sla;
  }

  /** The id of the last sheet this one was cloned to from this screen; null when none since it was loaded. */
  readonly clonedTo = signal<string | null>(null);

  /** Bumped on every sheet load, so features re-read the sheet from the store even for the same id. */
  readonly loads = signal(0);

  /** The id of the sheet this one names as its parent, as the settings last read it; null when none. */
  readonly parent = signal<string | null>(null);
  /** The month (1 to 12) a fiscal year starts in, as the settings last read it; 1 unless set. */
  readonly fiscalStartMonth = signal(1);
  /** Bumped each time the pot is saved, so the pools re-read their cuts. */
  readonly potSaves = signal(0);
  /** Bumped each time a hold is put on or the sheet's today changes, so the pools re-read their cuts. */
  readonly holdChanges = signal(0);
  /** Bumped each time a crew or a crew member is added, so the pools re-read the crews' pools. */
  readonly crewChanges = signal(0);
  /**
   * Each person's cut total in cents, by person id: their cuts as the pools show them, across every pool
   * they are a member of, added up (a pool that shows no cuts adds nothing). Kept by the pools section.
   */
  readonly cutTotals = signal<ReadonlyMap<number, number>>(new Map());
  /**
   * Each person's due-total in cents as the people section shows it, by person id; null when it shows n/a.
   * Kept by the people section.
   */
  readonly dueTotals = signal<ReadonlyMap<number, number | null>>(new Map());
  /**
   * Each crew's lead (person id; null when no longer on the sheet) and due-total in cents as the crews
   * section shows it. Kept by the crews section.
   */
  readonly crewTotals = signal<readonly { leadId: number | null; cents: number }[]>([]);
  /** Each crew's people (person ids): its lead, when still on the sheet, and its members. Kept by the crews section. */
  readonly crewPeople = signal<readonly (readonly number[])[]>([]);
  /**
   * The as-of date (yyyy-mm-dd) the signed-in user views the sheet at, or null: while it is set every
   * figure is shown as it stood at the end of that date, with it as the sheet's today, and every change
   * is refused. It belongs to this screen, and is left as it is by loading a sheet.
   */
  readonly asOf = signal<string | null>(null);
  /** Whether retired people are listed: off when the screen opens, left as it is by loading a sheet. */
  readonly showRetired = signal(false);
  /** What the people list is sorted by on this screen: added (list order) when the screen opens, left as it is by loading a sheet. */
  readonly sortBy = signal<PeopleSort>('added');
  /** The people-list columns switched off on this screen: none when the screen opens, left as they are by loading a sheet. */
  readonly hiddenColumns = signal<ReadonlySet<PeopleColumn>>(new Set());

  /** Switch a people-list column on or off on this screen. */
  showColumn(column: PeopleColumn, shown: boolean): void {
    this.hiddenColumns.update((hidden) => {
      const next = new Set(hidden);
      if (shown) next.delete(column);
      else next.add(column);
      return next;
    });
  }

  async signIn(name: string): Promise<void> {
    const res = await firstValueFrom(this.http.post<{ name: string; locale: Locale }>('/api/session', { name }));
    this.user.set(res.name);
    this.locale.set(res.locale);
  }

  /** Save the signed-in user's locale; it holds for them on every sheet. */
  async setLocale(locale: Locale): Promise<void> {
    const user = this.user();
    if (!user) throw new Error('not signed in');
    const res = await firstValueFrom(
      this.http.put<{ name: string; locale: Locale }>('/api/session/locale', { locale }, {
        headers: { 'X-User': user },
      }),
    );
    if (this.user() === res.name) this.locale.set(res.locale);
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
    this.lastBatch.set(res.lastBatch);
    this.reviewSla.set(res.reviewSla);
    this.scoreMid.set(res.scoreMid);
    this.scoreHigh.set(res.scoreHigh);
    this.role.set(res.role);
    this.clonedTo.set(null);
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
    if (this.sheetId() === res.id) {
      this.today.set(res.today);
      this.role.set(res.role);
      this.holdChanges.update((n) => n + 1);
    }
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

  /** Clone the active sheet to another id, one never used; a steward only. */
  async cloneTo(to: string): Promise<void> {
    const user = this.user();
    const id = this.sheetId();
    if (!user || !id) throw new Error('no sheet loaded');
    this.clonedTo.set(null);
    const res = await firstValueFrom(
      this.http.post<{ to: string }>(`/api/sheets/${encodeURIComponent(id)}/clone`, { to }, {
        headers: { 'X-User': user },
      }),
    );
    if (this.sheetId() === id) this.clonedTo.set(res.to);
  }
}
