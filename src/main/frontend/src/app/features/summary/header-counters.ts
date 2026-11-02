import { Component, input } from '@angular/core';
import { StatusSummary } from './summary-panel';

// The sheet's counters, always on top: how many people it has (retired included), how many
// claims are in each status, and its due-total and overdue-total, each the figure it repeats.
@Component({
  selector: 'app-header-counters',
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
        <dd data-testid="header-due-total">{{ dueTotal() }}</dd>
        <dt>Overdue</dt>
        <dd data-testid="header-overdue-total">{{ overdueTotal() }}</dd>
      </dl>
    </header>
  `,
})
export class HeaderCounters {
  readonly peopleCount = input.required<number>();
  readonly statuses = input.required<StatusSummary[]>();
  readonly dueTotal = input.required<string>();
  readonly overdueTotal = input.required<string>();
}
