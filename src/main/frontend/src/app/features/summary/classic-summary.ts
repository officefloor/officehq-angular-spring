import { Component, computed, inject } from '@angular/core';
import { Figures } from '../../core/figures';
import { Periods } from '../../core/periods';

// The classic summary, beside the summary: per period, its due-total counting each claim by its status
// now (a closed period shows the figure fixed when it closed); and the sheet's due-total with no
// trimming (everyone's due-total added up).
@Component({
  selector: 'app-classic-summary',
  template: `
    <section aria-label="Classic summary" data-testid="classic-summary">
      <h2>Classic summary</h2>
      <table data-testid="classic-periods">
        <caption>By period</caption>
        <thead>
          <tr><th scope="col">Period</th><th scope="col">Due total</th></tr>
        </thead>
        <tbody>
          @for (p of byPeriod(); track p.name) {
            <tr>
              <th scope="row">{{ p.name }}</th>
              <td [attr.data-testid]="'classic-period-' + p.name + '-due-total'">{{ p.dueTotal }}</td>
            </tr>
          }
        </tbody>
      </table>
      <p>Due total: <span data-testid="classic-due-total">{{ dueTotal() }}</span></p>
    </section>
  `,
})
export class ClassicSummaryPanel {
  private readonly periods = inject(Periods);
  private readonly figures = inject(Figures);

  protected readonly byPeriod = computed(() => {
    const live = this.figures.classicPeriodDueTotals();
    return this.periods.periods().map((p) => ({ name: p.name, dueTotal: p.closedTotal ?? live.get(p.id) ?? '0.00' }));
  });

  protected readonly dueTotal = this.figures.classicDueTotal;
}
