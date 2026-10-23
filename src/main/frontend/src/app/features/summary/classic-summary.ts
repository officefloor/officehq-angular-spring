import { Component, input } from '@angular/core';

/** A period's line in the classic summary: its due-total counting each claim by its status now. */
export interface ClassicPeriodSummary {
  name: string;
  dueTotal: string;
}

// The sheet's classic summary, shown beside the summary: per period, the due-total counting each claim by
// its status now (a closed period keeps the figure fixed when it closed), and everyone's due-total added up
// with no trimming.
@Component({
  selector: 'app-classic-summary',
  template: `
    <section aria-labelledby="classic-summary-heading">
      <h2 id="classic-summary-heading">Classic summary</h2>
      <table>
        <caption>By period</caption>
        <thead>
          <tr>
            <th scope="col">Period</th>
            <th scope="col">Due</th>
          </tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.name) {
            <tr>
              <th scope="row">{{ p.name }}</th>
              <td [attr.data-testid]="'classic-period-' + p.name + '-due-total'">{{ p.dueTotal }}</td>
            </tr>
          }
        </tbody>
      </table>
      <p>Due-total: <span data-testid="classic-due-total">{{ dueTotal() }}</span></p>
    </section>
  `,
})
export class ClassicSummary {
  readonly periods = input.required<ClassicPeriodSummary[]>();
  readonly dueTotal = input.required<string>();
}
