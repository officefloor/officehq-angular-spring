import { Injectable, computed, inject } from '@angular/core';
import { Claim, Claims, bound, countsAt, isOpen, statusAt } from './claims';
import { CrewView, Crews } from './crews';
import { People, Person, gradeOn } from './people';
import { Period, Periods } from './periods';
import { NO_RATE, Rates, hundredths, money, rounded } from './rates';
import { Roles } from './roles';
import { Session, addDays } from './session';

/** The statuses claims are counted by, in the order the screen shows them. */
export const STATUSES = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID', 'REVIEW'] as const;

/** People's figures added up, leaving out the single highest and the single lowest when there are
 *  three or more. */
export function trimmed(figures: bigint[]): bigint {
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
  private readonly crews = inject(Crews);
  private readonly people = inject(People);
  private readonly periods = inject(Periods);
  private readonly rates = inject(Rates);
  private readonly roles = inject(Roles);
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
      else if (!bound(c) && this.rates.inForceFor(gradeOn(p.grades, c.date), c.date, c.id) !== undefined) keptFigure = true;
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

  /** The due of a person's OPEN and PENDING claims that count live (not deferred or retired-and-excluded). */
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

  /** The person whose name or an alias is the signed-in user's name, compared as names are (trimmed,
   *  ignoring case); undefined when no one on the sheet has it. */
  private readonly me = computed(() => {
    const user = this.session.user()?.trim().toLowerCase();
    if (!user) return undefined;
    return this.people
      .people()
      .find((p) => p.name.trim().toLowerCase() === user || p.aliases.some((a) => a.trim().toLowerCase() === user));
  });

  /** The signed-in user's own figures: the due-total (n/a when it has none) and overdue-total of the
   *  person with the user's name; null when no one on the sheet has it. */
  readonly mine = computed(() => {
    const p = this.me();
    if (!p) return null;
    const due = this.dueCents(p);
    return { dueTotal: due === null ? NO_RATE : money(due), overdueTotal: money(this.overdueCents(p)) };
  });

  /** Whether the signed-in user is a steward on the sheet: given the role, or delegated it for the sheet's
   *  today (under an as-of date, the roles and delegations as they stood then). */
  private readonly steward = computed(() => {
    const user = this.session.user();
    const today = this.session.today();
    if (!user) return false;
    if (this.roles.roles().some((r) => r.user === user && r.role === 'steward')) return true;
    return today !== null && this.roles.delegations().some((d) => d.user === user && d.from <= today && today <= d.to);
  });

  /** The ids of the people a restricted user's people list shows: the person with the user's name and
   *  everyone in a crew they lead or belong to; null when the signed-in user is not restricted (a user
   *  delegated the steward role acts as a steward). Every other figure is still over everyone. */
  readonly visibleIds = computed(() => {
    const user = this.session.user();
    if (!user || this.steward()) return null;
    if (!this.roles.roles().some((r) => r.user === user && r.role === 'restricted')) return null;
    const ids = new Set<number>();
    const p = this.me();
    if (!p) return ids;
    ids.add(p.id);
    for (const c of this.crews.crews()) {
      const crew = [...(c.lead ? [c.lead.personId] : []), ...c.members.map((m) => m.personId)];
      if (crew.includes(p.id)) crew.forEach((id) => ids.add(id));
    }
    return ids;
  });

  /** A steward's dashboard: the sheet's PENDING and REVIEW claims and its overdue claims that count,
   *  counted; null when the signed-in user is not a steward. */
  readonly dashboard = computed(() => {
    if (!this.steward()) return null;
    const claims = this.claims.claims();
    return {
      pending: claims.filter((c) => c.status === 'PENDING').length,
      review: claims.filter((c) => c.status === 'REVIEW').length,
      overdue: this.claims.live().filter((c) => this.claims.overdue(c)).length,
    };
  });

  /** A crew's due-total: the lead's and the members' due-totals as the people list shows them, added up,
   *  n/a skipped. */
  crewDueCents(c: CrewView): bigint {
    const ids = new Set([...(c.lead ? [c.lead.personId] : []), ...c.members.map((m) => m.personId)]);
    let sum = 0n;
    for (const p of this.people.people()) {
      if (!ids.has(p.id)) continue;
      const d = this.dueCents(p);
      if (d !== null) sum += d;
    }
    return sum;
  }

  /** The signed-in user's crew total: the due-totals of the crews the person with the user's name leads,
   *  added up; null when there is no such person or they lead no crew. */
  readonly myCrewDueTotal = computed(() => {
    const p = this.me();
    if (!p) return null;
    const led = this.crews.crews().filter((c) => c.lead?.personId === p.id);
    if (led.length === 0) return null;
    return money(led.reduce((sum, c) => sum + this.crewDueCents(c), 0n));
  });

  /** Each tag ever used on the sheet (on a claim or a person), sorted, with the due of the claims that
   *  count live and carry it, or whose person carries it, once each (a claim with two tags counts in both),
   *  not capped or trimmed. */
  readonly tagTotals = computed(() => {
    const byId = new Map(this.people.people().map((p) => [p.id, p]));
    const used = [...new Set([...this.claims.usedTags(), ...this.people.people().flatMap((p) => p.tags)])].sort();
    const sums = new Map<string, bigint>(used.map((t) => [t, 0n]));
    for (const c of this.claims.live()) {
      const p = byId.get(c.personId);
      if (!p) continue;
      const due = this.figureCents(c, p);
      for (const t of new Set([...c.tags, ...p.tags])) sums.set(t, (sums.get(t) ?? 0n) + due);
    }
    return [...sums].map(([tag, cents]) => ({ tag, dueTotal: money(cents) }));
  });

  /** Each calendar month (yyyy-mm) any claim on the sheet is dated in, in order, with the due of the
   *  claims dated in it that count live, not capped or trimmed. */
  readonly monthTotals = computed(() => {
    const byId = new Map(this.people.people().map((p) => [p.id, p]));
    const months = [...new Set(this.claims.claims().map((c) => c.date.slice(0, 7)))].sort();
    const sums = new Map<string, bigint>(months.map((m) => [m, 0n]));
    for (const c of this.claims.live()) {
      const p = byId.get(c.personId);
      const month = c.date.slice(0, 7);
      if (!p || !sums.has(month)) continue;
      sums.set(month, sums.get(month)! + this.figureCents(c, p));
    }
    return [...sums].map(([month, cents]) => ({ month, dueTotal: money(cents) }));
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

  /** The sheet's age buckets: every person's agedCents added up, not trimmed, each with two decimals. */
  readonly agedTotals = computed(() => {
    const sum = { upTo30: 0n, upTo60: 0n, over60: 0n };
    for (const p of this.people.people()) {
      const a = this.agedCents(p);
      sum.upTo30 += a.upTo30;
      sum.upTo60 += a.upTo60;
      sum.over60 += a.over60;
    }
    return { upTo30: money(sum.upTo30), upTo60: money(sum.upTo60), over60: money(sum.over60) };
  });

  /** A claim's figure: its settled due, else the figure its person's last statement bound it to, else its live due at the rate in force on its date, at least the floor, an overdue
   *  claim's late charge on that floored figure on top;
   *  nothing when it has no figure. */
  figureCents(c: Claim, p: Person): bigint {
    if (c.settledDue !== null) return hundredths(c.settledDue);
    const f = bound(c)
      ? (c.statedDue === null ? null : hundredths(c.statedDue))
      : this.rates.dueCents(hundredths(c.amount), gradeOn(p.grades, c.date), c.date, this.claims.roundingDate(c), c.id);
    return f === null ? 0n : this.claims.chargedCents(c, f);
  }

  /** A claim's due as its row shows it: its figure with two decimals, or n/a when it has none. */
  claimDue(c: Claim, p: Person): string {
    if (c.settledDue !== null) return c.settledDue;
    const f = bound(c)
      ? (c.statedDue === null ? null : hundredths(c.statedDue))
      : this.rates.dueCents(hundredths(c.amount), gradeOn(p.grades, c.date), c.date, this.claims.roundingDate(c), c.id);
    return f === null ? NO_RATE : money(this.claims.chargedCents(c, f));
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
      const now = this.rates.dueCents(hundredths(c.amount), grade, c.date, roundOn, c.id);
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

  /** A person's name as the screen shows it: <name> (<grade in force on the sheet's today>). */
  shownName(p: Person): string {
    return `${p.name} (${this.gradeToday(p)})`;
  }

  /** Each period's live due-total for each person, in cents, by period id then person id: the figures
   *  of the person's claims whose due-by falls in it and that counted by the status they had at the end of
   *  the period's last day, as their status log records it (one added after then does not count), and
   *  that are not left out now by a hold or a deferral — each its due as shown now: a settled claim's
   *  frozen due, else as its person's last statement bound it, else its live due, an overdue claim's
   *  late charge on top (a claim with no figure adds nothing). */
  private readonly livePersonPeriodCents = computed(() => this.personPeriodCents((c, period) => countsAt(statusAt(c, period.to))));

  /** Each period's due-total for each person, in cents, by period id then person id, counting the claims
   *  whose due-by falls in it that pass `counts`, each its due as shown now. */
  private personPeriodCents(counts: (c: Claim, period: Period) => boolean): Map<number, Map<number, bigint>> {
    const grades = new Map(this.people.people().map((p) => [p.id, p.grades]));
    const sums = new Map<number, Map<number, bigint>>();
    for (const c of this.claims.periodCandidates()) {
      const period = this.periods.of(c.dueBy);
      if (!period || !counts(c, period)) continue;
      const byPerson = sums.get(period.id) ?? new Map<number, bigint>();
      byPerson.set(c.personId, (byPerson.get(c.personId) ?? 0n) + this.periodClaimCents(c, grades));
      sums.set(period.id, byPerson);
    }
    return sums;
  }

  /** A claim's due as the period figures count it: a settled claim's frozen due, else as its person's
   *  last statement bound it, else its live due, an overdue claim's late charge on top; 0 with no figure. */
  private periodClaimCents(c: Claim, grades: Map<number, Person['grades']>): bigint {
    let due: bigint | null;
    if (c.settledDue !== null) due = hundredths(c.settledDue);
    else if (bound(c)) due = c.statedDue === null ? null : hundredths(c.statedDue);
    else {
      const grade = grades.get(c.personId);
      due = grade === undefined ? null : this.rates.dueCents(hundredths(c.amount), gradeOn(grade, c.date), c.date, this.claims.roundingDate(c), c.id);
    }
    if (due !== null && c.settledDue === null) due = this.claims.chargedCents(c, due);
    return due ?? 0n;
  }

  /** Each period's carried figure for each person, in cents, by period id then person id: the due of the
   *  person's claims that count (as the period figures count them) whose due-by falls in a period ending
   *  before this one starts, and that were still OPEN or PENDING at the end of this period's last day, as
   *  their status log records it; and their opening balance for it on top. */
  private readonly carriedCents = computed(() => {
    const grades = new Map(this.people.people().map((p) => [p.id, p.grades]));
    const periods = this.periods.periods();
    const sums = new Map<number, Map<number, bigint>>();
    for (const c of this.claims.periodCandidates()) {
      const own = this.periods.of(c.dueBy);
      if (!own) continue;
      for (const period of periods) {
        if (own.to >= period.from) continue;
        const status = statusAt(c, period.to);
        if (status !== 'OPEN' && status !== 'PENDING') continue;
        const byPerson = sums.get(period.id) ?? new Map<number, bigint>();
        byPerson.set(c.personId, (byPerson.get(c.personId) ?? 0n) + this.periodClaimCents(c, grades));
        sums.set(period.id, byPerson);
      }
    }
    for (const period of periods) {
      for (const [id, opening] of Object.entries(period.openings ?? {})) {
        const byPerson = sums.get(period.id) ?? new Map<number, bigint>();
        byPerson.set(Number(id), (byPerson.get(Number(id)) ?? 0n) + hundredths(opening));
        sums.set(period.id, byPerson);
      }
    }
    return sums;
  });

  /** A person's carried figure for a period: fixed when it was closed or soft-closed (0.00 for a person added
   *  since), else live; 0.00 with nothing carried. */
  personPeriodCarried(period: Period, personId: number): string {
    if (period.closed || period.soft) return period.closedPersonCarried?.[personId] ?? '0.00';
    return money(this.carriedCents().get(period.id)?.get(personId) ?? 0n);
  }

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

  /** Each open period's classic due-total, by period id: like the live one, but each claim counts by
   *  its status now (OPEN, PENDING and SETTLED count) rather than the one it had at the period's end. */
  readonly classicPeriodDueTotals = computed(() => {
    const totals = new Map<number, string>();
    for (const [id, byPerson] of this.personPeriodCents((c) => countsAt(c.status))) {
      totals.set(id, money([...byPerson.values()].reduce((sum, d) => sum + d, 0n)));
    }
    return totals;
  });

  /** Everyone's (capped) due-total added up, skipping those that are n/a, not trimmed. */
  readonly classicDueTotal = computed(() =>
    money(this.people.people().reduce((sum, p) => sum + (this.dueCents(p) ?? 0n), 0n)),
  );
}
