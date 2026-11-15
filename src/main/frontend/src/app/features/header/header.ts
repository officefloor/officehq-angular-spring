import { Component, computed, input } from '@angular/core';
import { FigurePipe } from '../../core/figure.pipe';
import { Claim } from '../claims/claims-api';
import { DescendantFigures } from '../people/people-api';
import { statusCounts } from '../summary/summary';

// The sheet's counters, shown at all times: how many people are on it (retired ones too), how many
// claims are in each status (as the summary counts them), and its due-total and overdue-total (as
// the sheet's totals show them); and the same figures consolidated over the sheet and all its
// descendants (each sheet's totals as that sheet shows them, added up); and the signed-in user's own
// due-total and overdue-total, when a person on the sheet goes by their name.
@Component({
  selector: 'app-header',
  imports: [FigurePipe],
  template: `
    <header aria-label="Sheet counters" data-testid="header">
      <p>People <strong data-testid="header-people-count">{{ peopleCount() }}</strong></p>
      <p>Claims
        @for (s of byStatus(); track s.status) {
          {{ s.status }} <strong [attr.data-testid]="'header-claims-' + s.status + '-count'">{{ s.count }}</strong>
        }
      </p>
      <p>Due total <strong data-testid="header-due-total">{{ dueTotal() | figure }}</strong></p>
      <p>Overdue total <strong data-testid="header-overdue-total">{{ overdueTotal() | figure }}</strong></p>
      <p>My due total <strong data-testid="my-due-total">{{ myDueTotal() | figure }}</strong>,
        my overdue total <strong data-testid="my-overdue-total">{{ myOverdueTotal() | figure }}</strong></p>
      <p>With its descendants: people <strong data-testid="consolidated-people-count">{{ consolidated().people }}</strong>,
        due total <strong data-testid="consolidated-due-total">{{ consolidated().dueTotal | figure }}</strong>,
        overdue total <strong data-testid="consolidated-overdue-total">{{ consolidated().overdueTotal | figure }}</strong></p>
    </header>
  `,
})
export class Header {
  readonly peopleCount = input.required<number>();
  readonly claims = input.required<Claim[]>();
  readonly dueTotal = input.required<string>();
  readonly overdueTotal = input.required<string>();
  readonly consolidated = input.required<DescendantFigures>();
  /** The signed-in user's own figures (of the person on the sheet going by their name); null when none. */
  readonly myDueTotal = input<string | null>(null);
  readonly myOverdueTotal = input<string | null>(null);

  protected readonly byStatus = computed(() => statusCounts(this.claims()));
}
