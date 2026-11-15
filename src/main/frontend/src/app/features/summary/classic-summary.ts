import { Component, input } from '@angular/core';
import { FigurePipe } from '../../core/figure.pipe';
import { money } from '../claims/claims-api';
import { Period, periodDueCents } from '../periods/periods-api';

// The sheet's figures the classic way, beside the summary: per period, its due-total with each claim
// counted by its status now (a closed period's figure fixed when it closed); and the sheet's
// due-total untrimmed, everyone's due-total added up.
@Component({
  selector: 'app-classic-summary',
  imports: [FigurePipe],
  template: `
    <section aria-labelledby="classic-summary-heading">
      <h2 id="classic-summary-heading">Classic summary</h2>
      <table data-testid="classic-periods">
        <caption>By period</caption>
        <thead>
          <tr><th scope="col">Period</th><th scope="col">Due</th></tr>
        </thead>
        <tbody>
          @for (p of periods(); track p.id) {
            <tr>
              <th scope="row">{{ p.name }}</th>
              <td [attr.data-testid]="'classic-period-' + p.name + '-due-total'">{{ money(periodDue(p)) | figure }}</td>
            </tr>
          }
        </tbody>
      </table>
      <p>Due-total <span data-testid="classic-due-total">{{ money(dueTotal()) | figure }}</span></p>
    </section>
  `,
})
export class ClassicSummary {
  readonly periods = input.required<Period[]>();
  /** Each period's classic due-total in cents, by period id. */
  readonly dueTotals = input.required<Map<number, number>>();
  /** Everyone's due-total added up, in cents. */
  readonly dueTotal = input.required<number>();

  protected readonly money = money;

  protected periodDue(p: Period): number {
    return periodDueCents(p, this.dueTotals());
  }
}
