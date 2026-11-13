import { Component, computed, inject } from '@angular/core';
import { Figures } from '../../core/figures';
import { People } from '../../core/people';
import { hundredths, money } from '../../core/rates';
import { Session } from '../../core/session';

// The sheet's counters, always in view: everyone on it (retired people included), the claims in
// each status, and the sheet's due- and overdue-totals — the same figures the summary and the
// people list show. Then the same figures consolidated over the sheet and all its descendants: each
// descendant's as that sheet shows them, added to this sheet's own; while the screen shows the sheet as
// of a date, every sheet of the tree as it stood at the end of that date. And the signed-in user's own due- and overdue-totals, when a
// person on the sheet has their name or an alias equal to it; blank otherwise. And the user's crew total: the due-totals of
// the crews that person leads, added up; blank when they lead none. And, for a steward, a dashboard: the
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
    if (this.session.asOf()) {
      return {
        peopleCount: d.ownPeopleCount + d.peopleCount,
        dueTotal: money(hundredths(d.ownDueTotal) + hundredths(d.dueTotal)),
        overdueTotal: money(hundredths(d.ownOverdueTotal) + hundredths(d.overdueTotal)),
      };
    }
    return {
      peopleCount: this.people.people().length + d.peopleCount,
      dueTotal: money(hundredths(this.figures.dueTotal()) + hundredths(d.dueTotal)),
      overdueTotal: money(hundredths(this.figures.overdueTotal()) + hundredths(d.overdueTotal)),
    };
  });
}
