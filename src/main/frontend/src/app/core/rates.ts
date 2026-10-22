import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { ClaimView, LateTerms, cents, isCorrection, latePeriods, money } from './claims';
import { Grade } from './people';

export interface RateView {
  grade: Grade;
  /** The percent with two decimals, e.g. "12.50". */
  pct: string;
  /** The date (yyyy-mm-dd) the rate applies from; null when it applies from the beginning of time. */
  from: string | null;
}

/** A rate as set through the form; a null from-date applies from the beginning of time. */
export interface RateForm {
  pct: string;
  from: string | null;
}

/** A from-date as a rate takes it: yyyy-mm-dd. */
export const FROM_DATE = /^\d{4}-\d{2}-\d{2}$/;

/**
 * The rate a grade has in force on a date (yyyy-mm-dd): of its rates with no from-date or one on or
 * before the date, the one with the latest; null when the date is before every rate of the grade.
 */
export function rateOn(rates: RateView[], grade: Grade, date: string): string | null {
  let best: RateView | null = null;
  for (const r of rates) {
    if (r.grade !== grade || (r.from !== null && r.from > date)) continue;
    if (best === null || (r.from !== null && (best.from === null || r.from > best.from))) best = r;
  }
  return best?.pct ?? null;
}

/** Whether two rates are for the same grade and from-date, so one replaces the other. */
export function sameSlot(a: RateView, b: RateView): boolean {
  return a.grade === b.grade && a.from === b.from;
}

/** A percent as a rate takes it: 0.00 or more, with up to two decimals. */
export const PERCENT = /^\d{1,3}(\.\d{1,2})?$/;

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
 * A claim's due in whole cents: the due fixed when it was settled (its late charge frozen in), or
 * else its figure — the one its person's last statement bound it to, or else its amount at the rate
 * the grade has in force on the claim's date raised to the sheet's floor (in whole cents) when below
 * it — with the late charge on top: the late percent of the figure for every full 30 days the claim
 * is overdue. Live figures round half up when `halfUp` (see roundsHalfUp), else half to even. The
 * charge is never bound by a statement. A correction has no floor and no late charge. Null when there
 * is no figure.
 */
export function claimDueCents(
  claim: ClaimView,
  rates: RateView[],
  grade: Grade,
  floorCents: number,
  late: LateTerms,
  halfUp = false,
): number | null {
  if (claim.settledDue !== null) return cents(claim.settledDue);
  let figure: number | null;
  if (claim.stated) {
    figure = claim.statedDue === null ? null : cents(claim.statedDue);
  } else {
    const pct = rateOn(rates, grade, claim.date);
    const live = pct === null ? null : dueCents(cents(claim.amount), pct, 1, halfUp);
    figure = live === null || isCorrection(claim) ? live : Math.max(live, floorCents);
  }
  if (figure === null || isCorrection(claim)) return figure;
  return figure + dueCents(figure, late.pct, latePeriods(claim, late), halfUp);
}

// The rates for the grades on a sheet, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class RatesApi {
  private readonly http = inject(HttpClient);

  /** The sheet's rates, each grade's by from-date; a grade with no rate is not listed. */
  list(sheet: string): Promise<RateView[]> {
    return firstValueFrom(this.http.get<RateView[]>(this.url(sheet)));
  }

  /** Set a grade's rate from a date, replacing any it had from that date. */
  set(sheet: string, grade: Grade, form: RateForm): Promise<RateView> {
    return firstValueFrom(this.http.put<RateView>(`${this.url(sheet)}/${grade}`, form));
  }

  private url(sheet: string): string {
    return `/api/sheets/${encodeURIComponent(sheet)}/rates`;
  }
}
