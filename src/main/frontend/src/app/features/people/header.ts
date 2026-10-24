import { Component, computed, input } from '@angular/core';
import { Claim } from './claims.service';
import { STATUSES } from './summary';

// The sheet's header, shown at all times: how many people are on the sheet (retired ones
// included), how many claims are in each status (as the summary counts them), and the sheet's
// due-total and overdue-total (as its totals show them).
@Component({
  selector: 'app-header',
  template: `
    <header aria-label="Sheet counters" data-testid="header">
      <dl>
        <div>
          <dt>People</dt>
          <dd data-testid="header-people-count">{{ peopleCount() }}</dd>
        </div>
        @for (s of byStatus(); track s.status) {
          <div>
            <dt>{{ s.status }}</dt>
            <dd [attr.data-testid]="'header-claims-' + s.status + '-count'">{{ s.count }}</dd>
          </div>
        }
        <div>
          <dt>Due</dt>
          <dd data-testid="header-due-total">{{ dueTotal() }}</dd>
        </div>
        <div>
          <dt>Overdue</dt>
          <dd data-testid="header-overdue-total">{{ overdueTotal() }}</dd>
        </div>
      </dl>
    </header>
  `,
  styles: `
    dl {
      display: flex;
      flex-wrap: wrap;
      gap: 1.5rem;
      margin: 0 0 1rem;
    }
    dt {
      font-weight: bold;
    }
    dd {
      margin: 0;
    }
  `,
})
export class Header {
  /** How many people are on the sheet, retired ones included. */
  readonly peopleCount = input.required<number>();
  /** The sheet's claims. */
  readonly claims = input.required<Claim[]>();
  /** The sheet's due-total, with two decimals. */
  readonly dueTotal = input.required<string>();
  /** The sheet's overdue-total, with two decimals. */
  readonly overdueTotal = input.required<string>();

  protected readonly byStatus = computed(() =>
    STATUSES.map((status) => ({
      status,
      count: this.claims().filter((c) => c.status === status).length,
    })),
  );
}
