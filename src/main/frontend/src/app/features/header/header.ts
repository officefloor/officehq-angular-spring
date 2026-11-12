import { Component, computed, inject } from '@angular/core';
import { Figures } from '../../core/figures';
import { People } from '../../core/people';
import { hundredths, money } from '../../core/rates';
import { Session } from '../../core/session';

// The sheet's counters, always in view: everyone on it (retired people included), the claims in
// each status, and the sheet's due- and overdue-totals — the same figures the summary and the
// people list show. Then the same figures consolidated over the sheet and all its descendants: each
// descendant's as that sheet shows them, added to this sheet's own.
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
    return {
      peopleCount: this.people.people().length + d.peopleCount,
      dueTotal: money(hundredths(this.figures.dueTotal()) + hundredths(d.dueTotal)),
      overdueTotal: money(hundredths(this.figures.overdueTotal()) + hundredths(d.overdueTotal)),
    };
  });
}
