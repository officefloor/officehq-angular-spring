import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
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
  /** The active sheet's today; features use it, never the real clock. */
  readonly today = signal<string | null>(null);
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
  /** The active sheet's policy date (yyyy-mm-dd); empty when blank (the sheet's today is then the policy date). */
  readonly policyDate = signal('');
  /** The active sheet's cap in force on its today, with two decimals: a person's due-total is at most it; empty when there is none. */
  readonly cap = signal('');
  /** Whether the active sheet is archived. */
  readonly archived = signal(false);
  /**
   * The date (yyyy-mm-dd) this user's screen shows the sheet as of; empty when none is set. While it
   * is set, every list is read as it stood at the end of that date, it stands in for the sheet's today,
   * and every change is refused.
   */
  readonly asOf = signal('');
  /** Counts sheet loads, so features re-read the store even when the same id is loaded again. */
  readonly loads = signal(0);
  /** The sheet load whose people and claims are on screen; it equals loads once the latest has arrived. */
  readonly loaded = signal(0);

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
    const id = this.sheet();
    if (!id) throw new Error('No sheet is loaded.');
    const was = this.asOf();
    this.asOf.set(date);
    try {
      await this.loadSheet(id);
    } catch (e) {
      this.asOf.set(was);
      throw e;
    }
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

  private show(view: SheetView): void {
    this.sheet.set(view.id);
    this.today.set(view.today);
    this.floor.set(view.floor);
    this.terms.set(view.terms);
    this.latePct.set(view.latePct);
    this.floorHistory.set(view.floorHistory);
    this.latePctHistory.set(view.latePctHistory);
    this.policyDate.set(view.policyDate ?? '');
    this.cap.set(view.cap ?? '');
    this.archived.set(view.archived);
    this.graceHistory.set(view.graceHistory ?? {});
  }
}
