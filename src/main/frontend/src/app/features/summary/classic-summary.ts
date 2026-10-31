import { Component, input } from '@angular/core';

/** A period's classic line: its due-total with each claim counted by its status now (a closed period: the figure
 *  fixed when it closed). */
export interface ClassicPeriod {
  name: string;
  dueTotal: string;
}

// The classic summary, beside the summary: per period its due-total by status now, and the sheet's due-total untrimmed.
@Component({
  selector: 'app-classic-summary',
  template: `
    <section aria-labelledby="classic-summary-heading">
      <h2 id="classic-summary-heading">Classic summary</h2>
      <ul aria-label="Classic by period">
        @for (p of periods(); track p.name) {
          <li>Period {{ p.name }}: due
            <span [attr.data-testid]="'classic-period-' + p.name + '-due-total'">{{ p.dueTotal }}</span></li>
        }
      </ul>
      <p>Due-total: <span data-testid="classic-due-total">{{ dueTotal() }}</span></p>
    </section>
  `,
})
export class ClassicSummaryPanel {
  readonly periods = input.required<readonly ClassicPeriod[]>();
  readonly dueTotal = input.required<string>();
}
