import { FigurePipe } from '../../core/figure.pipe';
import { Component, input } from '@angular/core';

/** A period's line of the classic summary: its due-total counting each claim by its status now. */
export interface ClassicPeriodSummary {
  name: string;
  dueTotal: string;
}

// The sheet in classic figures, beside the summary: per period, its due-total counting each claim by
// its status now (a closed period's is the figure fixed when it closed); and the sheet's due-total,
// everyone's added up with no trimming.
@Component({
  selector: 'app-classic-summary',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="classic-summary-heading" data-testid="classic-summary">
      <h2 id="classic-summary-heading">Classic summary</h2>
      <table aria-label="Classic summary by period">
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
      <p>Due in total: <strong data-testid="classic-due-total">{{ dueTotal() | figure }}</strong></p>
    </section>
  `,
})
export class ClassicSummary {
  readonly periods = input.required<ClassicPeriodSummary[]>();
  readonly dueTotal = input.required<string>();
}
