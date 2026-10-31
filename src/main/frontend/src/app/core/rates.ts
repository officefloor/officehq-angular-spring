import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, tap } from 'rxjs';
import { ClaimStatus, cents, isOverdue } from './claims';
import { Grade } from './people';
import { SheetView, Sheets, settingOn } from './sheet';

export interface RateView {
  grade: Grade;
  /** The percent with exactly two decimals, e.g. "12.50". */
  rate: string;
  /** The day it takes effect (yyyy-mm-dd); null when it applies from the beginning of time. */
  from: string | null;
  /** The last day it applies (yyyy-mm-dd); null when it does not end. */
  to: string | null;
}

/** The rate among a grade's rates (in the order they take effect) in force on a date (yyyy-mm-dd):
 *  the one with the latest from-date on or before it, unless the date is after its to-date; undefined
 *  when the date is before them all, or that rate has ended by then (an earlier one does not take over again). */
export function rateOn(gradeRates: readonly RateView[], date: string): string | undefined {
  let inForce: RateView | undefined;
  for (const r of gradeRates) if (r.from === null || r.from <= date) inForce = r;
  return inForce === undefined || (inForce.to !== null && inForce.to < date) ? undefined : inForce.rate;
}

/** Rates in the order they take effect: the one with no from-date first, then by from-date. */
function byFrom(a: RateView, b: RateView): number {
  return a.from === b.from ? 0 : a.from === null ? -1 : b.from === null ? 1 : a.from < b.from ? -1 : 1;
}

/** A rate the form accepts: a percent of 0.00 or more, up to 999.99, with up to two decimals. */
export const RATE_PATTERN = /^\d{1,3}(\.\d{1,2})?$/;

/** The floor a sheet starts with. */
export const DEFAULT_FLOOR = '1.00';

/** The due terms used before a sheet is loaded: its starting floor and no late charge. */
export const DEFAULT_DUE_TERMS: DueTerms = { today: '', policyDate: null, settings: [] };

/** The late percent a sheet starts with. */
export const DEFAULT_LATE_PCT = '0.00';

/** What is shown in place of a due when its grade has no rate. */
export const NOT_AVAILABLE = 'n/a';

/** The due on an amount (in whole cents) at a rate (a percent, e.g. "12.50"), in whole cents:
 *  amount × rate / 100, rounded to the cent half to even, or half up when `halfUp` (a negative amount as its
 *  opposite is, negated). Exact, whatever the size of the amount. */
export function dueCents(amountCents: number, rate: string, halfUp = false): number {
  if (amountCents < 0) return -dueCents(-amountCents, rate, halfUp);
  const [whole, frac = ''] = rate.split('.');
  const basisPoints = BigInt(whole) * 100n + BigInt(frac.padEnd(2, '0').slice(0, 2));
  const product = BigInt(amountCents) * basisPoints;
  const quotient = product / 10000n;
  const remainder = product % 10000n;
  const roundUp = remainder > 5000n || (remainder === 5000n && (halfUp || quotient % 2n === 1n));
  return Number(roundUp ? quotient + 1n : quotient);
}

/** The sheet's settings a claim's due depends on: its dated floors and late percents, its policy date and, for
 *  the late charge, its today. */
export type DueTerms = Pick<SheetView, 'today' | 'policyDate' | 'settings'>;

/** The floor in force on a claim's date (yyyy-mm-dd). */
export function floorOn(sheet: DueTerms, date: string): string {
  return settingOn(sheet.settings, 'floor', date, DEFAULT_FLOOR);
}

/** The late percent in force on a claim's date (yyyy-mm-dd). */
export function latePctOn(sheet: DueTerms, date: string): string {
  return settingOn(sheet.settings, 'late-pct', date, DEFAULT_LATE_PCT);
}

/** Whether a claim whose figures round by this date (yyyy-mm-dd) rounds half up: it is before the sheet's policy
 *  date (its today when it has none); on or after it, it rounds half to even. */
export function roundsHalfUp(date: string, sheet: DueTerms): boolean {
  return date < (sheet.policyDate ?? sheet.today);
}

/** The late charge an OPEN or PENDING claim has gained on its figure (in whole cents) by the sheet's today: the
 *  late percent of the figure for every full 30 days past the day it is due by, rounded as the claim rounds.
 *  A correction (a negative amount) gains none. */
export function lateChargeCents(
  c: { amount: string; date: string; dueBy: string; status: ClaimStatus },
  figureCents: number,
  sheet: DueTerms,
): number {
  if (cents(c.amount) < 0 || !isOverdue(c, sheet.today)) return 0;
  const days = (Date.parse(sheet.today) - Date.parse(c.dueBy)) / 86_400_000;
  const periods = Math.floor(days / 30);
  if (periods <= 0) return 0;
  return dueCents(figureCents * periods, latePctOn(sheet, c.date), roundsHalfUp(c.date, sheet));
}

/** A claim's due in whole cents: the figure fixed when it was settled, else the figure its person's
 *  last statement binds it to (none: n/a), else its amount at its grade's rate in force on its date raised to the
 *  floor in force on its date (a correction is not), with any late charge added on top of it; null when it has none. Its live
 *  figure rounds by `roundsOn`: its date, or for a correction the date of the claim it corrects. */
export function claimDueCents(
  c: { amount: string; date: string; dueBy: string; status: ClaimStatus; settledDue: string | null; stated: boolean; statedDue: string | null },
  gradeRates: readonly RateView[],
  sheet: DueTerms,
  roundsOn: string,
): number | null {
  if (c.settledDue !== null) return cents(c.settledDue);
  let figure: number;
  if (c.stated && c.status !== 'PENDING') {
    // A statement binds the claim to its figure then, or to none (n/a).
    if (c.statedDue === null) return null;
    figure = cents(c.statedDue);
  } else {
    const rate = rateOn(gradeRates, c.date);
    if (rate === undefined) return null;
    const due = dueCents(cents(c.amount), rate, roundsHalfUp(roundsOn, sheet));
    figure = due < 0 ? due : Math.max(due, cents(floorOn(sheet, c.date)));
  }
  return figure + lateChargeCents(c, figure, sheet);
}


// The rates set for each grade on the active sheet, by from-date; a grade with none has no rate.
@Injectable({ providedIn: 'root' })
export class Rates {
  private readonly http = inject(HttpClient);
  private readonly sheets = inject(Sheets);
  private readonly list = signal<RateView[]>([]);
  /** The active sheet's rates of each grade, in the order they take effect. */
  readonly ratesByGrade = computed(() => {
    const byGrade = new Map<Grade, RateView[]>();
    for (const r of [...this.list()].sort(byFrom)) byGrade.set(r.grade, [...(byGrade.get(r.grade) ?? []), r]);
    return byGrade;
  });
  /** The rate of each grade in force on the active sheet's today; a grade with none is missing. */
  readonly byGrade = computed(() => {
    const today = this.sheets.current()?.today ?? '';
    const current = new Map<Grade, string>();
    for (const [grade, rates] of this.ratesByGrade()) {
      const rate = rateOn(rates, today);
      if (rate !== undefined) current.set(grade, rate);
    }
    return current;
  });

  /** Re-read a sheet's rates from the store; null clears them. */
  load(sheetId: string | null): Observable<RateView[]> | null {
    this.list.set([]);
    if (sheetId === null) return null;
    return this.http.get<RateView[]>(this.base(sheetId)).pipe(tap((rates) => this.list.set(rates)));
  }

  /** Set a grade's rate from a date (null: from the beginning of time) through a to-date (null: it does not end),
   *  replacing the one it had from that date. */
  set(sheetId: string, grade: Grade, rate: string, from: string | null, to: string | null): Observable<RateView> {
    return this.http
      .put<RateView>(`${this.base(sheetId)}/${grade}`, { rate, from, to })
      .pipe(
        tap((saved) =>
          this.list.update((list) => [...list.filter((r) => r.grade !== saved.grade || r.from !== saved.from), saved]),
        ),
      );
  }

  private base(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/rates`;
  }
}
