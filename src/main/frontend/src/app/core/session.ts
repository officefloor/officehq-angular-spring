import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { GRADES } from './people';

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
  /** The rounding mode (half-up or half-even) in force on the sheet's today; null when none. */
  rounding: string | null;
  /** The period numbering from-date (YYYY-MM-DD); null when off. */
  periodNumberingFrom: string | null;
  /** The terms of each grade that has its own; a grade without uses the sheet's terms. */
  gradeTerms: Record<string, number>;
  /** The most a person's due-total can be, two decimals; null when there is no cap. */
  cap: string | null;
  /** Every value recorded for a dated setting, by key then from-date. */
  settings: DatedSetting[];
  /** The due above which a claim needs a proof to be settled or approved, two decimals; null for none. */
  proofThreshold: string | null;
  /** The due above which a claim cannot be opened with an empty note, two decimals; null for none. */
  noteThreshold: string | null;
  /** The due above which a claim goes to REVIEW when opened, two decimals; null for none. */
  reviewThreshold: string | null;
  /** The permission matrix: the roles typed for an action (comma separated); one not here keeps the
   *  standing rule. */
  permissions: Record<string, string>;
  /** The grace days: a today change closes each open period whose to-date plus these days is before it;
   *  null when off. */
  graceDays: number | null;
  /** The escalation gap: reminding a claim whose previous reminder was at least these many days before the
   *  sheet's today escalates it (once per claim); null when off. */
  escalateAfter: number | null;
  /** The mid score threshold: with the high one set, a person scoring at or above it is MID; null for no
   *  bands. */
  scoreMid: number | null;
  /** The high score threshold: with the mid one set, a person scoring at or above it is HIGH; null for no
   *  bands. */
  scoreHigh: number | null;
  scoreLog: boolean;
  /** The review SLA: a REVIEW claim whose review day plus these many days is before the sheet's today is
   *  late for review; null when none. */
  reviewSla: number | null;
  /** The maximum of open claims per person: a claim is not opened when its person already has this many OPEN
   *  or PENDING claims; null when none. */
  maxOpen: number | null;
  /** The proof kinds, comma separated: a claim above the proof threshold needs a proof of each to be settled
   *  or approved; null when none. */
  proofKinds: string | null;
  /** The default grade: the grade a person added by the quick-add bar, or by an import line with a blank
   *  grade, takes; null for A. */
  defaultGrade: string | null;
  /** The retired grades, in the order retired: none can be chosen for a person. */
  retiredGrades: string[];
  /** Whether the sheet is archived: everything stays visible and every change is refused. */
  archived: boolean;
  /** The entry date (YYYY-MM-DD): the default date of a new claim in place of the sheet's today; null when
   *  off. */
  entryDate: string | null;
  /** The number of the sheet's last bulk settle batch; null before the first. */
  lastSettleBatch: number | null;
  /** The pot shared out among the members of each balanced pool that is in no other pool, two decimals; null for no pot. */
  pot: string | null;
  /** The parent sheet's id: a grade with no rate in force on a date takes the parent's; null for none. */
  parent: string | null;
  /** The month (1 to 12) a fiscal year starts in. */
  fiscalStartMonth: number;
  /** The alert threshold, two decimals; null when off. */
  alertThreshold: string | null;
}

/** The figures of a sheet's descendants (its children, their children and so on), each sheet's as that
 *  sheet shows them, added up; each as it stood at the end of the as-of date while the screen shows one. */
export interface DescendantFigures {
  peopleCount: number;
  /** Two decimals. */
  dueTotal: string;
  /** Two decimals. */
  overdueTotal: string;
  /** The sheet's own figures (as of the as-of date while one is set), which a screen showing the sheet as of a
   *  date consolidates. */
  ownPeopleCount: number;
  ownDueTotal: string;
  ownOverdueTotal: string;
  /** By grade, the due-totals (two decimals) of the people on it, retired people left out; a grade with none absent. */
  gradeDueTotals: Record<string, string>;
  /** By status, the number of claims in it; a status with none absent. */
  statusCounts: Record<string, number>;
  /** The sheet's own, likewise. */
  ownGradeDueTotals: Record<string, string>;
  ownStatusCounts: Record<string, number>;
  /** Each descendant's people, with their names (name, then aliases) and figures, for trimming and counting
   *  over the whole tree. */
  people: TreeMember[];
  /** The sheet's own, likewise. */
  ownPeople: TreeMember[];
  /** The descendants' overdue claims that count, counted. */
  overdueCount: number;
}

/** A person on a sheet of a tree: their name then aliases, their due-total (two decimals; null for n/a) and
 *  overdue-total (two decimals). */
export interface TreeMember {
  names: string[];
  dueTotal: string | null;
  overdueTotal: string;
}

const NO_DESCENDANTS: DescendantFigures = {
  peopleCount: 0,
  dueTotal: '0.00',
  overdueTotal: '0.00',
  ownPeopleCount: 0,
  ownDueTotal: '0.00',
  ownOverdueTotal: '0.00',
  gradeDueTotals: {},
  statusCounts: {},
  ownGradeDueTotals: {},
  ownStatusCounts: {},
  people: [],
  ownPeople: [],
  overdueCount: 0,
};

/** The actions the permission matrix covers. Blank keeps the standing rule: open, settle, void and close by
 *  clerk and steward; reassign and reopen by steward. */
export const PERMISSION_ACTIONS = ['open', 'settle', 'void', 'reassign', 'close', 'reopen'] as const;

/** A value a dated setting (floor, late-pct, terms, terms-<grade>, grace-<grade>, clerk-limit, clerk-limit-<grade>, cap, proof-threshold, proof-threshold-<grade>, note-threshold or rounding) takes from a date
 *  on; null for no limit, no cap, or a grade using the sheet's terms. */
export interface DatedSetting {
  key: string;
  from: string;
  value: string | null;
}

/** What the floor, the late percent and the terms are before every from-date. */
const STARTING: Record<string, string> = { floor: '1.00', 'late-pct': '0.00', terms: '14' };

// The signed-in user (a typed name, no password) and the active sheet. Every request to the
// server carries the user's name in the X-User header.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheetId = signal<string | null>(null);
  /** The sheet's today (YYYY-MM-DD): every date decision and default date uses it, never the clock. */
  readonly today = signal<string | null>(null);
  /** Every value recorded for the sheet's dated settings, each from a date. */
  readonly settings = signal<DatedSetting[]>([]);
  /** The sheet's clerk limit (two decimals) in force on its today; null when there is none. */
  readonly clerkLimit = computed(() => this.onToday('clerk-limit'));
  /** The sheet's floor (two decimals) in force on its today: the least a claim's live due can be. */
  readonly floor = computed(() => this.onToday('floor') ?? STARTING['floor']);
  /** The sheet's cap (two decimals) in force on its today: the most a person's due-total can be; null when
   *  there is none. */
  readonly cap = computed(() => this.onToday('cap'));
  /** The sheet's proof threshold (two decimals) in force on its today: a claim dated then whose due is above
   *  it needs a proof to be settled or approved; null when there is none. */
  readonly proofThreshold = computed(() => this.onToday('proof-threshold'));
  /** The sheet's note threshold (two decimals) in force on its today: a claim dated then whose due is above
   *  it cannot be opened with an empty note; null when there is none. */
  readonly noteThreshold = computed(() => this.onToday('note-threshold'));
  /** The sheet's review threshold (two decimals) in force on its today: a claim dated then whose due is above
   *  it goes to REVIEW when opened, for a steward to review; null when there is none. */
  readonly reviewThreshold = computed(() => this.onToday('review-threshold'));
  /** The sheet's rounding mode (half-up or half-even) in force on its today; null when there is none (a
   *  claim rounds by the policy date). */
  readonly rounding = computed(() => this.onToday('rounding'));
  /** The sheet's terms in force on its today: the number of days after a claim's date it is due by. */
  readonly terms = computed(() => Number(this.onToday('terms') ?? STARTING['terms']));
  /** The terms of each grade that has its own in force on the sheet's today (a claim added for it is due
   *  that many days after its date); a grade without uses the sheet's terms. */
  readonly gradeTerms = computed(() => {
    const out: Record<string, number> = {};
    for (const key of new Set(this.settings().map((s) => s.key))) {
      if (!key.startsWith('terms-')) continue;
      const t = this.onToday(key);
      if (t !== null) out[key.slice('terms-'.length)] = Number(t);
    }
    return out;
  });
  /** The clerk limit (two decimals) of each grade that has its own in force on the sheet's today; a grade
   *  without uses the sheet's. */
  readonly gradeClerkLimit = computed(() => {
    const out: Record<string, string> = {};
    for (const key of new Set(this.settings().map((s) => s.key))) {
      if (!key.startsWith('clerk-limit-')) continue;
      const t = this.onToday(key);
      if (t !== null) out[key.slice('clerk-limit-'.length)] = t;
    }
    return out;
  });
  /** The proof threshold (two decimals) of each grade that has its own in force on the sheet's today; a grade
   *  without uses the sheet's. */
  readonly gradeProofThreshold = computed(() => {
    const out: Record<string, string> = {};
    for (const key of new Set(this.settings().map((s) => s.key))) {
      if (!key.startsWith('proof-threshold-')) continue;
      const t = this.onToday(key);
      if (t !== null) out[key.slice('proof-threshold-'.length)] = t;
    }
    return out;
  });
  /** The grace days of each grade that has some in force on the sheet's today; a grade without has none. */
  readonly gradeGrace = computed(() => {
    const out: Record<string, number> = {};
    for (const key of new Set(this.settings().map((s) => s.key))) {
      if (!key.startsWith('grace-')) continue;
      const g = this.onToday(key);
      if (g !== null) out[key.slice('grace-'.length)] = Number(g);
    }
    return out;
  });
  /** The sheet's late percent (two decimals) in force on its today: what an overdue claim's due gains of its
   *  figure for every full 30 days it is overdue. */
  readonly latePct = computed(() => this.onToday('late-pct') ?? STARTING['late-pct']);
  /** The sheet's policy date (YYYY-MM-DD); null when it is the sheet's today. A claim dated before it
   *  rounds half up, one dated on or after it half to even. */
  readonly policyDate = signal<string | null>(null);
  /** The sheet's period numbering from-date (YYYY-MM-DD); null when off. While the sheet's today is on or
   *  after it, a claim opened is numbered within its period. */
  readonly periodNumberingFrom = signal<string | null>(null);
  /** The permission matrix: the roles typed for each action (comma separated); an action not here keeps the
   *  standing rule. */
  readonly permissions = signal<Record<string, string>>({});
  /** The sheet's grace days; null when off. A today change closes each open period whose to-date plus these
   *  days is before the new today. */
  readonly graceDays = signal<number | null>(null);
  /** The sheet's escalation gap in days; null when off. */
  readonly escalateAfter = signal<number | null>(null);
  /** The sheet's mid score threshold; null for no bands. Not dated. */
  readonly scoreMid = signal<number | null>(null);
  /** The sheet's high score threshold; null for no bands. Not dated. */
  readonly scoreHigh = signal<number | null>(null);
  readonly scoreLog = signal(false);
  /** The sheet's review SLA in days; null when none. */
  readonly reviewSla = signal<number | null>(null);
  /** The sheet's maximum of open claims per person; null when none. Not dated. */
  readonly maxOpen = signal<number | null>(null);
  /** The sheet's proof kinds, comma separated; null when none. */
  readonly proofKinds = signal<string | null>(null);
  /** The sheet's default grade; null for A. Not dated. */
  readonly defaultGrade = signal<string | null>(null);
  /** The sheet's retired grades, in the order retired. Not dated. */
  readonly retiredGrades = signal<string[]>([]);
  /** The sheet's entry date (YYYY-MM-DD); null when off. While set, it is the default date of a new claim
   *  in place of the sheet's today. */
  readonly entryDate = signal<string | null>(null);
  /** The sheet's pot, two decimals; null for no pot. Shared out among the members of each balanced pool in no other pool by
   *  their shares. Not dated. */
  readonly pot = signal<string | null>(null);
  /** The sheet's alert threshold, two decimals; null when off. A change records an ALERT for each person whose
   *  due-total goes from at or below it to above it. Not dated. */
  readonly alertThreshold = signal<string | null>(null);
  /** The parent sheet's id; null for none. A grade with no rate in force on a date on this sheet takes the
   *  parent's, then the parent's parent's, up to three levels up. Not dated. */
  readonly parent = signal<string | null>(null);
  /** The month (1 to 12) the sheet's fiscal year starts in; 1 unless set. A fiscal year is named FY<yyyy> by
   *  the calendar year its first day falls in. Not dated. */
  readonly fiscalStartMonth = signal(1);
  /** Whether the sheet is archived: everything stays visible and every change is refused. */
  readonly archived = signal(false);
  /** The figures of the sheet's descendants as the store held them when the sheet was loaded. */
  readonly descendants = signal<DescendantFigures>(NO_DESCENDANTS);
  /** The number of the sheet's last bulk settle batch; null before the first. */
  readonly lastSettleBatch = signal<number | null>(null);
  /** The date (YYYY-MM-DD) this user's screen shows the sheet as of, while set: every figure as it stood at
   *  the end of that date, the date standing in for the sheet's today, and every change refused; null when
   *  the screen shows the sheet as it is. Kept for this screen only, across sheet loads. */
  readonly asOf = signal<string | null>(null);
  /** The date (YYYY-MM-DD) this user's screen previews the sheet on, while set: every figure over the sheet's
   *  current records as if the sheet's today were that date, while every change still uses the real today
   *  and nothing a change of today does happens; null when not previewing. Kept for this screen only, across
   *  sheet loads. */
  readonly preview = signal<string | null>(null);
  /** The today every figure on the screen is reckoned on: the preview date while one is set, else the sheet's
   *  today. */
  readonly shownToday = computed(() => this.preview() ?? this.today());

  /** The sheet load running now (resolved when none): what reads the loaded rows at once, an export, waits
   *  for it, so a load started by setting or clearing the as-of date is not read half done. */
  private loading: Promise<unknown> = Promise.resolve();

  /** Keep this load as the one running now, and hand it back. */
  trackLoad<T>(load: Promise<T>): Promise<T> {
    this.loading = load.catch(() => null);
    return load;
  }

  /** Once the sheet load running now, if any, is done. */
  async loaded(): Promise<void> {
    let was: Promise<unknown>;
    do {
      was = this.loading;
      await was;
    } while (was !== this.loading);
  }

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
      const descendants = await firstValueFrom(
        this.http.get<DescendantFigures>(`/api/sheets/${encodeURIComponent(sid)}/descendants`, {
          headers: { 'X-User': user },
        }),
      );
      this.sheetId.set(sheet.id);
      this.descendants.set(descendants);
      this.today.set(sheet.today);
      this.settings.set(sheet.settings);
      this.policyDate.set(sheet.policyDate);
      this.periodNumberingFrom.set(sheet.periodNumberingFrom);
      this.permissions.set(sheet.permissions);
      this.graceDays.set(sheet.graceDays);
      this.escalateAfter.set(sheet.escalateAfter);
      this.scoreMid.set(sheet.scoreMid);
      this.scoreHigh.set(sheet.scoreHigh);
      this.scoreLog.set(sheet.scoreLog);
      this.reviewSla.set(sheet.reviewSla);
      this.maxOpen.set(sheet.maxOpen);
      this.proofKinds.set(sheet.proofKinds);
      this.defaultGrade.set(sheet.defaultGrade);
      this.retiredGrades.set(sheet.retiredGrades);
      this.entryDate.set(sheet.entryDate);
      this.pot.set(sheet.pot);
      this.alertThreshold.set(sheet.alertThreshold);
      this.parent.set(sheet.parent);
      this.fiscalStartMonth.set(sheet.fiscalStartMonth);
      this.archived.set(sheet.archived);
      this.lastSettleBatch.set(sheet.lastSettleBatch);
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

  /** Archive the sheet (a steward only); an error message when refused. */
  async archive(): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    if (!user || !sid) return 'Load a sheet first.';
    try {
      const sheet = await firstValueFrom(
        this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/archive`, null, {
          headers: { 'X-User': user },
        }),
      );
      if (this.sheetId() === sheet.id) this.archived.set(sheet.archived);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not archive the sheet (${e.status}).` : 'Could not archive the sheet.';
    }
  }

  /** Clone the sheet to another id never used (a steward only); an error message when refused. */
  async cloneSheet(to: string): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const t = to.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (!t) return 'Enter the id of the sheet to clone to.';
    try {
      await firstValueFrom(
        this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/clone`, { to: t }, {
          headers: { 'X-User': user },
        }),
      );
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not clone the sheet (${e.status}).` : 'Could not clone the sheet.';
    }
  }

  /** Record the sheet's clerk limit (blank: no limit) from a date (blank: the sheet's today); an error message when refused. */
  async setClerkLimit(limit: string, from = ''): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const l = limit.trim();
    if (!user || !sid) return 'Load a sheet first.';
    const d = from.trim();
    if (d && !isDate(d)) return 'Enter a from-date as YYYY-MM-DD, or leave it blank for the sheet\'s today.';
    if (l && !/^\d+(\.\d{1,2})?$/.test(l)) return 'Enter a clerk limit of 0.00 or more, with up to two decimals, or leave it blank.';
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings`, { clerkLimit: l || null, from: d || null }, {
          headers: { 'X-User': user },
        }),
      );
      if (this.sheetId() === sheet.id) this.settings.set(sheet.settings);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Record the sheet's floor from a date (blank: the sheet's today); an error message when refused. */
  async setFloor(floor: string, from = ''): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const f = floor.trim();
    if (!user || !sid) return 'Load a sheet first.';
    const d = from.trim();
    if (d && !isDate(d)) return 'Enter a from-date as YYYY-MM-DD, or leave it blank for the sheet\'s today.';
    if (!/^\d+(\.\d{1,2})?$/.test(f)) return 'Enter a floor of 0.00 or more, with up to two decimals.';
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings/floor`, { floor: f, from: d || null }, {
          headers: { 'X-User': user },
        }),
      );
      if (this.sheetId() === sheet.id) this.settings.set(sheet.settings);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Record the sheet's rounding mode (half-up or half-even; blank: none) from a date (blank: the sheet's
   *  today); an error message when refused. */
  async setRounding(rounding: string, from = ''): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const r = rounding.trim();
    if (!user || !sid) return 'Load a sheet first.';
    const d = from.trim();
    if (d && !isDate(d)) return 'Enter a from-date as YYYY-MM-DD, or leave it blank for the sheet\'s today.';
    if (r && r !== 'half-up' && r !== 'half-even') return 'Enter a rounding mode of half-up or half-even, or leave it blank.';
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings/rounding`, { rounding: r || null, from: d || null }, {
          headers: { 'X-User': user },
        }),
      );
      if (this.sheetId() === sheet.id) this.settings.set(sheet.settings);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Record the sheet's cap (blank: no cap) from a date (blank: the sheet's today); an error message when refused. */
  async setCap(cap: string, from = ''): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const c = cap.trim();
    if (!user || !sid) return 'Load a sheet first.';
    const d = from.trim();
    if (d && !isDate(d)) return 'Enter a from-date as YYYY-MM-DD, or leave it blank for the sheet\'s today.';
    if (c && !/^\d+(\.\d{1,2})?$/.test(c)) return 'Enter a cap of 0.00 or more, with up to two decimals, or leave it blank.';
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings/cap`, { cap: c || null, from: d || null }, {
          headers: { 'X-User': user },
        }),
      );
      if (this.sheetId() === sheet.id) this.settings.set(sheet.settings);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Record the sheet's proof threshold (blank: none) from a date (blank: the sheet's today); an error message
   *  when refused. */
  async setProofThreshold(threshold: string, from = ''): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const t = threshold.trim();
    if (!user || !sid) return 'Load a sheet first.';
    const d = from.trim();
    if (d && !isDate(d)) return 'Enter a from-date as YYYY-MM-DD, or leave it blank for the sheet\'s today.';
    if (t && !/^\d+(\.\d{1,2})?$/.test(t)) {
      return 'Enter a proof threshold of 0.00 or more, with up to two decimals, or leave it blank.';
    }
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(
          `/api/sheets/${encodeURIComponent(sid)}/settings/proof-threshold`,
          { proofThreshold: t || null, from: d || null },
          { headers: { 'X-User': user } },
        ),
      );
      if (this.sheetId() === sheet.id) this.settings.set(sheet.settings);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Record a grade's clerk limit (blank: the grade uses the sheet's) from a date (blank: the sheet's today);
   *  an error message when refused. */
  async setGradeClerkLimit(grade: string, limit: string, from = ''): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const l = limit.trim();
    if (!user || !sid) return 'Load a sheet first.';
    const d = from.trim();
    if (d && !isDate(d)) return 'Enter a from-date as YYYY-MM-DD, or leave it blank for the sheet\'s today.';
    if (l && !/^\d+(\.\d{1,2})?$/.test(l)) {
      return `Enter a clerk limit for grade ${grade} of 0.00 or more, with up to two decimals, or leave it blank.`;
    }
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(
          `/api/sheets/${encodeURIComponent(sid)}/settings/clerk-limit/${encodeURIComponent(grade)}`,
          { clerkLimit: l || null, from: d || null },
          { headers: { 'X-User': user } },
        ),
      );
      if (this.sheetId() === sheet.id) this.settings.set(sheet.settings);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Record a grade's proof threshold (blank: the grade uses the sheet's) from a date (blank: the sheet's
   *  today); an error message when refused. */
  async setGradeProofThreshold(grade: string, threshold: string, from = ''): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const t = threshold.trim();
    if (!user || !sid) return 'Load a sheet first.';
    const d = from.trim();
    if (d && !isDate(d)) return 'Enter a from-date as YYYY-MM-DD, or leave it blank for the sheet\'s today.';
    if (t && !/^\d+(\.\d{1,2})?$/.test(t)) {
      return `Enter a proof threshold for grade ${grade} of 0.00 or more, with up to two decimals, or leave it blank.`;
    }
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(
          `/api/sheets/${encodeURIComponent(sid)}/settings/proof-threshold/${encodeURIComponent(grade)}`,
          { proofThreshold: t || null, from: d || null },
          { headers: { 'X-User': user } },
        ),
      );
      if (this.sheetId() === sheet.id) this.settings.set(sheet.settings);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Record the sheet's note threshold (blank: none) from a date (blank: the sheet's today); an error message
   *  when refused. */
  async setNoteThreshold(threshold: string, from = ''): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const t = threshold.trim();
    if (!user || !sid) return 'Load a sheet first.';
    const d = from.trim();
    if (d && !isDate(d)) return 'Enter a from-date as YYYY-MM-DD, or leave it blank for the sheet\'s today.';
    if (t && !/^\d+(\.\d{1,2})?$/.test(t)) {
      return 'Enter a note threshold of 0.00 or more, with up to two decimals, or leave it blank.';
    }
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(
          `/api/sheets/${encodeURIComponent(sid)}/settings/note-threshold`,
          { noteThreshold: t || null, from: d || null },
          { headers: { 'X-User': user } },
        ),
      );
      if (this.sheetId() === sheet.id) this.settings.set(sheet.settings);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Record the sheet's review threshold (blank: none) from a date (blank: the sheet's today); an error message
   *  when refused. */
  async setReviewThreshold(threshold: string, from = ''): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const t = threshold.trim();
    if (!user || !sid) return 'Load a sheet first.';
    const d = from.trim();
    if (d && !isDate(d)) return 'Enter a from-date as YYYY-MM-DD, or leave it blank for the sheet\'s today.';
    if (t && !/^\d+(\.\d{1,2})?$/.test(t)) {
      return 'Enter a review threshold of 0.00 or more, with up to two decimals, or leave it blank.';
    }
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(
          `/api/sheets/${encodeURIComponent(sid)}/settings/review-threshold`,
          { reviewThreshold: t || null, from: d || null },
          { headers: { 'X-User': user } },
        ),
      );
      if (this.sheetId() === sheet.id) this.settings.set(sheet.settings);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Record the sheet's terms (whole days) from a date (blank: the sheet's today); an error message when refused. */
  async setTerms(terms: string, from = ''): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const t = terms.trim();
    if (!user || !sid) return 'Load a sheet first.';
    const d = from.trim();
    if (d && !isDate(d)) return 'Enter a from-date as YYYY-MM-DD, or leave it blank for the sheet\'s today.';
    if (!/^\d{1,5}$/.test(t) || Number(t) > 36500) return 'Enter terms as a whole number of days, 0 or more.';
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings/terms`, { terms: Number(t), from: d || null }, {
          headers: { 'X-User': user },
        }),
      );
      if (this.sheetId() === sheet.id) this.settings.set(sheet.settings);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Record a grade's own terms (whole days; blank: it uses the sheet's terms) from a date (blank: the
   *  sheet's today); an error message when refused. */
  async setGradeTerms(grade: string, terms: string, from = ''): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const t = terms.trim();
    if (!user || !sid) return 'Load a sheet first.';
    const d = from.trim();
    if (d && !isDate(d)) return 'Enter a from-date as YYYY-MM-DD, or leave it blank for the sheet\'s today.';
    if (t && (!/^\d{1,5}$/.test(t) || Number(t) > 36500)) {
      return `Enter terms for grade ${grade} as a whole number of days, 0 or more, or leave it blank.`;
    }
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings/terms/${encodeURIComponent(grade)}`,
          { terms: t ? Number(t) : null, from: d || null }, { headers: { 'X-User': user } }),
      );
      if (this.sheetId() === sheet.id) this.settings.set(sheet.settings);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Record a grade's grace days (whole days; blank: it has none) from a date (blank: the sheet's today); an
   *  error message when refused. */
  async setGradeGrace(grade: string, days: string, from = ''): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const t = days.trim();
    if (!user || !sid) return 'Load a sheet first.';
    const d = from.trim();
    if (d && !isDate(d)) return 'Enter a from-date as YYYY-MM-DD, or leave it blank for the sheet\'s today.';
    if (t && (!/^\d{1,5}$/.test(t) || Number(t) > 36500)) {
      return `Enter grace days for grade ${grade} as a whole number, 0 or more, or leave it blank.`;
    }
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings/grace/${encodeURIComponent(grade)}`,
          { graceDays: t ? Number(t) : null, from: d || null }, { headers: { 'X-User': user } }),
      );
      if (this.sheetId() === sheet.id) this.settings.set(sheet.settings);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** The value of a dated setting in force on a date (YYYY-MM-DD): the one with the latest from-date on or
   *  before it; null when none is recorded by then, or the one in force is none. */
  valueOn(key: string, date: string): string | null {
    let found: DatedSetting | undefined;
    for (const s of this.settings()) {
      if (s.key === key && s.from <= date && (found === undefined || s.from > found.from)) found = s;
    }
    return found?.value ?? null;
  }

  /** The floor (two decimals) in force on a date: the least a live due of a claim dated then can be. */
  floorOn(date: string): string {
    return this.valueOn('floor', date) ?? STARTING['floor'];
  }

  /** A grade's grace days in force on a date; 0 when it has none then. */
  graceOn(grade: string, date: string): number {
    return Number(this.valueOn('grace-' + grade, date) ?? 0);
  }

  /** The late percent (two decimals) in force on a date: what an overdue claim dated then gains of its
   *  figure for every full 30 days. */
  latePctOn(date: string): string {
    return this.valueOn('late-pct', date) ?? STARTING['late-pct'];
  }

  private onToday(key: string): string | null {
    const today = this.shownToday();
    return today === null ? null : this.valueOn(key, today);
  }

  /** Whether a claim dated on a date rounds half up: by the rounding mode in force on that date; with none,
   *  it is before the policy date (the sheet's today when there is none); otherwise it rounds half to even. */
  roundsHalfUp(date: string): boolean {
    const mode = this.valueOn('rounding', date);
    if (mode === 'half-up') return true;
    if (mode === 'half-even') return false;
    const policy = this.policyDate() ?? this.shownToday();
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

  /** Change the sheet's entry date (blank: off); an error message when refused. */
  async setEntryDate(date: string): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const d = date.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (d && !isDate(d)) return 'Enter an entry date as YYYY-MM-DD, or leave it blank.';
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings/entry-date`, { entryDate: d || null }, {
          headers: { 'X-User': user },
        }),
      );
      if (this.sheetId() === sheet.id) this.entryDate.set(sheet.entryDate);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Set the roles that may take an action, comma separated (blank: the standing rule); an error message
   *  when refused. */
  async setPermission(action: string, roles: string): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const r = roles.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (r && !/^(clerk|steward)(\s*,\s*(clerk|steward))*$/.test(r)) {
      return 'Enter the roles as a comma separated list of clerk and steward, or leave it blank.';
    }
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings/permissions/${encodeURIComponent(action)}`,
          { roles: r }, { headers: { 'X-User': user } }),
      );
      if (this.sheetId() === sheet.id) this.permissions.set(sheet.permissions);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Change the sheet's grace days (blank: off); an error message when refused. */
  async setGraceDays(days: string): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const d = days.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (d && !/^\d{1,5}$/.test(d)) return 'Enter the grace days as a whole number, or leave it blank.';
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings/grace-days`,
          { graceDays: d ? Number(d) : null }, { headers: { 'X-User': user } }),
      );
      if (this.sheetId() === sheet.id) this.graceDays.set(sheet.graceDays);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Change the sheet's escalation gap (blank: off); an error message when refused. */
  async setEscalateAfter(days: string): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const d = days.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (d && !/^\d{1,5}$/.test(d)) return 'Enter the escalation gap as a whole number of days, or leave it blank.';
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings/escalate-after`,
          { escalateAfter: d ? Number(d) : null }, { headers: { 'X-User': user } }),
      );
      if (this.sheetId() === sheet.id) this.escalateAfter.set(sheet.escalateAfter);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Change the sheet's mid or high score threshold (blank: no bands); an error message when refused. */
  async setScoreThreshold(key: 'score-mid' | 'score-high', value: string): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const v = value.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (v && !/^-?\d{1,9}$/.test(v)) return 'Enter the score threshold as a whole number, or leave it blank.';
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings/${key}`,
          { value: v ? Number(v) : null }, { headers: { 'X-User': user } }),
      );
      if (this.sheetId() === sheet.id) {
        this.scoreMid.set(sheet.scoreMid);
        this.scoreHigh.set(sheet.scoreHigh);
      }
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Switch the sheet's score log on ("on") or off (blank); an error message when refused. */
  async setScoreLog(value: string): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const v = value.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (v !== '' && v !== 'on') return 'Enter on to switch the score log on, or leave it blank for off.';
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings/score-log`,
          { value: v }, { headers: { 'X-User': user } }),
      );
      if (this.sheetId() === sheet.id) this.scoreLog.set(sheet.scoreLog);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Change the sheet's maximum of open claims per person (blank: none); an error message when refused. */
  async setMaxOpen(max: string): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const m = max.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (m && !/^\d{1,6}$/.test(m)) return 'Enter the maximum of open claims as a whole number, or leave it blank.';
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings/max-open`,
          { maxOpen: m ? Number(m) : null }, { headers: { 'X-User': user } }),
      );
      if (this.sheetId() === sheet.id) this.maxOpen.set(sheet.maxOpen);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Change the sheet's review SLA (blank: none); an error message when refused. */
  async setReviewSla(days: string): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const d = days.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (d && !/^\d{1,5}$/.test(d)) return 'Enter the review SLA as a whole number of days, or leave it blank.';
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings/review-sla`,
          { reviewSla: d ? Number(d) : null }, { headers: { 'X-User': user } }),
      );
      if (this.sheetId() === sheet.id) this.reviewSla.set(sheet.reviewSla);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Change the sheet's proof kinds (a comma separated list of receipt or photo; blank: none); an error
   *  message when refused. */
  async setProofKinds(kinds: string): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const k = kinds.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (k && !/^(receipt|photo)(\s*,\s*(receipt|photo))*$/.test(k)) {
      return 'Enter the proof kinds as a comma separated list of receipt or photo, or leave it blank.';
    }
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings/proof-kinds`,
          { proofKinds: k || null }, { headers: { 'X-User': user } }),
      );
      if (this.sheetId() === sheet.id) this.proofKinds.set(sheet.proofKinds);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Change the sheet's default grade (one of the grades; blank: A); an error message when refused. */
  async setDefaultGrade(grade: string): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const g = grade.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (g && !(GRADES as readonly string[]).includes(g)) {
      return `Choose a default grade of ${GRADES.join(', ')}, or leave it blank for A.`;
    }
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings/default-grade`,
          { defaultGrade: g || null }, { headers: { 'X-User': user } }),
      );
      if (this.sheetId() === sheet.id) this.defaultGrade.set(sheet.defaultGrade);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Retire a grade (one of the grades, not yet retired); an error message when refused. */
  async retireGrade(grade: string): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    if (!user || !sid) return 'Load a sheet first.';
    if (!(GRADES as readonly string[]).includes(grade)) return `Choose a grade of ${GRADES.join(', ')}.`;
    try {
      const sheet = await firstValueFrom(
        this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/grades/retire`,
          { grade }, { headers: { 'X-User': user } }),
      );
      if (this.sheetId() === sheet.id) this.retiredGrades.set(sheet.retiredGrades);
      return null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 409 && typeof e.error?.message === 'string') return e.error.message;
      return e instanceof HttpErrorResponse ? `Could not retire the grade (${e.status}).` : 'Could not retire the grade.';
    }
  }

  /** Change the sheet's fiscal start month (1 to 12); an error message when refused. */
  async setFiscalStartMonth(month: string): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const m = month.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (!/^\d{1,2}$/.test(m) || Number(m) < 1 || Number(m) > 12) return 'Enter the fiscal start month as 1 to 12.';
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings/fiscal-start-month`,
          { fiscalStartMonth: Number(m) }, { headers: { 'X-User': user } }),
      );
      if (this.sheetId() === sheet.id) this.fiscalStartMonth.set(sheet.fiscalStartMonth);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Change the sheet's pot (blank: no pot); an error message when refused. */
  async setPot(pot: string): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const p = pot.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (p && !/^\d{1,13}(\.\d{1,2})?$/.test(p)) return 'Enter the pot as an amount in two decimals, or leave it blank.';
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings/pot`,
          { pot: p || null }, { headers: { 'X-User': user } }),
      );
      if (this.sheetId() === sheet.id) this.pot.set(sheet.pot);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Change the sheet's alert threshold (blank: off); an error message when refused. */
  async setAlertThreshold(threshold: string): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const t = threshold.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (t && !/^\d{1,13}(\.\d{1,2})?$/.test(t)) {
      return 'Enter the alert threshold as an amount in two decimals, or leave it blank.';
    }
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings/alert-threshold`,
          { alertThreshold: t || null }, { headers: { 'X-User': user } }),
      );
      if (this.sheetId() === sheet.id) this.alertThreshold.set(sheet.alertThreshold);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Change the sheet's parent (blank: none); an error message when refused. */
  async setParent(parent: string): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const p = parent.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (p === sid) return 'A sheet cannot be its own parent.';
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings/parent`,
          { parent: p || null }, { headers: { 'X-User': user } }),
      );
      if (this.sheetId() === sheet.id) this.parent.set(sheet.parent);
      return null;
    } catch (e) {
      if (e instanceof HttpErrorResponse && e.status === 422) {
        return 'The parent must be a loaded sheet that is not this one and does not have this one as an ancestor.';
      }
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Change the sheet's period numbering from-date (blank: off); an error message when refused. */
  async setPeriodNumberingFrom(date: string): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const d = date.trim();
    if (!user || !sid) return 'Load a sheet first.';
    if (d && !isDate(d)) return 'Enter a period numbering date as YYYY-MM-DD, or leave it blank.';
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings/period-numbering-from`,
          { periodNumberingFrom: d || null }, { headers: { 'X-User': user } }),
      );
      if (this.sheetId() === sheet.id) this.periodNumberingFrom.set(sheet.periodNumberingFrom);
      return null;
    } catch (e) {
      return e instanceof HttpErrorResponse ? `Could not save settings (${e.status}).` : 'Could not save settings.';
    }
  }

  /** Record the sheet's late percent from a date (blank: the sheet's today); an error message when refused. */
  async setLatePct(pct: string, from = ''): Promise<string | null> {
    const user = this.user();
    const sid = this.sheetId();
    const p = pct.trim();
    if (!user || !sid) return 'Load a sheet first.';
    const d = from.trim();
    if (d && !isDate(d)) return 'Enter a from-date as YYYY-MM-DD, or leave it blank for the sheet\'s today.';
    if (!/^\d{1,7}(\.\d{1,2})?$/.test(p)) return 'Enter a late percent of 0.00 or more, with up to two decimals.';
    try {
      const sheet = await firstValueFrom(
        this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(sid)}/settings/late-pct`, { latePct: p, from: d || null }, {
          headers: { 'X-User': user },
        }),
      );
      if (this.sheetId() === sheet.id) this.settings.set(sheet.settings);
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
