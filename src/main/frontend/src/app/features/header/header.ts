import { Component, computed, inject } from '@angular/core';
import { Figures, STATUSES, trimmed } from '../../core/figures';
import { GRADES, People } from '../../core/people';
import { hundredths, money } from '../../core/rates';
import { Session, TreeMember } from '../../core/session';

/** How many people there are among rows whose names or aliases match, as names are compared (trimmed, without
 *  regard to case): rows sharing a name, directly or through others, are one person. */
function distinctPeople(rows: string[][]): number {
  const parent = rows.map((_, i) => i);
  const root = (i: number): number => (parent[i] === i ? i : (parent[i] = root(parent[i])));
  const byName = new Map<string, number>();
  rows.forEach((names, i) =>
    names.forEach((n) => {
      const key = n.trim().toLowerCase();
      const seen = byName.get(key);
      if (seen === undefined) byName.set(key, i);
      else parent[root(i)] = root(seen);
    }),
  );
  return rows.filter((_, i) => root(i) === i).length;
}

/** People count, due-total and overdue-total over every person of a tree, the totals trimmed once over them all. */
function overTree(members: TreeMember[]) {
  return {
    peopleCount: distinctPeople(members.map((m) => m.names)),
    dueTotal: money(trimmed(members.filter((m) => m.dueTotal !== null).map((m) => hundredths(m.dueTotal!)))),
    overdueTotal: money(trimmed(members.map((m) => hundredths(m.overdueTotal)))),
  };
}

// The sheet's counters, always in view: everyone on it (retired people included), the claims in
// each status, and the sheet's due- and overdue-totals — the same figures the summary and the
// people list show. Then the same figures consolidated over the sheet and all its descendants (the tree): the
// due- and overdue-totals of every person of the tree added up, trimmed once over them all; the people counted
// once each, people whose names or aliases match (as names are compared) being one; while the screen shows the
// sheet as of a date, every sheet of the tree as it stood at the end of that date. Likewise per grade the due-totals of
// the people on it (retired people left out), and per status the number of claims in it. And the signed-in user's own due- and overdue-totals, when a
// person on the sheet has their name or an alias equal to it; blank otherwise. And the user's crew total: the due-totals of
// the crews that person leads, added up; blank when they lead none. And the REVIEW claims assigned to the user, counted. And, for a steward, a dashboard: the
// sheet's PENDING and REVIEW claims and its overdue claims that count, counted; blank for anyone else.
@Component({
  selector: 'app-header',
  template: `
    <header aria-label="Counters" data-testid="header">
      <dl>
        <div><dt>People</dt><dd data-testid="header-people-count">{{ people.people().length }}</dd></div>
        @for (s of figures.statusCounts(); track s.status) {
          <div><dt>{{ s.status }}</dt><dd [attr.data-testid]="'header-claims-' + s.status + '-count'">{{ s.count }}</dd></div>
        }
        <div><dt>Due total</dt><dd data-testid="header-due-total">{{ figures.dueTotal() }}</dd></div>
        <div><dt>Overdue total</dt><dd data-testid="header-overdue-total">{{ figures.overdueTotal() }}</dd></div>
        <div><dt>My due total</dt><dd data-testid="my-due-total">{{ figures.mine()?.dueTotal }}</dd></div>
        <div><dt>My overdue total</dt><dd data-testid="my-overdue-total">{{ figures.mine()?.overdueTotal }}</dd></div>
        <div><dt>My crew due total</dt><dd data-testid="my-crew-due-total">{{ figures.myCrewDueTotal() }}</dd></div>
        <div><dt>My reviews</dt><dd data-testid="my-reviews-count">{{ figures.myReviewsCount() }}</dd></div>
      </dl>
      <dl aria-label="Steward dashboard">
        <div><dt>Pending to approve</dt><dd data-testid="dash-pending-count">{{ figures.dashboard()?.pending }}</dd></div>
        <div><dt>To review</dt><dd data-testid="dash-review-count">{{ figures.dashboard()?.review }}</dd></div>
        <div><dt>Overdue</dt><dd data-testid="dash-overdue-count">{{ figures.dashboard()?.overdue }}</dd></div>
      </dl>
      <dl aria-label="Consolidated with descendant sheets">
        <div><dt>Consolidated people</dt><dd data-testid="consolidated-people-count">{{ consolidated().peopleCount }}</dd></div>
        <div><dt>Consolidated due total</dt><dd data-testid="consolidated-due-total">{{ consolidated().dueTotal }}</dd></div>
        <div><dt>Consolidated overdue total</dt><dd data-testid="consolidated-overdue-total">{{ consolidated().overdueTotal }}</dd></div>
        @for (g of consolidated().grades; track g.grade) {
          <div><dt>Consolidated grade {{ g.grade }} due total</dt><dd [attr.data-testid]="'consolidated-grade-' + g.grade + '-due-total'">{{ g.dueTotal }}</dd></div>
        }
        @for (s of consolidated().statuses; track s.status) {
          <div><dt>Consolidated {{ s.status }}</dt><dd [attr.data-testid]="'consolidated-status-' + s.status + '-count'">{{ s.count }}</dd></div>
        }
      </dl>
    </header>
  `,
  styles: `
    header { position: sticky; top: 0; z-index: 1; background: #fff; border-bottom: 1px solid #767676; }
    dl { display: flex; flex-wrap: wrap; gap: 0.25rem 1rem; margin: 0; padding: 0.5rem 0; }
    dt, dd { display: inline; }
    dt::after { content: ': '; }
    dd { margin: 0; font-weight: bold; }
  `,
})
export class HeaderCounters {
  protected readonly people = inject(People);
  protected readonly figures = inject(Figures);
  private readonly session = inject(Session);

  protected readonly consolidated = computed(() => {
    const d = this.session.descendants();
    const grades = (own: (grade: string) => bigint) =>
      GRADES.map((grade) => ({ grade, dueTotal: money(own(grade) + hundredths(d.gradeDueTotals[grade] ?? '0.00')) }));
    const statuses = (own: (status: string) => number) =>
      STATUSES.map((status) => ({ status, count: own(status) + (d.statusCounts[status] ?? 0) }));
    if (this.session.asOf()) {
      return {
        ...overTree([...d.ownPeople, ...d.people]),
        grades: grades((g) => hundredths(d.ownGradeDueTotals[g] ?? '0.00')),
        statuses: statuses((s) => d.ownStatusCounts[s] ?? 0),
      };
    }
    const current = this.people.people().filter((p) => !p.retired);
    const counts = this.figures.statusCounts();
    const own: TreeMember[] = this.people.people().map((p) => {
      const due = this.figures.dueCents(p);
      return { names: [p.name, ...p.aliases], dueTotal: due === null ? null : money(due), overdueTotal: money(this.figures.overdueCents(p)) };
    });
    return {
      ...overTree([...own, ...d.people]),
      grades: grades((g) =>
        current
          .filter((p) => this.figures.gradeToday(p) === g)
          .reduce((sum, p) => sum + (this.figures.dueCents(p) ?? 0n), 0n),
      ),
      statuses: statuses((s) => counts.find((c) => c.status === s)?.count ?? 0),
    };
  });
}
