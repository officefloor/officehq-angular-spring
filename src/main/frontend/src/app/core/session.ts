import { HttpClient } from '@angular/common/http';
import { Injectable, WritableSignal, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** A setting's value from a date (yyyy-mm-dd); from null for its starting value, before every from-date. */
export interface DatedValue {
  from: string | null;
  value: string;
}

/**
 * The value a dated setting has in force on a date (yyyy-mm-dd): the one with the latest from-date
 * on or before it, else the starting value (the one with no from-date), else `start`.
 */
export function valueOn(history: DatedValue[], date: string, start: string): string {
  let best: DatedValue | null = null;
  for (const d of history) {
    if (d.from !== null && d.from > date) continue;
    if (best === null || (d.from !== null && (best.from === null || d.from > best.from))) best = d;
  }
  return best === null ? start : best.value;
}

export interface SheetView {
  id: string;
  /** The sheet's today (yyyy-mm-dd): every date-dependent decision and default date uses it. */
  today: string;
  /** The sheet's floor in force on its today, with two decimals. */
  floor: string;
  /** The sheet's terms in force on its today, in days. */
  terms: number;
  /** The sheet's late percent in force on its today, with two decimals. */
  latePct: string;
  /** The sheet's policy date (yyyy-mm-dd); null when blank, the sheet's today then being the policy date. */
  policyDate: string | null;
  /** The sheet's cap in force on its today, with two decimals: a person's due-total is at most it; null when there is none. */
  cap: string | null;
  /** The floor's values by from-date, its starting value first. */
  floorHistory: DatedValue[];
  /** The late percent's values by from-date, its starting value first. */
  latePctHistory: DatedValue[];
  /** Whether the sheet is archived: everything stays visible but nothing on it may change. */
  archived: boolean;
  /** Each grade's grace values by from-date (a value null when none), its starting value (none) first. */
  graceHistory: Record<string, DatedValue[]>;
  /** The number of the sheet's last settle batch; null (or absent) when there has been none. */
  lastSettleBatch?: number | null;
  /** The sheet's pot with two decimals: split among each balanced pool's members as their cuts; null when there is none. */
  pot?: string | null;
  /** The parent sheet's id: its rates apply where this sheet has none in force; null when there is none. */
  parent?: string | null;
  /** The month, 1 to 12, the sheet's fiscal year starts in. */
  fiscalStartMonth: number;
  /** The rounding mode's values by from-date (a value null when none), its starting value (none) first. */
  roundingHistory?: DatedValue[];
  /** The review SLA in days: a REVIEW claim whose review day plus them is before the sheet's today is late; null when none. */
  reviewSla?: number | null;
  /** The mid score threshold: a person scoring at or above it is MID (while the high one is set too); null when none. */
  scoreMid?: number | null;
  /** The high score threshold: a person scoring at or above it is HIGH (while the mid one is set too); null when none. */
  scoreHigh?: number | null;
  /** The proof kinds, comma separated: a claim above the proof threshold needs a proof of each to be settled; null when none. */
  proofKinds?: string | null;
  /** The sheet's own today, the one changes use: differs from today while a preview date is set. */
  sheetToday?: string | null;
  /** The preview date the figures are read on (today then being it); null when none is set. */
  preview?: string | null;
}

/**
 * The grace days a grade has in force on a date (yyyy-mm-dd), from its grace values by from-date
 * (see valueOn); 0 when it has none.
 */
export function graceOn(graceHistory: Record<string, DatedValue[]>, grade: string, date: string): number {
  const value = valueOn(graceHistory[grade] ?? [], date, '');
  return value ? Number(value) : 0;
}

// The signed-in user (a typed name, no password) and the active sheet. Everything else in the
// app is kept per sheet, so features read the active sheet id from here.
@Injectable({ providedIn: 'root' })
export class Session {
  private readonly http = inject(HttpClient);

  readonly user = signal<string | null>(null);
  readonly sheet = signal<string | null>(null);
  /**
   * The day the active sheet's figures are read on: its today, or the preview date while one is set.
   * Features use it, never the real clock.
   */
  readonly today = signal<string | null>(null);
  /** The active sheet's own today: every change, and every default date of one, uses it, never the preview date. */
  readonly sheetToday = signal<string | null>(null);
  /** The active sheet's floor in force on its today, with two decimals. */
  readonly floor = signal('1.00');
  /** The active sheet's floor by from-date: a claim's live due is at least the one in force on its date. */
  readonly floorHistory = signal<DatedValue[]>([]);
  /** The active sheet's terms in force on its today, in days. */
  readonly terms = signal(14);
  /** The active sheet's late percent in force on its today, with two decimals. */
  readonly latePct = signal('0.00');
  /**
   * The active sheet's late percent by from-date: an overdue claim gains the one in force on its date
   * of its figure every full 30 days.
   */
  readonly latePctHistory = signal<DatedValue[]>([]);
  /**
   * Each grade's grace values by from-date: a claim whose grade (the one in force on its date) has
   * grace in force on that date is overdue, and late, only from its due-by plus those days.
   */
  readonly graceHistory = signal<Record<string, DatedValue[]>>({});
  /**
   * The rounding mode's values by from-date (half-up, half-even, or null for none): a claim whose date
   * has one in force rounds by it, else by the policy date.
   */
  readonly roundingHistory = signal<DatedValue[]>([]);
  /** The active sheet's policy date (yyyy-mm-dd); empty when blank (the sheet's today is then the policy date). */
  readonly policyDate = signal('');
  /** The active sheet's cap in force on its today, with two decimals: a person's due-total is at most it; empty when there is none. */
  readonly cap = signal('');
  /** The active sheet's pot with two decimals: split among each balanced pool's members as their cuts; empty when there is none. */
  readonly pot = signal('');
  /** The active sheet's parent sheet id; empty when there is none. */
  readonly parent = signal('');
  /** The month, 1 to 12, the active sheet's fiscal year starts in. */
  readonly fiscalStartMonth = signal(1);
  /** Whether the active sheet is archived. */
  readonly archived = signal(false);
  /** The active sheet's review SLA in days; empty when there is none. */
  readonly reviewSla = signal('');
  /** The active sheet's mid score threshold; empty when there is none. */
  readonly scoreMid = signal('');
  /** The active sheet's high score threshold; empty when there is none. */
  readonly scoreHigh = signal('');
  /** The active sheet's proof kinds, comma separated; empty when there are none. */
  readonly proofKinds = signal('');
  /** The number of the active sheet's last settle batch; null when there has been none. */
  readonly lastSettleBatch = signal<number | null>(null);
  /**
   * The date (yyyy-mm-dd) this user's screen shows the sheet as of; empty when none is set. While it
   * is set, every list is read as it stood at the end of that date, it stands in for the sheet's today,
   * and every change is refused.
   */
  readonly asOf = signal('');
  /**
   * The date (yyyy-mm-dd) this user's screen shows the sheet's figures as if it were the sheet's today;
   * empty when none is set. The figures are read over the sheet's current records; changes still use
   * the sheet's own today.
   */
  readonly preview = signal('');
  /** Counts sheet loads, so features re-read the store even when the same id is loaded again. */
  readonly loads = signal(0);
  /** The sheet load whose people and claims are on screen; it equals loads once the latest has arrived. */
  readonly loaded = signal(0);
  /** A change of the as-of or preview date whose sheet load is still in flight; null when none is. */
  private asOfLoading: Promise<void> | null = null;

  signIn(name: string): void {
    this.user.set(name);
  }

  /** Load a sheet by id from the store; an id never used before starts an empty sheet. */
  async loadSheet(id: string): Promise<void> {
    const view = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/load`, null),
    );
    this.show(view);
    this.loads.update((n) => n + 1);
  }

  /** Show the active sheet as of a date (empty: as it stands), re-reading it from the store. */
  async setAsOf(date: string): Promise<void> {
    await this.reloadWith(this.asOf, date);
  }

  /** Show the active sheet's figures as if its today were a date (empty: its own today), re-reading it from the store. */
  async setPreview(date: string): Promise<void> {
    await this.reloadWith(this.preview, date);
  }

  /** Set a date this screen reads the sheet with, re-reading it; put back when the read fails. */
  private async reloadWith(day: WritableSignal<string>, date: string): Promise<void> {
    const id = this.sheet();
    if (!id) throw new Error('No sheet is loaded.');
    const was = day();
    day.set(date);
    const loading = this.loadSheet(id);
    this.asOfLoading = loading;
    try {
      await loading;
    } catch (e) {
      day.set(was);
      throw e;
    } finally {
      if (this.asOfLoading === loading) this.asOfLoading = null;
    }
  }

  /**
   * Resolves once the people and claims on screen are those of the latest sheet load, including one
   * started by changing the as-of date, so what is read from the screen is read as of that date.
   */
  async settled(): Promise<void> {
    while (this.asOfLoading) await this.asOfLoading.catch(() => undefined);
    while (this.loaded() !== this.loads()) await new Promise((done) => setTimeout(done, 20));
  }

  /** Change the active sheet's today in the store. */
  async setToday(date: string): Promise<void> {
    const id = this.sheet();
    if (!id) throw new Error('No sheet is loaded.');
    const view = await firstValueFrom(
      this.http.put<SheetView>(`/api/sheets/${encodeURIComponent(id)}/today`, { today: date }),
    );
    this.show(view);
  }

  /** Archive the active sheet in the store. */
  async archive(): Promise<void> {
    const id = this.sheet();
    if (!id) throw new Error('No sheet is loaded.');
    const view = await firstValueFrom(
      this.http.post<SheetView>(`/api/sheets/${encodeURIComponent(id)}/archive`, null),
    );
    this.show(view);
  }

  /** Clone the active sheet to another id, one never used, in the store; answers the id cloned to. */
  async cloneTo(to: string): Promise<string> {
    const id = this.sheet();
    if (!id) throw new Error('No sheet is loaded.');
    const made = await firstValueFrom(
      this.http.post<{ to: string }>(`/api/sheets/${encodeURIComponent(id)}/clone`, { to }),
    );
    return made.to;
  }

  private show(view: SheetView): void {
    this.sheet.set(view.id);
    this.today.set(view.today);
    this.sheetToday.set(view.sheetToday ?? view.today);
    this.floor.set(view.floor);
    this.terms.set(view.terms);
    this.latePct.set(view.latePct);
    this.floorHistory.set(view.floorHistory);
    this.latePctHistory.set(view.latePctHistory);
    this.policyDate.set(view.policyDate ?? '');
    this.roundingHistory.set(view.roundingHistory ?? []);
    this.cap.set(view.cap ?? '');
    this.pot.set(view.pot ?? '');
    this.parent.set(view.parent ?? '');
    this.fiscalStartMonth.set(view.fiscalStartMonth ?? 1);
    this.archived.set(view.archived);
    this.graceHistory.set(view.graceHistory ?? {});
    this.lastSettleBatch.set(view.lastSettleBatch ?? null);
    this.reviewSla.set(view.reviewSla?.toString() ?? '');
    this.scoreMid.set(view.scoreMid?.toString() ?? '');
    this.scoreHigh.set(view.scoreHigh?.toString() ?? '');
    this.proofKinds.set(view.proofKinds ?? '');
  }
}
