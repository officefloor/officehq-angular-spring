import { Injectable, computed, inject } from '@angular/core';
import { Claim, Claims, bound } from './claims';
import { People, Person, gradeOn } from './people';
import { Periods } from './periods';
import { Rates, hundredths, money } from './rates';
import { Session } from './session';

// The due figures the screen shows, worked out in one place so the people list, the periods list
// and the summary always agree.
@Injectable({ providedIn: 'root' })
export class Figures {
  private readonly claims = inject(Claims);
  private readonly people = inject(People);
  private readonly periods = inject(Periods);
  private readonly rates = inject(Rates);
  private readonly session = inject(Session);

  /** The sum of the figures of a person's claims that count live (not left out by a hold): a settled claim's frozen due, else the
   *  figure their last statement bound it to, else its live due at the rate in force on its date (a
   *  claim with no figure adds nothing). Null when the grade in force on the sheet's today has no rate
   *  there and no claim has a figure. */
  dueCents(p: Person): bigint | null {
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

  /** Each open period's live due-total, by period id: the figures of the claims that count live
   *  whose date falls in it — a settled claim's frozen due, else as its person's last statement bound
   *  it, else its live due, an overdue claim's late charge on top (a claim with no figure adds
   *  nothing). */
  readonly periodDueTotals = computed(() => {
    const grades = new Map(this.people.people().map((p) => [p.id, p.grades]));
    const sums = new Map<number, bigint>();
    for (const c of this.claims.live()) {
      const period = this.periods.of(c.date);
      if (!period) continue;
      let due: bigint | null;
      if (c.settledDue !== null) due = hundredths(c.settledDue);
      else if (bound(c)) due = c.statedDue === null ? null : hundredths(c.statedDue);
      else {
        const grade = grades.get(c.personId);
        due = grade === undefined ? null : this.rates.dueCents(hundredths(c.amount), gradeOn(grade, c.date), c.date, this.claims.roundingDate(c));
      }
      if (due !== null && c.settledDue === null) due += this.claims.lateCents(c, due);
      sums.set(period.id, (sums.get(period.id) ?? 0n) + (due ?? 0n));
    }
    return new Map([...sums].map(([id, sum]) => [id, money(sum)]));
  });
}
