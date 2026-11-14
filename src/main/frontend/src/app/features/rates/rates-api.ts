import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session } from '../../core/session';
import { Claim, cents, dueBy, isBound, isCorrection, overdue } from '../claims/claims-api';

/** A percent of 0.00 or more with up to two decimals. */
export const PCT_PATTERN = /^\d{1,3}(\.\d{1,2})?$/;

export interface Rate {
  grade: string;
  /** The first date (yyyy-mm-dd) the rate is in force; null when it applies from the beginning of time. */
  from: string | null;
  /** Always with two decimals, as the server formats it. */
  pct: string;
}

/** Of one grade's rates, the one in force on a date (yyyy-mm-dd): the one with the latest from-date
 *  on or before it, an undated rate applying from the beginning of time; null when none applies yet. */
export function pctOn(rates: readonly Rate[], date: string | null): string | null {
  let best: Rate | null = null;
  for (const r of rates) {
    if (r.from !== null && (date === null || r.from > date)) continue;
    if (best === null || (best.from ?? '') < (r.from ?? '')) best = r;
  }
  return best?.pct ?? null;
}

// The sheet's rate for each grade, read and written through the server.
@Injectable({ providedIn: 'root' })
export class RatesApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  list(sheetId: string): Promise<Rate[]> {
    return firstValueFrom(this.http.get<Rate[]>(this.url(sheetId), { headers: this.headers() }));
  }

  set(sheetId: string, grade: string, pct: string, from: string | null): Promise<Rate> {
    return firstValueFrom(
      this.http.put<Rate>(`${this.url(sheetId)}/${encodeURIComponent(grade)}`, { pct, from }, { headers: this.headers() }),
    );
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/rates`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}

export function rateError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Choose a grade, enter a percent of 0.00 or more with up to two decimals and a from-date as yyyy-mm-dd or none.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'The rate was changed at the same moment; try again.';
  return 'Something went wrong; try again.';
}

/** Whole cents times a percent (in hundredths of a percent), divided by 100, in whole cents,
 *  rounded half to even, or half up when `halfUp` (a negative product rounded as its size is).
 *  Worked in BigInt so large amounts stay exact. */
export function dueCents(cents: number, pctHundredths: number, halfUp = false): number {
  const product = BigInt(cents) * BigInt(pctHundredths);
  const size = product < 0n ? -product : product;
  const whole = size / 10000n;
  const rest = size % 10000n;
  const rounded = rest > 5000n || (rest === 5000n && (halfUp || whole % 2n === 1n)) ? whole + 1n : whole;
  return Number(product < 0n ? -rounded : rounded);
}

/** What a claim's late charge follows: the sheet's today, terms and late percent. */
export interface Lateness {
  today: string | null;
  terms: number;
  /** The late percent with two decimals. */
  latePct: string;
}

/** A claim's due in cents: the due fixed when it was settled, else its figure (the due a statement
 *  bound it to (unless PENDING), else its amount times its grade's rate in force on its date, rounded half up when
 *  `halfUp` (the claim's rounding policy) else half to even, and raised to the sheet's floor) with
 *  its late charge, rounded the same way, on top (a correction is neither raised to the floor nor
 *  charged late); null (n/a) with no figure. */
export function claimDueCents(rates: readonly Rate[], c: Claim, floorCents: number, late: Lateness, halfUp: boolean): number | null {
  if (c.settledDue !== null) return cents(c.settledDue);
  const figure = figureCents(rates, c, floorCents, halfUp);
  return figure === null ? null : figure + lateChargeCents(c, figure, late, halfUp);
}

function figureCents(rates: readonly Rate[], c: Claim, floorCents: number, halfUp: boolean): number | null {
  if (isBound(c)) return c.statedDue === null ? null : cents(c.statedDue);
  const pct = pctOn(rates, c.date);
  if (pct === null) return null;
  const due = dueCents(cents(c.amount), cents(pct), halfUp);
  return isCorrection(c) ? due : Math.max(due, floorCents);
}

/** An overdue claim's late charge in cents: the late percent of its figure for every full 30 days
 *  past its due-by, rounded by the claim's rounding policy; 0 when it is not overdue. */
function lateChargeCents(c: Claim, figure: number, late: Lateness, halfUp: boolean): number {
  if (late.today === null || isCorrection(c) || !overdue(c, late.terms, late.today)) return 0;
  const days = Math.round((Date.parse(late.today) - Date.parse(dueBy(c, late.terms))) / 86_400_000);
  return dueCents(figure, cents(late.latePct) * Math.floor(days / 30), halfUp);
}
