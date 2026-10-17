import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { Claim, daysOverdue, isCorrection, overdue, toCents } from './claims';
import { Grade } from './people';
import { Session } from './session';

export interface Rate {
  grade: Grade;
  /** yyyy-mm-dd the rate is in force from; null for a rate in force from the beginning of time. */
  from: string | null;
  /** A percent, always with two decimals, e.g. "12.50". */
  rate: string;
}

/** A rate the store accepts: a percent of 0 or more, below 1000, with at most two decimals. */
export const RATE = /^\d{1,3}(\.\d{1,2})?$/;

/**
 * Cents due on an amount in cents at a rate (a percent with two decimals): amount times rate
 * divided by 100, rounded to whole cents half to even, or half up when `halfUp` (a negative
 * amount's as its size, negated). Exact, as the rate is taken in hundredths.
 */
export function dueCents(cents: number, rate: string, halfUp = false): number {
  if (cents < 0) return -dueCents(-cents, rate, halfUp);
  const product = BigInt(cents) * BigInt(toCents(rate));
  const whole = product / 10000n;
  const rest = product % 10000n;
  return Number(rest > 5000n || (rest === 5000n && (halfUp || whole % 2n === 1n)) ? whole + 1n : whole);
}

/**
 * The rate among these in force on a date (yyyy-mm-dd): the one with the latest from-date on or
 * before it, a rate with no from-date applying from the beginning of time. Undefined when none applies.
 */
export function rateOn(rates: Rate[], date: string): string | undefined {
  let best: Rate | undefined;
  for (const r of rates) {
    if (r.from !== null && r.from > date) continue;
    if (best === undefined || (best.from ?? '') < (r.from ?? '')) best = r;
  }
  return best?.rate;
}

// The rates for each grade on the active sheet, per from-date, read from and written to the store.
@Injectable({ providedIn: 'root' })
export class Rates {
  private readonly http = inject(HttpClient);
  private readonly session = inject(Session);

  readonly all = signal<Rate[]>([]);

  /** Each grade's rates, by grade. */
  readonly ofGrade = computed(() => {
    const out = new Map<Grade, Rate[]>();
    for (const r of this.all()) out.set(r.grade, [...(out.get(r.grade) ?? []), r]);
    return out;
  });

  /** Each grade's rate in force on the sheet's today; a grade with none is absent. */
  readonly byGrade = computed(() => {
    const today = this.session.today() ?? '';
    const out = new Map<Grade, string>();
    for (const [grade, rates] of this.ofGrade()) {
      const rate = rateOn(rates, today);
      if (rate !== undefined) out.set(grade, rate);
    }
    return out;
  });

  /** The grade's rate in force on a date; undefined when none applies. */
  on(grade: Grade, date: string): string | undefined {
    return rateOn(this.ofGrade().get(grade) ?? [], date);
  }

  /**
   * A claim's due in cents: a settled claim's as fixed when it was settled; any other claim's at
   * the grade's rate in force on the claim's date, raised to the sheet's floor; an overdue claim's
   * then gains the late percent of that floored due for every full 30 days it is overdue, rounded
   * like due. A correction's is its amount at the rate, with no floor and no late charge. Every
   * rounding is half up for a claim dated before the sheet's policy date (its today when none is
   * set), half to even otherwise. Undefined (n/a) when there was or is no such rate.
   */
  dueOf(claim: Claim, grade: Grade): number | undefined {
    if (claim.status === 'SETTLED') return claim.settledDue === null ? undefined : toCents(claim.settledDue);
    const rate = this.on(grade, claim.date);
    if (rate === undefined) return undefined;
    const today = this.session.today() ?? '';
    const halfUp = claim.date < (this.session.policyDate() ?? today);
    if (isCorrection(claim)) return dueCents(toCents(claim.amount), rate, halfUp);
    const floor = this.session.floor();
    const floored = Math.max(dueCents(toCents(claim.amount), rate, halfUp), floor === null ? 0 : toCents(floor));
    if (!overdue(claim, today)) return floored;
    const periods = Math.floor(daysOverdue(claim, today) / 30);
    return floored + dueCents(floored * periods, this.session.latePct() ?? '0.00', halfUp);
  }

  async reload(): Promise<void> {
    try {
      this.all.set(await firstValueFrom(this.http.get<Rate[]>(this.url(), { headers: this.headers() })));
    } catch {
      this.all.set([]);
    }
  }

  /** Set a grade's rate from a date (or from the beginning of time), replacing any it had then. */
  async set(grade: Grade, rate: string, from: string | null): Promise<void> {
    const saved = await firstValueFrom(
      this.http.put<Rate>(`${this.url()}/${grade}`, { rate: rate.trim(), from }, { headers: this.headers() }),
    );
    this.all.update((list) => [
      ...list.filter((r) => r.grade !== saved.grade || r.from !== saved.from),
      saved,
    ]);
  }

  private url(): string {
    const id = this.session.sheetId();
    if (!id) throw new Error('no sheet loaded');
    return `/api/sheets/${encodeURIComponent(id)}/rates`;
  }

  private headers(): Record<string, string> {
    const user = this.session.user();
    if (!user) throw new Error('not signed in');
    return { 'X-User': user };
  }
}
