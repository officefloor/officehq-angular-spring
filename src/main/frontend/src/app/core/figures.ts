import { Injectable, computed, inject } from '@angular/core';
import { Claim, Claims, bound, countsAt, statusAt } from './claims';
import { People, Person, gradeOn } from './people';
import { Period, Periods } from './periods';
import { Rates, hundredths, money } from './rates';
import { Session } from './session';

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

  /** Everyone's (capped) due-total added up, skipping those whose grade has no rate, trimmed. */
  readonly dueTotal = computed(() =>
    money(trimmed(this.people.people().map((p) => this.dueCents(p)).filter((d): d is bigint => d !== null))),
  );

  /** The due of everyone's overdue claims that count, added up, trimmed. */
  readonly overdueTotal = computed(() =>
    money(trimmed(this.people.people().map((p) => this.overdueCents(p)))),
  );

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

  /** A claim's figure: its settled due, else the figure its person's last statement bound it to (not
   *  while PENDING), else its live due at the rate in force on its date, an overdue claim's late charge on top;
   *  nothing when it has no figure. */
  figureCents(c: Claim, p: Person): bigint {
    if (c.settledDue !== null) return hundredths(c.settledDue);
    const f = bound(c)
      ? (c.statedDue === null ? 0n : hundredths(c.statedDue))
      : (this.rates.dueCents(hundredths(c.amount), gradeOn(p.grades, c.date), c.date, this.claims.roundingDate(c)) ?? 0n);
    return f + this.claims.lateCents(c, f);
  }

  /** The grade in force on the sheet's today, the one the list shows. */
  gradeToday(p: Person): string {
    const today = this.session.today();
    return today ? gradeOn(p.grades, today) : p.grade;
  }

  /** Each period's live due-total for each person, in cents, by period id then person id: the figures
   *  of the person's claims whose date falls in it and that counted by the status they had at the end of
   *  the period's last day, as their status log records it (one added after then does not count), and
   *  that are not left out now by a hold or a deferral — each its due as shown now: a settled claim's
   *  frozen due, else as its person's last statement bound it, else its live due, an overdue claim's
   *  late charge on top (a claim with no figure adds nothing). */
  private readonly livePersonPeriodCents = computed(() => {
    const grades = new Map(this.people.people().map((p) => [p.id, p.grades]));
    const sums = new Map<number, Map<number, bigint>>();
    for (const c of this.claims.periodCandidates()) {
      const period = this.periods.of(c.date);
      if (!period || !countsAt(statusAt(c, period.to))) continue;
      let due: bigint | null;
      if (c.settledDue !== null) due = hundredths(c.settledDue);
      else if (bound(c)) due = c.statedDue === null ? null : hundredths(c.statedDue);
      else {
        const grade = grades.get(c.personId);
        due = grade === undefined ? null : this.rates.dueCents(hundredths(c.amount), gradeOn(grade, c.date), c.date, this.claims.roundingDate(c));
      }
      if (due !== null && c.settledDue === null) due += this.claims.lateCents(c, due);
      const byPerson = sums.get(period.id) ?? new Map<number, bigint>();
      byPerson.set(c.personId, (byPerson.get(c.personId) ?? 0n) + (due ?? 0n));
      sums.set(period.id, byPerson);
    }
    return sums;
  });

  /** A person's due-total for a period: fixed when it was closed (0.00 for a person added since), else
   *  live. */
  personPeriodDue(period: Period, personId: number): string {
    if (period.closed) return period.closedPersonTotals?.[personId] ?? '0.00';
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
