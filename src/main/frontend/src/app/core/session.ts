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
}

// The signed-in user and the active sheet. Signing in only takes a name; the name is sent with
// every request in the X-User header.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheet = signal<string | null>(null);
  /** The active sheet's today (yyyy-mm-dd): every date-dependent decision and default uses it. */
  readonly today = signal<string | null>(null);
  /** Whether the active sheet is archived: everything stays visible and every change is refused. */
  readonly archived = signal(false);
  /** Whether the signed-in user is a viewer of the active sheet, as of its last load: they may change nothing. */
  readonly viewer = signal(false);
  /** Whether retired people are listed: off when the screen opens, and left as it is by loading a sheet. */
  readonly showRetired = signal(false);
  /** The date this screen shows the sheet as of (yyyy-mm-dd): it stands in for the sheet's today and every change is
   *  refused; null shows the sheet as it stands. It belongs to this screen and is kept when another sheet is loaded. */
  readonly asOf = signal<string | null>(null);
  /** Counts completed sheet loads, so the screen re-reads the sheet's data even for the same id. */
  readonly loaded = signal(0);
  /** Every value recorded of each dated setting of the active sheet (clerk-limit, floor, terms, terms-A, terms-B,
   *  terms-C, late-pct, cap, proof-threshold, note-threshold), from-date first to last; null until the settings are read. */
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
  /** The default date of a new claim: the entry date while it is set, else the sheet's today. */
  readonly newClaimDate = computed(() => this.entryDate() || this.today());
  /** The active sheet's cap (two decimals) in force on its today: a person's due-total is at most it; null when there
   *  is none or until the settings are read. */
  readonly cap = computed(() => this.settingOn('cap', this.today()));
  private loads = 0;

  signIn(name: string): void {
    this.user.set(name);
    this.asOf.set(null);
    this.sheet.set(null);
    this.today.set(null);
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

  /** Request options carrying the signed-in user. */
  headers(): { headers: Record<string, string> } {
    return { headers: { 'X-User': this.user() ?? '' } };
  }

  /** Load a sheet by id; an id never used before starts an empty sheet. */
  async loadSheet(id: string): Promise<void> {
    const load = ++this.loads;
    const loaded = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/load`, null, {
        headers: { 'X-User': this.user() ?? '' },
      }),
    );
    // A later load wins over a slower earlier one.
    if (load === this.loads) {
      this.show(loaded);
      this.loaded.update((n) => n + 1);
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

  private show(view: SheetView): void {
    this.sheet.set(view.id);
    this.today.set(view.today);
    this.archived.set(view.archived);
    this.viewer.set(view.viewer);
  }
}
