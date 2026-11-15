import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Session, otherError } from '../../core/session';
import { Claim, cents, isBound, isCorrection, lateFrom, overdue, plusDays } from '../claims/claims-api';

export { plusDays };

/** A percent of 0.00 or more with up to two decimals. */
export const PCT_PATTERN = /^\d{1,7}(\.\d{1,2})?$/;

export interface Rate {
  grade: string;
  /** The first date (yyyy-mm-dd) the rate is in force; null when it applies from the beginning of time. */
  from: string | null;
  /** The last date (yyyy-mm-dd) the rate applies; null when it applies for good. */
  to: string | null;
  /** Always with two decimals, as the server formats it. */
  pct: string;
  /** How many versions the rate has, the one shown (its newest) being in force; absent on a rate
   *  worked out on the screen rather than read from the server. */
  version?: number;
  /** For a forward-only newest version (saved with a from-date on or after the sheet's today then),
   *  the highest claim id there was when it was saved: only claims added after it apply it; null or
   *  absent when it applies to every claim. */
  afterClaim?: number | null;
  /** The versions a claim added before the newest one applies instead, newest first. */
  earlier?: EarlierRate[];
}

/** An earlier version of a rate as a claim added before its later forward-only one applies it. */
export interface EarlierRate {
  pct: string;
  to: string | null;
  afterClaim: number | null;
}

/** Of rates, each as it applies to a claim added as `added` (its own id, or for a copy in a clone
 *  the claim it copies was added as): a forward-only version saved after it was added gives way to
 *  the newest earlier one that applies to it; a rate none of whose versions does is left out. */
export function ratesForClaim(rates: readonly Rate[], added: number): Rate[] {
  const out: Rate[] = [];
  for (const r of rates) {
    if (r.afterClaim == null || added > r.afterClaim) {
      out.push(r);
      continue;
    }
    const e = (r.earlier ?? []).find((v) => v.afterClaim === null || added > v.afterClaim);
    if (e) out.push({ grade: r.grade, from: r.from, to: e.to, pct: e.pct, version: r.version });
  }
  return out;
}

/** A sheet's own rates and those of each of its ancestors (its parent's first): `inForce` the rates
 *  in force on it as one list, as the rates panel shows them, and `forClaim` the ones a claim
 *  applies, leaving out the forward-only versions saved after it was added. */
export class RateLevels {
  readonly inForce: Rate[];
  /** Every forward-only mark the rates have, lowest first. */
  private readonly marks: number[];
  /** The rates claims apply, by how many of the marks they were added after. */
  private readonly byMark = new Map<number, Rate[]>();

  constructor(private readonly own: readonly Rate[], private readonly inherited: readonly (readonly Rate[])[]) {
    this.inForce = effectiveRates(own, inherited);
    const marks = new Set<number>();
    for (const r of [...own, ...inherited.flat()]) {
      if (r.afterClaim != null) marks.add(r.afterClaim);
      for (const e of r.earlier ?? []) if (e.afterClaim !== null) marks.add(e.afterClaim);
    }
    this.marks = [...marks].sort((a, b) => a - b);
  }

  /** The rates, with what the sheet inherits, as one list, as a claim added as `added` applies them. */
  forClaim(added: number): Rate[] {
    const passed = this.marks.filter((m) => m < added).length;
    if (passed === this.marks.length) return this.inForce;
    let rates = this.byMark.get(passed);
    if (!rates) {
      rates = effectiveRates(ratesForClaim(this.own, added), this.inherited.map((l) => ratesForClaim(l, added)));
      this.byMark.set(passed, rates);
    }
    return rates;
  }
}

/** Of one grade's rates, the one in force on a date (yyyy-mm-dd): the one with the latest from-date
 *  on or before it, an undated rate applying from the beginning of time; null when none applies yet,
 *  or when that one's to-date has passed by then. */
export function pctOn(rates: readonly Rate[], date: string | null): string | null {
  let best: Rate | null = null;
  for (const r of rates) {
    if (r.from !== null && (date === null || r.from > date)) continue;
    if (best === null || (best.from ?? '') < (r.from ?? '')) best = r;
  }
  if (best?.to != null && date !== null && best.to < date) return null;
  return best?.pct ?? null;
}

/** The rates in force on a sheet as one list: for each grade and date, the rate in force then of
 *  the first of the sheet's own rates, then each ancestor's in `inherited` (its parent's first),
 *  that has one. The dates are cut where any of them starts or ends, each piece taking the rate
 *  the levels give it; with nothing inherited it is the sheet's own. */
export function effectiveRates(own: readonly Rate[], inherited: readonly (readonly Rate[])[]): Rate[] {
  if (inherited.every((l) => l.length === 0)) return [...own];
  const levels = [own, ...inherited];
  const grades = [...new Set(levels.flatMap((l) => l.map((r) => r.grade)))].sort();
  const out: Rate[] = [];
  for (const grade of grades) {
    const byLevel = levels.map((l) => l.filter((r) => r.grade === grade));
    const cuts = new Set<string>();
    for (const l of byLevel) {
      for (const r of l) {
        if (r.from !== null) cuts.add(r.from);
        if (r.to !== null) cuts.add(plusDays(r.to, 1));
      }
    }
    const starts: (string | null)[] = [null, ...[...cuts].sort()];
    starts.forEach((start, i) => {
      const next = starts[i + 1] ?? null;
      const end = next === null ? null : plusDays(next, -1);
      const on = start ?? end ?? '1970-01-01';
      for (const l of byLevel) {
        const pct = pctOn(l, on);
        if (pct !== null) {
          out.push({ grade, from: start, to: end, pct });
          break;
        }
      }
    });
  }
  return out;
}

/** What a candidate rate for a grade would change: how many claims' dues, and by how much in cents
 *  (new minus current). */
export interface RatePreview {
  count: number;
  cents: number;
}

// The sheet's rate for each grade, read and written through the server.
@Injectable({ providedIn: 'root' })
export class RatesApi {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  list(sheetId: string): Promise<Rate[]> {
    return firstValueFrom(this.http.get<Rate[]>(this.url(sheetId), { headers: this.headers() }));
  }

  /** The rates of each of the sheet's ancestors as they are now, its parent's first. */
  inherited(sheetId: string): Promise<Rate[][]> {
    return firstValueFrom(this.http.get<Rate[][]>(`${this.url(sheetId)}/inherited`, { headers: this.headers() }));
  }

  set(sheetId: string, grade: string, pct: string, from: string | null, to: string | null): Promise<Rate> {
    return firstValueFrom(
      this.http.put<Rate>(`${this.url(sheetId)}/${encodeURIComponent(grade)}`, { pct, from, to }, { headers: this.headers() }),
    );
  }

  /** Set a rate for each pasted line of grade,rate,from,to, all or none; the rates set, in line order. */
  importRates(sheetId: string, text: string): Promise<Rate[]> {
    return firstValueFrom(this.http.post<Rate[]>(`${this.url(sheetId)}/import`, { text }, { headers: this.headers() }));
  }

  private url(sheetId: string): string {
    return `/api/sheets/${encodeURIComponent(sheetId)}/rates`;
  }

  private headers(): Record<string, string> {
    return { 'X-User': this.session.user() ?? '' };
  }
}

export function rateError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) return 'Choose a grade, enter a percent of 0.00 or more with up to two decimals and a from-date and to-date as yyyy-mm-dd or none, the to-date not before the from-date.';
  if (e instanceof HttpErrorResponse && e.status === 409) return 'The rate was changed at the same moment; try again.';
  return otherError(e);
}

export function importRatesError(e: unknown): string {
  if (e instanceof HttpErrorResponse && e.status === 400) {
    return 'Paste lines of grade,rate,from,to: a grade A, B or C, a percent of 0.00 or more with up to two decimals, and a from-date and to-date as yyyy-mm-dd or blank, the to-date not before the from-date.';
  }
  if (e instanceof HttpErrorResponse && e.status === 409) return 'A rate was changed at the same moment; try again.';
  return otherError(e);
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

/** What a claim's late charge follows: the sheet's today and the late percent in force on each boundary date. */
export interface Lateness {
  today: string | null;
  /** The late percent with two decimals in force on a date. */
  latePctOn: (date: string) => string;
  /** The grace days a claim has (those of its person's grade in force on its date); none when left out. */
  graceOf?: (c: Claim) => number;
}

/** A claim's due in cents: the due fixed when it was settled, else the greater of the sheet's floor in
 *  force on its date and its figure (the rate figure a statement bound it to, else its
 *  amount times its grade's rate in force on its date, rounded half up when `halfUp` (the claim's rounding
 *  policy) else half to even) with its late charge, rounded the same way, on top (a correction is neither
 *  raised to the floor nor charged late); null (n/a) with no figure. */
export function claimDueCents(rates: readonly Rate[], c: Claim, floorCents: number, late: Lateness, halfUp: boolean): number | null {
  if (c.settledDue !== null) return cents(c.settledDue);
  const figure = figureCents(rates, c, halfUp);
  if (figure === null) return null;
  const charged = figure + lateChargeCents(c, figure, late, halfUp);
  return isCorrection(c) ? charged : Math.max(charged, floorCents);
}

function figureCents(rates: readonly Rate[], c: Claim, halfUp: boolean): number | null {
  if (isBound(c)) return c.statedDue === null ? null : cents(c.statedDue);
  const pct = pctOn(rates, c.date);
  return pct === null ? null : dueCents(cents(c.amount), cents(pct), halfUp);
}

/** An overdue claim's late charge in cents: for every full 30 days past its due-by plus its grace days, the late percent
 *  in force on that boundary date (that date plus 30, 60, 90 ... days) of its figure plus the charges of the
 *  boundaries before it (compounding), each rounded by
 *  the claim's rounding policy, added up; 0 when it is not overdue or its late charge is waived. */
function lateChargeCents(c: Claim, figure: number, late: Lateness, halfUp: boolean): number {
  const grace = late.graceOf?.(c) ?? 0;
  if (late.today === null || isCorrection(c) || c.waived || !overdue(c, late.today, grace)) return 0;
  const from = lateFrom(c, grace);
  const days = Math.round((Date.parse(late.today) - Date.parse(from)) / 86_400_000);
  let charge = 0;
  for (let step = 1; step <= Math.floor(days / 30); step++) {
    const pct = cents(late.latePctOn(plusDays(from, step * 30)));
    if (pct !== 0) charge += dueCents(figure + charge, pct, halfUp);
  }
  return charge;
}

