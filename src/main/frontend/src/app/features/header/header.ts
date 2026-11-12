import { Component, inject } from '@angular/core';
import { Figures } from '../../core/figures';
import { People } from '../../core/people';

// The sheet's counters, always in view: everyone on it (retired people included), the claims in
// each status, and the sheet's due- and overdue-totals — the same figures the summary and the
// people list show.
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
}
