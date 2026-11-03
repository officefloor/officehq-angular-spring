import { Component, input } from '@angular/core';
import { StatusSummary } from './summary-panel';
import { FigurePipe } from '../../core/figure';

// The sheet's counters, always on top: how many people it has (retired included), how many
// claims are in each status, and its due-total and overdue-total, each the figure it repeats;
// and the signed-in user's own due-total and overdue-total, when a person on the sheet is theirs.
@Component({
  selector: 'app-header-counters',
  imports: [FigurePipe],
  template: `
    <header aria-label="Sheet counters" data-testid="header">
      <dl>
        <dt>People</dt>
        <dd data-testid="header-people-count">{{ peopleCount() }}</dd>
        @for (s of statuses(); track s.status) {
          <dt>{{ s.status }}</dt>
          <dd [attr.data-testid]="'header-claims-' + s.status + '-count'">{{ s.count }}</dd>
        }
        <dt>Due</dt>
        <dd data-testid="header-due-total">{{ dueTotal() | figure }}</dd>
        <dt>Overdue</dt>
        <dd data-testid="header-overdue-total">{{ overdueTotal() | figure }}</dd>
        <dt>My due</dt>
        <dd data-testid="my-due-total">{{ myDueTotal() | figure }}</dd>
        <dt>My overdue</dt>
        <dd data-testid="my-overdue-total">{{ myOverdueTotal() | figure }}</dd>
      </dl>
    </header>
  `,
})
export class HeaderCounters {
  readonly peopleCount = input.required<number>();
  readonly statuses = input.required<StatusSummary[]>();
  readonly dueTotal = input.required<string>();
  readonly overdueTotal = input.required<string>();
  /** The signed-in user's own due-total and overdue-total: blank when no person on the sheet has
   *  their name or alias. */
  readonly myDueTotal = input.required<string>();
  readonly myOverdueTotal = input.required<string>();
}
