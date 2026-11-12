import { Injectable, computed, inject } from '@angular/core';
import { Claim, Claims, bound, countsAt, isOpen, statusAt } from './claims';
import { People, Person, gradeOn } from './people';
import { Period, Periods } from './periods';
import { Rates, hundredths, money, rounded } from './rates';
import { Session, addDays } from './session';

/** The statuses claims are counted by, in the order the screen shows them. */
export const STATUSES = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'] as const;

/** People's figures added up, leaving out the single highest and the single lowest when there are
 *  three or more. */
function trimmed(figures: bigint[]): bigint {
  const sum = figures.reduce((s, f) => s + f, 0n);
  if (figures.length < 3) return sum;
  const max = figures.reduce((m, f) => (f > m ? f : m));
  const min = figures.reduce((m, f) => (f < m ? f : m));
  return sum - max - min;
}

// The due figures the screen shows, worked out in one place so the people list, the periods list,
// the summary and the header always agree.
@Injectable({ providedIn: 'root' })
export class Figures {
  private readonly claims = inject(Claims);
  private readonly people = inject(People);
  private readonly periods = inject(Periods);
  private readonly rates = inject(Rates);
  private readonly session = inject(Session);

  /** A person's due-total: the sum of their figures, at most the sheet's cap. Null (n/a) is not capped. */
  dueCents(p: Person): bigint | null {
    const sum = this.sumCents(p);
    const cap = this.capCents();
    return sum !== null && cap !== null && sum > cap ? cap : sum;
  }

  /** How far the sum of a person's figures is above the sheet's cap; 0 when it is not (or is n/a). */
  excessCents(p: Person): bigint {
    const sum = this.sumCents(p);
    const cap = this.capCents();
    return sum !== null && cap !== null && sum > cap ? sum - cap : 0n;
  }

  private capCents(): bigint | null {
    const cap = this.session.cap();
    return cap === null ? null : hundredths(cap);
  }

  /** The sum of the figures of a person's claims that count live (not left out by a hold): a settled claim's frozen due, else the
   *  figure their last statement bound it to, else its live due at the rate in force on its date (a
   *  claim with no figure adds nothing). Null when the grade in force on the sheet's today has no rate
   *  there and no claim has a figure. */
  sumCents(p: Person): bigint | null {
    let sum = 0n;
    let keptFigure = false;
    for (const c of this.claims.live().filter((c) => c.personId === p.id)) {
      if (c.settledDue !== null || (bound(c) && c.statedDue !== null)) keptFigure = true;
      // a claim priced at the grade in force on its date keeps a figure too
      else if (!bound(c) && this.rates.inForce(gradeOn(p.grades, c.date), c.date) !== undefined) keptFigure = true;
      sum += this.figureCents(c, p);
    }
    const today = this.session.today();
    const rated = today ? this.rates.inForce(gradeOn(p.grades, today), today) !== undefined : false;
    return rated || keptFigure ? sum : null;
  }

  /** The due of a person's SETTLED claims that count live, at their frozen figures, corrections included. */
  settledCents(p: Person): bigint {
    return this.claims
      .live()
      .filter((c) => c.personId === p.id && c.status === 'SETTLED')
      .reduce((sum, c) => sum + this.figureCents(c, p), 0n);
  }

  /** The due of a person's OPEN and PENDING claims that count live (not held, deferred or retired-and-excluded). */
  openCents(p: Person): bigint {
    return this.claims
      .live()
      .filter((c) => c.personId === p.id && isOpen(c))
      .reduce((sum, c) => sum + this.figureCents(c, p), 0n);
  }

  /** Everyone's settled-total added up (not trimmed). */
  readonly settledTotal = computed(() =>
    money(this.people.people().reduce((sum, p) => sum + this.settledCents(p), 0n)),
  );

  /** Everyone's open-total added up (not trimmed). */
  readonly openTotal = computed(() =>
    money(this.people.people().reduce((sum, p) => sum + this.openCents(p), 0n)),
  );

  /** Everyone's (capped) due-total added up, skipping those whose grade has no rate, trimmed. */
  readonly dueTotal = computed(() =>
    money(trimmed(this.people.people().map((p) => this.dueCents(p)).filter((d): d is bigint => d !== null))),
  );

  /** The due of everyone's overdue claims that count, added up, trimmed. */
  readonly overdueTotal = computed(() =>
    money(trimmed(this.people.people().map((p) => this.overdueCents(p)))),
  );

  /** Each tag ever used on the sheet, sorted, with the due of the claims that count live and carry it
   *  (a claim with two tags counts in both), not capped or trimmed. */
  readonly tagTotals = computed(() => {
    const byId = new Map(this.people.people().map((p) => [p.id, p]));
    const sums = new Map<string, bigint>(this.claims.usedTags().map((t) => [t, 0n]));
    for (const c of this.claims.live()) {
      const p = byId.get(c.personId);
      if (!p) continue;
      const due = this.figureCents(c, p);
      for (const t of c.tags) sums.set(t, (sums.get(t) ?? 0n) + due);
    }
    return [...sums].map(([tag, cents]) => ({ tag, dueTotal: money(cents) }));
  });

  /** The number of claims in each status, in the order of STATUSES. */
  readonly statusCounts = computed(() => {
    const claims = this.claims.claims();
    return STATUSES.map((status) => ({ status, count: claims.filter((c) => c.status === status).length }));
  });

  /** A person's claims that count live and are overdue. */
  overdue(p: Person): Claim[] {
    return this.claims.live().filter((c) => c.personId === p.id && this.claims.overdue(c));
  }

  /** The due of a person's overdue claims that count, added up. */
  overdueCents(p: Person): bigint {
    return this.overdue(p).reduce((sum, c) => sum + this.figureCents(c, p), 0n);
  }

  /** The due of a person's OPEN and PENDING claims that count live, split by the claim's age on the
   *  sheet's today (the days from its date): 0 to 30 (a claim dated after today too), 31 to 60, and 61
   *  or more. All 0 with no today. */
  agedCents(p: Person): { upTo30: bigint; upTo60: bigint; over60: bigint } {
    const aged = { upTo30: 0n, upTo60: 0n, over60: 0n };
    const today = this.session.today();
    if (!today) return aged;
    const from30 = addDays(today, -30);
    const from60 = addDays(today, -60);
    for (const c of this.claims.live().filter((c) => c.personId === p.id && isOpen(c))) {
      const due = this.figureCents(c, p);
      if (c.date >= from30) aged.upTo30 += due;
      else if (c.date >= from60) aged.upTo60 += due;
      else aged.over60 += due;
    }
    return aged;
  }

  /** A claim's figure: its settled due, else the figure its person's last statement bound it to (not
   *  while PENDING), else its live due at the rate in force on its date, an overdue claim's late charge on top, at
   *  least the floor;
   *  nothing when it has no figure. */
  figureCents(c: Claim, p: Person): bigint {
    if (c.settledDue !== null) return hundredths(c.settledDue);
    const f = bound(c)
      ? (c.statedDue === null ? null : hundredths(c.statedDue))
      : this.rates.dueCents(hundredths(c.amount), gradeOn(p.grades, c.date), c.date, this.claims.roundingDate(c));
    return f === null ? 0n : this.claims.chargedCents(c, f);
  }

  /** What a candidate rate (a percent with up to two decimals) would change if it applied to a grade on
   *  every date: of the OPEN and PENDING claims of people on that grade on the claim's date, not bound
   *  by a statement, how many would show a different due, and the sum of new due minus current due in
   *  cents (a due shown as n/a counting as nothing). Nothing is saved. */
  ratePreview(grade: string, pct: string): { count: number; diffCents: bigint } {
    const people = new Map(this.people.people().map((p) => [p.id, p]));
    const rate = hundredths(pct);
    let count = 0;
    let diffCents = 0n;
    for (const c of this.claims.claims()) {
      const p = people.get(c.personId);
      if (!p || !isOpen(c) || bound(c) || gradeOn(p.grades, c.date) !== grade) continue;
      const roundOn = this.claims.roundingDate(c);
      const now = this.rates.dueCents(hundredths(c.amount), grade, c.date, roundOn);
      const was = now === null ? null : this.claims.chargedCents(c, now);
      const will = this.claims.chargedCents(c, rounded(hundredths(c.amount) * rate, 10000n, this.session.roundsHalfUp(roundOn)));
      if (was === will) continue;
      count++;
      diffCents += will - (was ?? 0n);
    }
    return { count, diffCents };
  }

  /** Whether a person is active: they have an OPEN or PENDING claim that counts live dated within the
   *  30 days up to and including the sheet's today. */
  active(p: Person): boolean {
    const today = this.session.today();
    if (!today) return false;
    const from = addDays(today, -29);
    return this.claims.live().some((c) => c.personId === p.id && isOpen(c) && c.date >= from && c.date <= today);
  }

  /** A person's settled figure: the frozen dues of their SETTLED claims dated within the 90 days up to
   *  and including the sheet's today, added up; 0 with none. */
  recentSettledCents(p: Person): bigint {
    const today = this.session.today();
    if (!today) return 0n;
    const from = addDays(today, -89);
    return this.claims
      .claims()
      .filter((c) => c.personId === p.id && c.status === 'SETTLED' && c.settledDue !== null && c.date >= from && c.date <= today)
      .reduce((sum, c) => sum + hundredths(c.settledDue as string), 0n);
  }

  /** The grade in force on the sheet's today, the one the list shows. */
  gradeToday(p: Person): string {
    const today = this.session.today();
    return today ? gradeOn(p.grades, today) : p.grade;
  }

  /** Each period's live due-total for each person, in cents, by period id then person id: the figures
   *  of the person's claims whose due-by falls in it and that counted by the status they had at the end of
   *  the period's last day, as their status log records it (one added after then does not count), and
   *  that are not left out now by a hold or a deferral — each its due as shown now: a settled claim's
   *  frozen due, else as its person's last statement bound it, else its live due, an overdue claim's
   *  late charge on top (a claim with no figure adds nothing). */
  private readonly livePersonPeriodCents = computed(() => {
    const grades = new Map(this.people.people().map((p) => [p.id, p.grades]));
    const sums = new Map<number, Map<number, bigint>>();
    for (const c of this.claims.periodCandidates()) {
      const period = this.periods.of(c.dueBy);
      if (!period || !countsAt(statusAt(c, period.to))) continue;
      let due: bigint | null;
      if (c.settledDue !== null) due = hundredths(c.settledDue);
      else if (bound(c)) due = c.statedDue === null ? null : hundredths(c.statedDue);
      else {
        const grade = grades.get(c.personId);
        due = grade === undefined ? null : this.rates.dueCents(hundredths(c.amount), gradeOn(grade, c.date), c.date, this.claims.roundingDate(c));
      }
      if (due !== null && c.settledDue === null) due = this.claims.chargedCents(c, due);
      const byPerson = sums.get(period.id) ?? new Map<number, bigint>();
      byPerson.set(c.personId, (byPerson.get(c.personId) ?? 0n) + (due ?? 0n));
      sums.set(period.id, byPerson);
    }
    return sums;
  });

  /** A person's due-total for a period: fixed when it was closed or soft-closed (0.00 for a person added since), else
   *  live. */
  personPeriodDue(period: Period, personId: number): string {
    if (period.closed || period.soft) return period.closedPersonTotals?.[personId] ?? '0.00';
    return money(this.livePersonPeriodCents().get(period.id)?.get(personId) ?? 0n);
  }

  /** Each open period's live due-total, by period id: the sum of the people's live figures for it. */
  readonly periodDueTotals = computed(() => {
    const totals = new Map<number, string>();
    for (const [id, byPerson] of this.livePersonPeriodCents()) {
      totals.set(id, money([...byPerson.values()].reduce((sum, d) => sum + d, 0n)));
    }
    return totals;
  });
}
