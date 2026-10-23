import { Component, input } from '@angular/core';
import { FigurePipe } from '../../core/locale';
import { StatusSummary } from '../summary/summary';

// The sheet's header: its counters, each repeating a figure shown elsewhere on the screen — the people
// on the sheet (retired people included), the summary's claim count per status, and the sheet's
// due-total and overdue-total; and the signed-in user's own figures, when a person goes by their name,
// with the due-total of the crews that person leads.
@Component({
  selector: 'app-header',
  imports: [FigurePipe],
  template: `
    <header aria-label="Sheet counters">
      <p>People <strong data-testid="header-people-count">{{ peopleCount() }}</strong></p>
      <p>
        @for (s of statuses(); track s.status) {
          {{ s.status }} <strong [attr.data-testid]="'header-claims-' + s.status + '-count'">{{ s.count }}</strong>
        }
      </p>
      <p>Due <strong data-testid="header-due-total">{{ dueTotal() | figure }}</strong></p>
      <p>Overdue <strong data-testid="header-overdue-total">{{ overdueTotal() | figure }}</strong></p>
      <p>My due <strong data-testid="my-due-total">{{ myDueTotal() | figure }}</strong></p>
      <p>My overdue <strong data-testid="my-overdue-total">{{ myOverdueTotal() | figure }}</strong></p>
      <p>My crews <strong data-testid="my-crew-due-total">{{ myCrewDueTotal() | figure }}</strong></p>
    </header>
  `,
})
export class Header {
  readonly peopleCount = input.required<number>();
  readonly statuses = input.required<StatusSummary[]>();
  readonly dueTotal = input.required<string>();
  readonly overdueTotal = input.required<string>();
  /** The signed-in user's own due-total and overdue-total, as the person going by their name; empty when none does. */
  readonly myDueTotal = input.required<string>();
  readonly myOverdueTotal = input.required<string>();
  /** The due-totals of the crews led by the person going by the user's name, added up; empty when they lead none. */
  readonly myCrewDueTotal = input.required<string>();
}
