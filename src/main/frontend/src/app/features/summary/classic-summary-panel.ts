import { Component, input } from '@angular/core';
import { FigurePipe } from '../../core/figure';

/** A period's line of the classic summary: its due-total counting each claim by its status now. */
export interface ClassicPeriodSummary {
  name: string;
  dueTotal: string;
}

// The sheet summed up the classic way: per period by each claim's status now, and the due-total untrimmed.
@Component({
  selector: 'app-classic-summary-panel',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="classic-summary-heading" data-testid="classic-summary">
      <h2 id="classic-summary-heading">Classic summary</h2>
      <table>
        <caption>By period</caption>
        <thead>
          <tr><th scope="col">Period</th><th scope="col">Due</th></tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.name) {
            <tr>
              <th scope="row">{{ p.name }}</th>
              <td [attr.data-testid]="'classic-period-' + p.name + '-due-total'">{{ p.dueTotal | figure }}</td>
            </tr>
          }
        </tbody>
      </table>
      <dl>
        <dt>Due, untrimmed</dt>
        <dd data-testid="classic-due-total">{{ dueTotal() | figure }}</dd>
      </dl>
    </section>
  `,
})
export class ClassicSummaryPanel {
  readonly periods = input.required<ClassicPeriodSummary[]>();
  readonly dueTotal = input.required<string>();
}
