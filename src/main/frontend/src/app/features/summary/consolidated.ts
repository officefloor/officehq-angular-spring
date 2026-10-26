import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { FigurePipe } from '../../core/figure.pipe';
import { Session } from '../../core/session';
import { cents, money } from '../claims/claims-api';
import { ConsolidatedApi, DescendantsView } from './consolidated-api';
import { GradeSummary, StatusSummary } from './summary';

// The consolidated figures over the sheet and all its descendants (the sheets naming it as their
// parent, then theirs, and so on): the number of people, and each sheet's due-total and overdue-total
// as that sheet shows it, added up; per grade, the due-totals of the people on it who are not retired,
// added up; and per status, how many claims have it. The sheet's own figures are those it shows; its descendants' are
// re-read each time a sheet is loaded. A sheet with no children shows its own figures. While the screen
// shows the sheet as of a date, the consolidated figures are as of that date too: every sheet of the
// tree as it stood at the end of that day, with that date as its today.
@Component({
  selector: 'app-consolidated',
  imports: [FigurePipe],
  template: `
    <section aria-label="Consolidated figures" data-testid="consolidated">
      <dl>
        <dt>People (with descendants)</dt>
        <dd data-testid="consolidated-people-count">{{ peopleCount() }}</dd>
        <dt>Due (with descendants)</dt>
        <dd data-testid="consolidated-due-total">{{ dueTotal() | figure }}</dd>
        <dt>Overdue (with descendants)</dt>
        <dd data-testid="consolidated-overdue-total">{{ overdueTotal() | figure }}</dd>
      </dl>
      <table aria-label="Consolidated due by grade">
        <thead>
          <tr><th scope="col">Grade</th><th scope="col">Due (with descendants)</th></tr>
        </thead>
        <tbody>
          @for (g of grades(); track g.grade) {
            <tr>
              <th scope="row">{{ g.grade }}</th>
              <td [attr.data-testid]="'consolidated-grade-' + g.grade + '-due-total'">{{ g.dueTotal | figure }}</td>
            </tr>
          }
        </tbody>
      </table>
      <table aria-label="Consolidated claims by status">
        <thead>
          <tr><th scope="col">Status</th><th scope="col">Claims (with descendants)</th></tr>
        </thead>
        <tbody>
          @for (s of statuses(); track s.status) {
            <tr>
              <th scope="row">{{ s.status }}</th>
              <td [attr.data-testid]="'consolidated-status-' + s.status + '-count'">{{ s.count }}</td>
            </tr>
          }
        </tbody>
      </table>
    </section>
  `,
})
export class Consolidated {
  private readonly session = inject(Session);
  private readonly api = inject(ConsolidatedApi);

  /** The sheet's own figures, as it shows them. */
  readonly ownPeopleCount = input.required<number>();
  readonly ownDueTotal = input.required<string>();
  readonly ownOverdueTotal = input.required<string>();
  /** The sheet's own summary lines per grade and per status, as it shows them. */
  readonly ownGrades = input.required<GradeSummary[]>();
  readonly ownStatuses = input.required<StatusSummary[]>();

  /** The loaded sheet's descendants' figures; null until read (or when the read failed). */
  private readonly descendants = signal<DescendantsView | null>(null);

  /** The sheet's own figures, as it shows them. */
  private readonly own = computed(() =>
    ({ peopleCount: this.ownPeopleCount(), dueTotal: this.ownDueTotal(), overdueTotal: this.ownOverdueTotal() }));

  protected readonly peopleCount = computed(() => this.own().peopleCount + (this.descendants()?.peopleCount ?? 0));
  protected readonly dueTotal = computed(() =>
    money(cents(this.own().dueTotal) + cents(this.descendants()?.dueTotal ?? '0.00')));
  protected readonly overdueTotal = computed(() =>
    money(cents(this.own().overdueTotal) + cents(this.descendants()?.overdueTotal ?? '0.00')));

  protected readonly grades = computed(() => this.ownGrades().map((g) => ({
    grade: g.grade,
    dueTotal: money(cents(g.dueTotal) + cents(this.descendants()?.gradeDueTotals[g.grade] ?? '0.00')),
  })));
  protected readonly statuses = computed(() => this.ownStatuses().map((s) => ({
    status: s.status,
    count: s.count + (this.descendants()?.statusCounts[s.status] ?? 0),
  })));

  constructor() {
    effect(() => {
      // A new sheet view each time a sheet is loaded, so its descendants are read again.
      const sheet = this.session.sheet();
      this.descendants.set(null);
      if (sheet === null) return;
      this.api.descendants(sheet.id).then(
        (view) => { if (this.session.sheet() === sheet) this.descendants.set(view); },
        () => undefined);
    });
  }
}
