import { Component, computed, input } from '@angular/core';
import { Claim, money } from './claims.service';
import { GRADES, Grade } from './people.service';
import { Period } from './periods.service';

/** The statuses the summary counts claims by. */
const STATUSES = ['DRAFT', 'OPEN', 'PENDING', 'SETTLED', 'VOID'] as const;

/** A person as the summary counts them: their grade on the sheet's today, whether they are retired,
 *  and their due-total in cents (null when it is n/a). */
export interface SummaryPerson {
  grade: Grade;
  retired: boolean;
  dueCents: number | null;
}

// A summary of the sheet, worked from the same figures the list shows: per grade, how many people
// not retired are on it today and their due-totals added up (a due-total that is n/a is skipped);
// per period, its due-total; per status, how many of the sheet's claims have it.
@Component({
  selector: 'app-summary',
  template: `
    <section aria-labelledby="summary-heading" data-testid="summary">
      <h2 id="summary-heading">Summary</h2>
      <table>
        <thead>
          <tr>
            <th scope="col">Grade</th>
            <th scope="col">People</th>
            <th scope="col">Due</th>
          </tr>
        </thead>
        <tbody>
          @for (g of byGrade(); track g.grade) {
            <tr>
              <th scope="row">{{ g.grade }}</th>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-count'">{{ g.count }}</td>
              <td [attr.data-testid]="'summary-grade-' + g.grade + '-due-total'">{{ g.dueTotal }}</td>
            </tr>
          }
        </tbody>
      </table>
      @if (byPeriod().length > 0) {
        <table>
          <thead>
            <tr>
              <th scope="col">Period</th>
              <th scope="col">Due</th>
            </tr>
          </thead>
          <tbody>
            @for (p of byPeriod(); track p.name) {
              <tr>
                <th scope="row">{{ p.name }}</th>
                <td [attr.data-testid]="'summary-period-' + p.name + '-due-total'">{{ p.dueTotal }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
      <table>
        <thead>
          <tr>
            <th scope="col">Status</th>
            <th scope="col">Claims</th>
          </tr>
        </thead>
        <tbody>
          @for (s of byStatus(); track s.status) {
            <tr>
              <th scope="row">{{ s.status }}</th>
              <td [attr.data-testid]="'summary-status-' + s.status + '-count'">{{ s.count }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
  styles: `
    table {
      border-collapse: collapse;
      display: inline-table;
      margin: 0 2rem 1rem 0;
      vertical-align: top;
    }
    th,
    td {
      text-align: left;
      padding: 0.25rem 0.75rem 0.25rem 0;
    }
  `,
})
export class Summary {
  /** Everyone on the sheet. */
  readonly people = input.required<SummaryPerson[]>();
  /** The sheet's periods. */
  readonly periods = input.required<Period[]>();
  /** Each open period's live due-total, by name. */
  readonly periodDueTotals = input.required<Record<string, string>>();
  /** The sheet's claims. */
  readonly claims = input.required<Claim[]>();

  protected readonly byGrade = computed(() =>
    GRADES.map((grade) => {
      const on = this.people().filter((p) => !p.retired && p.grade === grade);
      return {
        grade,
        count: on.length,
        dueTotal: money(on.reduce((sum, p) => sum + (p.dueCents ?? 0), 0)),
      };
    }),
  );

  /** Each period's due-total as its row shows it: fixed once it is closed, live while it is open. */
  protected readonly byPeriod = computed(() =>
    [...this.periods()]
      .sort((a, b) => a.from.localeCompare(b.from))
      .map((p) => ({
        name: p.name,
        dueTotal: p.closed ? p.closedTotal : (this.periodDueTotals()[p.name] ?? '0.00'),
      })),
  );

  protected readonly byStatus = computed(() =>
    STATUSES.map((status) => ({
      status,
      count: this.claims().filter((c) => c.status === status).length,
    })),
  );
}
