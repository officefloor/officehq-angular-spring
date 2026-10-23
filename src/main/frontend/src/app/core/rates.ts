import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ClaimView, LateTerms, cents, isCorrection, lateBoundaries, money } from './claims';
import { valueOn } from './session';
import { Grade } from './people';

export interface RateView {
  grade: Grade;
  /** The percent with two decimals, e.g. "12.50". */
  pct: string;
  /** The date (yyyy-mm-dd) the rate applies from; null when it applies from the beginning of time. */
  from: string | null;
  /** The last date (yyyy-mm-dd) the rate applies on; null when it has no end. */
  to: string | null;
  /** How many versions the rate has, its newest in force; null (one) in views kept before versions. */
  versions: number | null;
  /**
   * Where a forward-only rate was saved in the order claims are added: it applies only to claims added
   * after; null (or absent, in views kept before forward-only rates) when it applies to every claim.
   */
  forwardAfter?: number | null;
  /** The rate's older versions, newest first: what a claim added before a forward-only version falls back to. */
  older?: OlderRateView[] | null;
}

/** An older version of a rate (see RateView). */
export interface OlderRateView {
  pct: string;
  to: string | null;
  forwardAfter: number | null;
}

/**
 * A rate as set through the form; a null from-date applies from the beginning of time, a null
 * to-date has no end.
 */
export interface RateForm {
  pct: string;
  from: string | null;
  to: string | null;
}

/**
 * What a candidate rate for a grade would change: how many OPEN and PENDING claims would show a
 * different due, and the sum of the differences (new minus current, two decimals).
 */
export interface RatePreview {
  count: number;
  diff: string;
}

/** What a rate import did: how many rates were set, and the sheet's rates after. */
export interface RateImportView {
  set: number;
  rates: RateView[];
}

/** A from-date as a rate takes it: yyyy-mm-dd. */
export const FROM_DATE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * The rate a grade has in force on a date (yyyy-mm-dd): of its rates with no from-date or one on or
 * before the date, the one with the latest; null when the date is before every rate of the grade, or
 * after that one's to-date. When the sheet has none, its parent's rate for the grade and date, then
 * its parent's parent's, and so on (`inherited`, nearest first). For a claim, `added` is its place in
 * the order claims are added: a forward-only rate saved after it was added is passed over (see seenBy);
 * undefined (the rate in force, as the rates panel shows it) or null (a claim not yet stored) passes none.
 */
export function rateOn(
  rates: RateView[],
  grade: Grade,
  date: string,
  inherited: RateView[][] = [],
  added?: number | null,
): string | null {
  for (const level of [rates, ...inherited]) {
    const seen = level.flatMap((r) => (r.grade === grade ? seenBy(r, added) : []));
    const pct = ownRateOn(seen, grade, date);
    if (pct !== null) return pct;
  }
  return null;
}

/**
 * A rate as a claim added at a place sees it: its newest version unless that is forward-only and saved
 * after the claim was added, else its newest older version that applies to the claim; none when none does.
 */
function seenBy(rate: RateView, added: number | null | undefined): RateView[] {
  const applies = (forwardAfter: number | null | undefined) =>
    forwardAfter === null || forwardAfter === undefined || added === null || added === undefined || added > forwardAfter;
  if (applies(rate.forwardAfter)) return [rate];
  const older = (rate.older ?? []).find((v) => applies(v.forwardAfter));
  return older === undefined ? [] : [{ ...rate, pct: older.pct, to: older.to, forwardAfter: older.forwardAfter, older: [] }];
}

/** The rate a grade has in force on a date among one sheet's rates alone (see rateOn). */
function ownRateOn(rates: RateView[], grade: Grade, date: string): string | null {
  let best: RateView | null = null;
  for (const r of rates) {
    if (r.grade !== grade || (r.from !== null && r.from > date)) continue;
    if (best === null || (r.from !== null && (best.from === null || r.from > best.from))) best = r;
  }
  if (best === null || (best.to !== null && best.to < date)) return null;
  return best.pct;
}

/** Whether two rates are for the same grade and from-date, so one replaces the other. */
export function sameSlot(a: RateView, b: RateView): boolean {
  return a.grade === b.grade && a.from === b.from;
}

/** A percent as a rate takes it: 0.00 or more, with up to two decimals. */
export const PERCENT = /^\d{1,5}(\.\d{1,2})?$/;

/** Shown in place of a due when the grade has no rate. */
export const NO_RATE = 'n/a';

/**
 * What is due on an amount in whole cents at a rate (a two-decimal percent): the amount times the
 * rate divided by 100, rounded half to even (or half up) to whole cents (a negative amount rounds as
 * its opposite does). Worked in integers so it is exact.
 */
export function dueCents(amountCents: number, pct: string, times = 1, halfUp = false): number {
  if (amountCents < 0) return -dueCents(-amountCents, pct, times, halfUp);
  const [whole, frac = ''] = pct.split('.');
  const basisPoints = BigInt(whole) * 100n + BigInt(frac.padEnd(2, '0'));
  const exact = BigInt(amountCents) * basisPoints * BigInt(times);
  const quotient = exact / 10000n;
  const remainder = exact % 10000n;
  const up = remainder > 5000n || (remainder === 5000n && (halfUp || quotient % 2n === 1n));
  return Number(up ? quotient + 1n : quotient);
}

/** What is due on an amount in whole cents at a rate, shown with two decimals; n/a with no rate in force. */
export function due(amountCents: number, pct: string | null): string {
  return pct === null ? NO_RATE : money(dueCents(amountCents, pct));
}

/**
 * A claim's due in whole cents: the due fixed when it was settled (frozen as it was settled), or the
 * due its person's last statement bound it to as shown then, late charge and floor included, or else
 * the greater of the floor (in whole cents) and its figure, with the late charge worked out on that
 * floored figure on top. Its figure is
 * the rate figure an older statement bound it to (see statedWhole), or else its amount
 * at the rate the grade has in force on the claim's date as the claim sees the rates (a forward-only
 * rate saved after it was added passed over); the late charge is, for every full 30 days
 * the claim is overdue, the late percent in force on that boundary date (its due-by plus 30, 60, 90
 * ... days) of the floored figure plus the charges of the boundaries before it (compounding), each rounded
 * like the claim, added up. The floor is applied first (before the late charge); the one
 * given is the one in force on the claim's date. Live figures round half up when `halfUp` (see
 * roundsHalfUp), else half to even. A correction
 * has no floor and no late charge, and a claim whose late charge was waived no late charge. Null when
 * there is no figure.
 */
export function claimDueCents(
  claim: ClaimView,
  rates: RateView[],
  grade: Grade,
  floorCents: number,
  late: LateTerms,
  halfUp = false,
  inherited: RateView[][] = [],
): number | null {
  if (claim.settledDue !== null) return cents(claim.settledDue);
  if (claim.stated && claim.statedWhole) return claim.statedDue === null ? null : cents(claim.statedDue);
  let figure: number | null;
  if (claim.stated) {
    figure = claim.statedDue === null ? null : cents(claim.statedDue);
  } else {
    const pct = rateOn(rates, grade, claim.date, inherited, claim.added ?? null);
    figure = pct === null ? null : dueCents(cents(claim.amount), pct, 1, halfUp);
  }
  if (figure === null || isCorrection(claim)) return figure;
  const floored = Math.max(figure, floorCents);
  const charge = claim.waived ? 0 : lateBoundaries(claim, late)
    .reduce((sum, on) => sum + dueCents(floored + sum, valueOn(late.history, on, '0.00'), 1, halfUp), 0);
  return floored + charge;
}

// The rates for the grades on a sheet, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class RatesApi {
  private readonly http = inject(HttpClient);

  /** The sheet's rates, each grade's by from-date; a grade with no rate is not listed. */
  list(sheet: string): Promise<RateView[]> {
    return firstValueFrom(this.http.get<RateView[]>(this.url(sheet)));
  }

  /**
   * The rates of the sheet's ancestors, nearest first (its parent's, up to three levels up): what a
   * grade with no rate in force on the sheet falls back to.
   */
  inherited(sheet: string): Promise<RateView[][]> {
    return firstValueFrom(this.http.get<RateView[][]>(`${this.url(sheet)}/inherited`));
  }

  /** Set a grade's rate from a date, replacing any it had from that date. */
  set(sheet: string, grade: Grade, form: RateForm): Promise<RateView> {
    return firstValueFrom(this.http.put<RateView>(`${this.url(sheet)}/${grade}`, form));
  }

  /** Set a rate per line of grade,rate,from,to, in order; all or none. */
  importRates(sheet: string, text: string): Promise<RateImportView> {
    return firstValueFrom(this.http.post<RateImportView>(`${this.url(sheet)}/import`, { text }));
  }

  /** Preview a candidate rate applied to a grade on every date; nothing is saved. */
  preview(sheet: string, grade: Grade, pct: string): Promise<RatePreview> {
    return firstValueFrom(
      this.http.get<RatePreview>(`${this.url(sheet)}/${grade}/preview`, { params: { pct } }),
    );
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/rates`;
  }
}
