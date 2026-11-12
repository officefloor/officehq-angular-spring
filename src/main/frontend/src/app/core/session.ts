import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** A dated setting's value (two decimals or whole days; null: none) from its from-date (yyyy-mm-dd). */
export interface DatedValue {
  from: string;
  value: string | null;
}

/** The sheet's starting values, in force before a key's first from-date: none for a key not listed. */
const STARTING: Record<string, string> = { floor: '1.00', terms: '14', 'late-pct': '0.00' };

interface SheetView {
  id: string;
  today: string;
  archived: boolean;
  viewer: boolean;
  lastSettleBatch: number | null;
  steward: boolean;
  visiblePeople: number[] | null;
  grades: string[];
}

/** The grades every sheet has, before any added to it. */
export const STANDING_GRADES = ['A', 'B', 'C'];

// The signed-in user and the active sheet. Signing in only takes a name; the name is sent with
// every request in the X-User header.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheet = signal<string | null>(null);
  /** The active sheet's real today (yyyy-mm-dd): every change and its default dates use it. */
  readonly sheetToday = signal<string | null>(null);
  /** The date this screen previews the sheet on (yyyy-mm-dd): every figure is shown as if the sheet's today were it,
   *  over the sheet's current records, while changes still use its real today; null shows the sheet under its today.
   *  It belongs to this screen and is kept when another sheet is loaded. */
  readonly preview = signal<string | null>(null);
  /** The today every figure on the screen is shown under: the preview date while it is set, else the sheet's today. */
  readonly today = computed(() => this.preview() ?? this.sheetToday());
  /** Whether the active sheet is archived: everything stays visible and every change is refused. */
  readonly archived = signal(false);
  /** Whether the signed-in user is a viewer of the active sheet, as of its last load: they may change nothing. */
  readonly viewer = signal(false);
  /** Whether the signed-in user is a steward of the active sheet (in their own right or by delegation), as of its last
   *  load. */
  readonly steward = signal(false);
  /** For a restricted user, the ids of the people the people list shows them (their crews' people and themselves), as
   *  of the sheet's last load; null for any other user, who sees everyone. */
  readonly visiblePeople = signal<number[] | null>(null);
  /** The active sheet's grades: A, B and C, then those added to it in the order they were added. */
  readonly grades = signal<string[]>(STANDING_GRADES);
  /** The number of the active sheet's last bulk settle batch; null while there has been none. */
  readonly lastSettleBatch = signal<number | null>(null);
  /** Whether retired people are listed: off when the screen opens, and left as it is by loading a sheet. */
  readonly showRetired = signal(false);
  /** The date this screen shows the sheet as of (yyyy-mm-dd): it stands in for the sheet's today and every change is
   *  refused; null shows the sheet as it stands. It belongs to this screen and is kept when another sheet is loaded. */
  readonly asOf = signal<string | null>(null);
  /** Counts completed sheet loads, so the screen re-reads the sheet's data even for the same id. */
  readonly loaded = signal(0);
  /** Every value recorded of each dated setting of the active sheet (clerk-limit, floor, terms, terms-A, terms-B,
   *  terms-C, late-pct, cap, proof-threshold, note-threshold, review-threshold, rounding), from-date first to last; null until the settings are read. */
  readonly dated = signal<Record<string, DatedValue[]> | null>(null);
  /** The active sheet's floor (two decimals) in force on its today: a live due is at least it; null until the settings are read. */
  readonly floor = computed(() => this.settingOn('floor', this.today()));
  /** The active sheet's terms in force on its today: a claim of a grade without its own is due by its date plus these many days; null until the settings are read. */
  readonly terms = computed(() => {
    const terms = this.settingOn('terms', this.today());
    return terms === null ? null : Number(terms);
  });
  /** The active sheet's late percent (two decimals) in force on its today: an overdue claim's due gains it of its figure every full 30 days. */
  readonly latePct = computed(() => this.settingOn('late-pct', this.today()));
  /** The active sheet's policy date (yyyy-mm-dd): a claim dated before it rounds half up, on or after it half to even;
   *  blank means the sheet's today; null until the settings are read. */
  readonly policyDate = signal<string | null>(null);
  /** The active sheet's entry date (yyyy-mm-dd): while set, the default date of a new claim; blank means off (the
   *  sheet's today); null until the settings are read. */
  readonly entryDate = signal<string | null>(null);
  /** The active sheet's pot (two decimals), shared out over each balanced pool's members by their shares; blank means
   *  none; null until the settings are read. */
  readonly pot = signal<string | null>(null);
  /** The active sheet's parent sheet id, whose rates a grade with none in force falls back on; blank means none;
   *  null until the settings are read. */
  readonly parent = signal<string | null>(null);
  /** The active sheet's review SLA in days: a REVIEW claim whose review day plus it is before the sheet's today is
   *  late; blank means none; null until the settings are read. */
  readonly reviewSla = signal<string | null>(null);
  /** The active sheet's pending SLA in days: a PENDING claim whose pending day plus it is before the sheet's today is
   *  late; blank means none; null until the settings are read. */
  readonly pendingSla = signal<string | null>(null);
  /** The active sheet's mid score threshold (a whole number): while both thresholds are set, a person whose score is at
   *  or above it is MID; blank means none; null until the settings are read. */
  readonly scoreMid = signal<string | null>(null);
  /** The active sheet's high score threshold (a whole number): while both thresholds are set, a person whose score is
   *  at or above it is HIGH; blank means none; null until the settings are read. */
  readonly scoreHigh = signal<string | null>(null);
  /** The default date of a new claim: the entry date while it is set, else the sheet's today. */
  readonly newClaimDate = computed(() => this.entryDate() || this.sheetToday());
  /** The active sheet's cap (two decimals) in force on its today: a person's due-total is at most it; null when there
   *  is none or until the settings are read. */
  readonly cap = computed(() => this.settingOn('cap', this.today()));
  private loads = 0;
  /** The number of sheet loads waiting for their answer. */
  private loading = 0;
  /** The completed load whose data each part of the screen last showed, by part. */
  private readonly shownLoads: Record<string, number> = {};

  signIn(name: string): void {
    this.user.set(name);
    this.asOf.set(null);
    this.preview.set(null);
    this.sheet.set(null);
    this.sheetToday.set(null);
  }

  /**
   * The value of a dated setting in force on the date: the one with the latest from-date on or before it, else the
   * sheet's starting value; null when that is none, or until the settings are read.
   */
  settingOn(key: string, date: string | null): string | null {
    const dated = this.dated();
    if (dated === null || date === null) return null;
    let value: string | null = STARTING[key] ?? null;
    for (const d of dated[key] ?? []) {
      if (d.from <= date) value = d.value;
    }
    return value;
  }

  /** Whether a claim is late for review: in REVIEW, with its review day plus the review SLA before the sheet's today. */
  reviewLate(claim: { status: string; reviewOn: string | null }): boolean {
    const sla = this.reviewSla();
    const today = this.today();
    if (claim.status !== 'REVIEW' || !sla || claim.reviewOn === null || today === null) return false;
    const [y, m, d] = claim.reviewOn.split('-').map(Number);
    const limit = new Date(Date.UTC(y, m - 1, d + Number(sla))).toISOString().slice(0, 10);
    return limit < today;
  }

  /** Whether a claim is late pending: PENDING, with its pending day plus the pending SLA before the sheet's today. */
  pendingLate(claim: { status: string; pendingOn: string | null }): boolean {
    const sla = this.pendingSla();
    const today = this.today();
    if (claim.status !== 'PENDING' || !sla || claim.pendingOn === null || today === null) return false;
    const [y, m, d] = claim.pendingOn.split('-').map(Number);
    const limit = new Date(Date.UTC(y, m - 1, d + Number(sla))).toISOString().slice(0, 10);
    return limit < today;
  }

  /** Request options carrying the signed-in user. */
  headers(): { headers: Record<string, string> } {
    return { headers: { 'X-User': this.user() ?? '' } };
  }

  /** Load a sheet by id; an id never used before starts an empty sheet. */
  async loadSheet(id: string): Promise<void> {
    const load = ++this.loads;
    this.loading++;
    const loaded = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/load`, null, {
        headers: { 'X-User': this.user() ?? '' },
      }),
    ).finally(() => this.loading--);
    // A later load wins over a slower earlier one.
    if (load === this.loads) {
      this.show(loaded);
      this.loaded.update((n) => n + 1);
    }
  }

  /** Record that the part of the screen now shows the data it read for the completed load. */
  shown(part: string, load: number): void {
    this.shownLoads[part] = load;
  }

  /**
   * Resolves once no sheet load is waiting for its answer and each of the parts shows the data read for the latest
   * completed load (so a figure taken from the screen is the sheet as it now stands, or as of the as-of date).
   */
  async settled(...parts: string[]): Promise<void> {
    // A part whose read failed never catches up: after a while, take the screen as it is.
    for (let waited = 0; waited < 10_000; waited += 20) {
      if (this.loading === 0 && parts.every((part) => this.shownLoads[part] === this.loaded())) return;
      await new Promise((resolve) => setTimeout(resolve, 20));
    }
  }

  /** Show the active sheet as of the date (null: as it stands), re-reading all of it. */
  async setAsOf(date: string | null): Promise<void> {
    const id = this.sheet();
    if (!id) throw new Error('No sheet loaded.');
    const was = this.asOf();
    this.asOf.set(date);
    try {
      await this.loadSheet(id);
    } catch (e) {
      this.asOf.set(was);
      throw e;
    }
  }

  /** Preview the active sheet on the date (null: under its today), re-reading all of it. */
  async setPreview(date: string | null): Promise<void> {
    const id = this.sheet();
    if (!id) throw new Error('No sheet loaded.');
    const was = this.preview();
    this.preview.set(date);
    try {
      await this.loadSheet(id);
    } catch (e) {
      this.preview.set(was);
      throw e;
    }
  }

  /** Change the active sheet's today. */
  async setToday(today: string): Promise<void> {
    const id = this.sheet();
    if (!id) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.put<SheetView>(
        `/api/sheets/${encodeURIComponent(id)}/today`,
        { today },
        { headers: { 'X-User': this.user() ?? '' } },
      ),
    );
    if (this.sheet() === saved.id) this.show(saved);
  }

  /** Archive the active sheet: a steward may; every change to it is refused from then on. */
  async archive(): Promise<void> {
    const id = this.sheet();
    if (!id) throw new Error('No sheet loaded.');
    const saved = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/archive`, null, {
        headers: { 'X-User': this.user() ?? '' },
      }),
    );
    if (this.sheet() === saved.id) this.show(saved);
  }

  /** Clone the active sheet to another id never used: a steward may. */
  async cloneSheet(to: string): Promise<void> {
    const id = this.sheet();
    if (!id) throw new Error('No sheet loaded.');
    await firstValueFrom(
      this.http.post<SheetView>(
        `/api/sheets/${encodeURIComponent(id)}/clone`,
        { to },
        { headers: { 'X-User': this.user() ?? '' } },
      ),
    );
  }

  private show(view: SheetView): void {
    this.sheet.set(view.id);
    this.sheetToday.set(view.today);
    this.archived.set(view.archived);
    this.viewer.set(view.viewer);
    this.steward.set(view.steward);
    this.visiblePeople.set(view.visiblePeople);
    this.lastSettleBatch.set(view.lastSettleBatch);
    this.grades.set(view.grades ?? STANDING_GRADES);
  }
}
