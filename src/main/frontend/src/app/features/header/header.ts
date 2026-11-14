import { Component, computed, input } from '@angular/core';
import { Claim } from '../claims/claims-api';
import { statusCounts } from '../summary/summary';

// The sheet's counters, shown at all times: how many people are on it (retired ones too), how many
// claims are in each status (as the summary counts them), and its due-total and overdue-total (as
// the sheet's totals show them).
@Component({
  selector: 'app-header',
  template: `
    <header aria-label="Sheet counters" data-testid="header">
      <p>People <strong data-testid="header-people-count">{{ peopleCount() }}</strong></p>
      <p>Claims
        @for (s of byStatus(); track s.status) {
          {{ s.status }} <strong [attr.data-testid]="'header-claims-' + s.status + '-count'">{{ s.count }}</strong>
        }
      </p>
      <p>Due total <strong data-testid="header-due-total">{{ dueTotal() }}</strong></p>
      <p>Overdue total <strong data-testid="header-overdue-total">{{ overdueTotal() }}</strong></p>
    </header>
  `,
})
export class Header {
  readonly peopleCount = input.required<number>();
  readonly claims = input.required<Claim[]>();
  readonly dueTotal = input.required<string>();
  readonly overdueTotal = input.required<string>();

  protected readonly byStatus = computed(() => statusCounts(this.claims()));
}
