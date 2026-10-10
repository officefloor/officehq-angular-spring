import { Component, computed, input } from '@angular/core';
import { MoneyPipe } from '../currencies/money.pipe';
import { CurrencyCode } from '../clients/client.service';
import { ProjectBudget } from './project.service';

// A job's profitability: what has been invoiced set against its budget, and by how much it is over or under.
@Component({
  selector: 'app-job-profit',
  imports: [MoneyPipe],
  template: `
    <section aria-labelledby="job-profit-heading" data-testid="job-profit-section">
      <h3 id="job-profit-heading">Profitability</h3>
      <dl>
        <dt>Budget</dt>
        <dd data-testid="job-profit-budget">
          @if (figures().budget === null) {
            No budget set
          } @else {
            {{ figures().budget | money: currency() }}
          }
        </dd>
        <dt>Invoiced</dt>
        <dd data-testid="job-profit-invoiced">{{ figures().invoiced | money: currency() }}</dd>
        <dt>Invoiced against budget</dt>
        <dd data-testid="job-profit-variance">
          @if (variance() === null) {
            —
          } @else if (variance()! > 0) {
            {{ variance() | money: currency() }} over budget
          } @else if (variance()! < 0) {
            {{ -variance()! | money: currency() }} under budget
          } @else {
            On budget
          }
        </dd>
      </dl>
    </section>
  `,
  styles: `
    dl {
      display: grid;
      grid-template-columns: max-content auto;
      gap: 0.25rem 1rem;
    }
    dd {
      margin: 0;
    }
  `,
})
export class JobProfit {
  /** The job's budget and what has been invoiced against it. */
  readonly figures = input.required<ProjectBudget>();
  /** The currency of the client the job is for. */
  readonly currency = input.required<CurrencyCode>();

  /** Invoiced less budget: positive when over budget, negative when under; null when no budget is set. */
  protected readonly variance = computed(() => {
    const { budget, invoiced } = this.figures();
    return budget === null ? null : Math.round((invoiced - budget) * 100) / 100;
  });
}
