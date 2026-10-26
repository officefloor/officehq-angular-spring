import { Component, computed, input } from '@angular/core';
import { STATUSES } from '../summary/summary';

// The sheet's counters, shown at all times: each repeats a figure shown elsewhere — the status counts those of the
// summary, the two totals the sheet's totals.
@Component({
  selector: 'app-header',
  template: `
    <header aria-label="Sheet counters">
      <dl>
        <dt>People</dt>
        <dd data-testid="header-people-count">{{ peopleCount() }}</dd>
        @for (s of byStatus(); track s.status) {
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
export class Header {
  // Everyone on the sheet, retired people included.
  readonly peopleCount = input(0);
  // The status of every claim on the sheet, as the summary counts them.
  readonly statuses = input<readonly string[]>([]);
  readonly dueTotal = input('');
  readonly overdueTotal = input('');

  protected readonly byStatus = computed(() =>
    STATUSES.map((status) => ({ status, count: this.statuses().filter((s) => s === status).length })),
  );
}
